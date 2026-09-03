package dev.rajeev.orchestrator.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.agents.Agent;
import dev.rajeev.orchestrator.agents.AgentContext;
import dev.rajeev.orchestrator.agents.Agents;
import dev.rajeev.orchestrator.agents.Artifacts;
import dev.rajeev.orchestrator.agents.Artifacts.Ambiguity;
import dev.rajeev.orchestrator.agents.Artifacts.RequirementsSpec;
import dev.rajeev.orchestrator.agents.Artifacts.TaskPlan;
import dev.rajeev.orchestrator.core.Approvals.Approver;
import dev.rajeev.orchestrator.core.Approvals.Outcome;
import dev.rajeev.orchestrator.core.Approvals.Question;
import dev.rajeev.orchestrator.core.Approvals.Request;
import dev.rajeev.orchestrator.core.Controls.SafeStop;
import dev.rajeev.orchestrator.core.Types.Actor;
import dev.rajeev.orchestrator.core.Types.ApprovalRecord;
import dev.rajeev.orchestrator.core.Types.ApprovalSpec;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.ArtifactRecord;
import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.core.Types.Decision;
import dev.rajeev.orchestrator.core.Types.GateResult;
import dev.rajeev.orchestrator.core.Types.Pending;
import dev.rajeev.orchestrator.core.Types.RiskLevel;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.RunStatus;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.core.Types.StageStatus;
import dev.rajeev.orchestrator.llm.LlmProvider;
import dev.rajeev.orchestrator.tools.Toolchain;
import dev.rajeev.orchestrator.tools.Workspace;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * The engine. One instance drives one run. It is re-entrant: constructing it over a persisted
 * {@link RunStore} and calling {@link #run()} again continues exactly where the previous process left
 * off (after a safe-stop, a crash, or an approval).
 *
 * Non-linear, stateful execution: every stage whose dependencies are satisfied runs concurrently on a
 * virtual thread; batches join before the next scheduling decision; stages pause at human checkpoints
 * and resume from the same gate; upstream changes invalidate downstream work and re-expand the graph.
 */
public final class Orchestrator {

    public record Injection(String afterStage, String kind, String requirement, String note) {}

    /** Wiring for one run. Plain fields so tests and the CLI can assemble it without a framework. */
    public static final class Config {
        public RunStore store;
        public Workspace workspace;
        public Toolchain toolchain;
        public LlmProvider llm;
        public Approver approver;
        public PolicyEngine policy = new PolicyEngine();
        public SafeStop safeStop;
        public List<Injection> injections = List.of();
        public int concurrency = 4;
        public LongConsumer sleeper = ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        public Consumer<RunEvent> onEvent;
    }

    private enum StageOutcome { SUCCEEDED, FAILED, WAITING_APPROVAL, STOPPED, SKIPPED }

    private record StageResult(StageOutcome outcome, JsonNode output, ArtifactRecord previous) {}

    private final Config cfg;
    private final RunStore store;
    private final RunState state;
    private WorkflowGraph graph;
    private final Set<String> injectionsDone = new HashSet<>();

    public Orchestrator(Config cfg) {
        this.cfg = cfg;
        this.store = cfg.store;
        this.state = store.state();
        this.graph = new WorkflowGraph(state.graph);
        if (cfg.safeStop == null) cfg.safeStop = new SafeStop(store.dir().resolve("STOP"), Controls.Budget.DEFAULT);
        for (RunEvent e : store.events()) {
            if (e.type().equals("replan.injected") && e.payload() != null && e.payload().get("afterStage") != null) injectionsDone.add(e.payload().get("afterStage").toString());
        }
    }

    public RunState state() { return state; }

    public WorkflowGraph graph() { return graph; }

    public String toolchainName() { return cfg.toolchain.name(); }

    public String llmName() { return cfg.llm.name(); }

    /* ---------------------------------------------------------------------- */
    /* Main loop                                                               */
    /* ---------------------------------------------------------------------- */

    public RunState run() {
        if (state.status == RunStatus.COMPLETED || state.status == RunStatus.FAILED) return state;
        if (state.status == RunStatus.CREATED) {
            Path snap = cfg.workspace.snapshot(store.dir().resolve("snapshots"), "initial");
            emit("run.started", Actor.SYSTEM, Map.of("initialSnapshot", snap.toString(), "approver", cfg.approver.name(), "llm", cfg.llm.name(), "toolchain", cfg.toolchain.name()), null, null);
        } else {
            emit("run.resumed", Actor.SYSTEM, Map.of("from", state.status.name().toLowerCase()), null, null);
        }
        state.status = RunStatus.RUNNING;
        store.snapshot();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (;;) {
                String stopReason = cfg.safeStop.shouldStop(state.metrics);
                if (stopReason != null) return finish(RunStatus.STOPPED, "safe-stop: " + stopReason);

                List<StageDefinition> ready = graph.ready(state.stages);
                boolean running = state.stages.values().stream().anyMatch(s -> s.status == StageStatus.RUNNING);
                if (ready.isEmpty() && !running) {
                    var states = state.stages.values();
                    if (states.stream().anyMatch(s -> s.status == StageStatus.WAITING_APPROVAL)) return finish(RunStatus.PAUSED, "waiting for human approval");
                    if (states.stream().allMatch(s -> s.status == StageStatus.SUCCEEDED || s.status == StageStatus.SKIPPED)) return finish(RunStatus.COMPLETED, null);
                    List<String> failed = states.stream().filter(s -> s.status == StageStatus.FAILED || s.status == StageStatus.ROLLED_BACK).map(s -> s.id).toList();
                    return finish(RunStatus.FAILED, failed.isEmpty() ? "no runnable stages (blocked)" : "stage(s) failed: " + String.join(", ", failed));
                }

                // Parallel paths: every ready stage runs concurrently, up to the limit; the batch joins before the next decision.
                List<StageDefinition> batch = ready.subList(0, Math.min(ready.size(), cfg.concurrency));
                if (batch.size() > 1) emit("scheduler.parallel", Actor.SYSTEM, Map.of("stages", batch.stream().map(StageDefinition::id).toList()), null, null);
                List<Future<StageResult>> futures = new ArrayList<>();
                for (StageDefinition stage : batch) futures.add(pool.submit(() -> runStage(stage)));
                List<StageResult> results = new ArrayList<>();
                for (Future<StageResult> f : futures) {
                    try {
                        results.add(f.get());
                    } catch (Exception e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
                    }
                }
                if (batch.size() > 1) emit("scheduler.join", Actor.SYSTEM, Map.of("stages", batch.stream().map(StageDefinition::id).toList(), "outcomes", results.stream().map(r -> r.outcome().name().toLowerCase().replace('_', '-')).toList()), null, null);

                for (int i = 0; i < batch.size(); i++) {
                    StageDefinition stage = batch.get(i);
                    StageResult r = results.get(i);
                    switch (r.outcome()) {
                        case STOPPED -> { return finish(RunStatus.STOPPED, state.stages.get(stage.id()).lastError); }
                        case FAILED -> { if (!applyFailurePolicy(stage)) return finish(RunStatus.FAILED, "stage '" + stage.id() + "' failed (" + stage.onFailure().name().toLowerCase().replace('_', '-') + ")"); }
                        case SUCCEEDED -> afterSuccess(stage, r);
                        default -> { }
                    }
                }
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Stage execution (runs on a virtual thread)                              */
    /* ---------------------------------------------------------------------- */

    private StageResult runStage(StageDefinition stage) {
        StageState st = state.stages.get(stage.id());
        if (st.pending != null) return continueFromPending(stage, st);

        if (st.status == StageStatus.INVALIDATED) st.attemptBase = st.attempts;
        if (st.startedAt == null || st.status == StageStatus.INVALIDATED) st.startedAt = Instant.now().toString();
        st.status = StageStatus.RUNNING;
        st.inputHashes = store.currentHashes(stage.consumes());
        emit("stage.started", Actor.SYSTEM, Map.of("agent", agentName(stage), "dependsOn", stage.dependsOn(), "inputHashes", st.inputHashes), stage.id(), null);

        for (String g : stage.entryGates()) {
            GateResult res = Gates.gate(g).evaluate(gateContext(stage, null, cfg.workspace));
            emit("gate.evaluated", Actor.SYSTEM, payload("gate", g, "kind", "entry", "passed", res.passed(), "details", res.details()), stage.id(), null);
            if (!res.passed()) return new StageResult(failStage(stage, st, "entry gate '" + g + "' failed: " + res.details()), null, null);
        }

        while (st.attempts - st.attemptBase() < stage.retry().maxAttempts()) {
            st.attempts++;
            synchronized (state.metrics) { state.metrics.attemptsTotal++; }
            int attempt = st.attempts;
            emit("stage.attempt", Actor.SYSTEM, Map.of("attempt", attempt, "maxAttempts", stage.retry().maxAttempts(), "feedback", List.copyOf(st.feedback)), stage.id(), attempt);
            try {
                cfg.safeStop.assertRunning(state.metrics);
                Workspace ws = stage.mutatesWorkspace() ? forkWorktree(stage, st, attempt) : cfg.workspace;
                Agent<?> agent = Agents.forKind(stage.agent());
                Object produced = agent.produce(agentContext(stage, attempt, ws));
                JsonNode output = Json.tree(produced);
                emit("agent.output", Actor.AGENT, Map.of("agent", agentName(stage), "hash", Json.hash(output), "preview", preview(output)), stage.id(), attempt);
                StageResult r = runExitGates(stage, st, output, 0);
                if (r.outcome() != StageOutcome.FAILED) return r;
            } catch (OrchestrationException e) {
                if (e.kind() == OrchestrationException.Kind.STOPPED) {
                    st.status = StageStatus.PENDING;
                    st.lastError = e.getMessage();
                    emit("stage.interrupted", Actor.SYSTEM, Map.of("reason", e.getMessage()), stage.id(), attempt);
                    return new StageResult(StageOutcome.STOPPED, null, null);
                }
                if (!handleAttemptError(stage, st, attempt, e.getMessage(), e.retryable(), e.kind().name().toLowerCase())) return new StageResult(failStage(stage, st, e.getMessage()), null, null);
            } catch (LlmProvider.ProviderException e) {
                if (!handleAttemptError(stage, st, attempt, e.getMessage(), e.retryable(), "provider")) return new StageResult(failStage(stage, st, e.getMessage()), null, null);
            } catch (RuntimeException e) {
                if (!handleAttemptError(stage, st, attempt, String.valueOf(e), false, "unexpected")) return new StageResult(failStage(stage, st, String.valueOf(e)), null, null);
            }
            if (st.attempts - st.attemptBase() < stage.retry().maxAttempts()) {
                long delay = Controls.backoffDelay(stage.retry(), st.attempts);
                synchronized (state.metrics) { state.metrics.retries++; }
                emit("stage.retry-scheduled", Actor.SYSTEM, Map.of("nextAttempt", st.attempts + 1, "backoffMs", delay), stage.id(), null);
                cfg.sleeper.accept(delay);
            }
        }
        return new StageResult(failStage(stage, st, "exhausted " + stage.retry().maxAttempts() + " attempts: " + (st.lastError == null ? "unknown error" : st.lastError)), null, null);
    }

    /** @return true when the attempt may be retried. */
    private boolean handleAttemptError(StageDefinition stage, StageState st, int attempt, String message, boolean retryable, String kind) {
        st.lastError = message;
        Controls.markFirstFailure(st);
        emit("stage.attempt-failed", Actor.SYSTEM, payload("error", message, "retryable", retryable, "kind", kind), stage.id(), attempt);
        rollbackAttempt(stage, st, attempt);
        if (retryable) st.feedback.add("attempt " + attempt + ": " + message);
        return retryable;
    }

    /** Runs exit gates from {@code startIndex}; handles approval checkpoints and persistence of partial progress. */
    private StageResult runExitGates(StageDefinition stage, StageState st, JsonNode output, int startIndex) {
        int attempt = st.attempts;
        Workspace ws = workspaceFor(stage, st);
        for (int gi = startIndex; gi < stage.exitGates().size(); gi++) {
            String gateId = stage.exitGates().get(gi);
            GateResult res = Gates.gate(gateId).evaluate(gateContext(stage, output, ws));
            emit("gate.evaluated", Actor.SYSTEM, payload("gate", gateId, "kind", "exit", "passed", res.passed(), "details", res.details(), "requiresApproval", res.requiresApproval() == null ? null : res.requiresApproval().gateId()), stage.id(), attempt);
            if (res.passed()) continue;

            if (res.requiresApproval() != null) {
                ApprovalSpec spec = res.requiresApproval();
                Outcome decision = resolveApproval(stage, st, spec, output);
                if (decision == null) return pause(stage, st, output, gi, spec);
                if (decision.decision() == Decision.APPROVE) {
                    if (spec.questionsFrom() != null) {
                        // Clarification answered: the answers become an artifact and the stage re-runs with them.
                        Map<String, String> answers = decision.answers() == null ? Map.of() : decision.answers();
                        store.putArtifact(ArtifactKind.CLARIFICATIONS, null, answers, "human", attempt, Map.of(spec.questionsFrom().name(), Json.hash(output)));
                        st.feedback.add("stakeholder answered clarification questions: " + Json.compact(answers));
                        rollbackAttempt(stage, st, attempt);
                        return new StageResult(StageOutcome.FAILED, null, null);
                    }
                    continue; // human accepted the risk; proceed to the next gate
                }
                st.feedback.add("approval '" + spec.gateId() + "' rejected by " + decision.decidedBy() + ": " + (decision.note() == null ? "no reason given" : decision.note()));
                Controls.markFirstFailure(st);
                emit("stage.attempt-failed", Actor.HUMAN, payload("error", "approval rejected: " + decision.note(), "retryable", true), stage.id(), attempt);
                rollbackAttempt(stage, st, attempt);
                return new StageResult(StageOutcome.FAILED, null, null);
            }

            st.lastError = "exit gate '" + gateId + "' failed: " + res.details();
            st.feedback.add("attempt " + attempt + " — " + gateId + ": " + res.details());
            Controls.markFirstFailure(st);
            emit("stage.attempt-failed", Actor.SYSTEM, payload("gate", gateId, "error", res.details(), "retryable", true), stage.id(), attempt);
            rollbackAttempt(stage, st, attempt);
            return new StageResult(StageOutcome.FAILED, null, null);
        }

        // Stage-level approval (design sign-off, release go/no-go) happens after all exit gates.
        if (stage.approval() != null) {
            Outcome decision = resolveApproval(stage, st, stage.approval(), output);
            if (decision == null) return pause(stage, st, output, stage.exitGates().size(), stage.approval());
            if (decision.decision() == Decision.REJECT) {
                st.feedback.add("approval '" + stage.approval().gateId() + "' rejected by " + decision.decidedBy() + ": " + decision.note());
                Controls.markFirstFailure(st);
                emit("stage.attempt-failed", Actor.HUMAN, payload("error", "approval rejected: " + decision.note(), "retryable", true), stage.id(), attempt);
                rollbackAttempt(stage, st, attempt);
                return new StageResult(StageOutcome.FAILED, null, null);
            }
        }

        if (!mergeWorktree(stage, st, output)) return new StageResult(StageOutcome.FAILED, null, null);
        return succeedStage(stage, st, output);
    }

    private StageResult pause(StageDefinition stage, StageState st, JsonNode output, int gateIndex, ApprovalSpec spec) {
        st.pending = new Pending(output, gateIndex, spec.gateId());
        st.status = StageStatus.WAITING_APPROVAL;
        notice("Stage '" + stage.id() + "' is waiting for approval '" + spec.gateId() + "': " + spec.reason());
        emit("stage.waiting-approval", Actor.SYSTEM, Map.of("gate", spec.gateId(), "reason", spec.reason(), "riskLevel", spec.riskLevel().name().toLowerCase()), stage.id(), st.attempts);
        return new StageResult(StageOutcome.WAITING_APPROVAL, null, null);
    }

    private StageResult continueFromPending(StageDefinition stage, StageState st) {
        Pending pending = st.pending;
        ApprovalRecord approval;
        synchronized (state) { approval = state.approvals.get(pending.approvalGateId()); }
        if (approval == null || approval.decision == null) {
            st.status = StageStatus.WAITING_APPROVAL;
            return new StageResult(StageOutcome.WAITING_APPROVAL, null, null);
        }
        st.pending = null;
        st.status = StageStatus.RUNNING;
        emit("stage.resumed", Actor.SYSTEM, Map.of("fromGate", pending.gateIndex() < stage.exitGates().size() ? stage.exitGates().get(pending.gateIndex()) : "stage-approval", "decision", approval.decision.name().toLowerCase()), stage.id(), st.attempts);
        if (pending.approvalGateId().startsWith("escalate:")) {
            approval.consumedAt = Instant.now().toString();
            if (approval.decision == Decision.APPROVE) {
                st.status = StageStatus.SKIPPED;
                emit("stage.skipped", Actor.HUMAN, Map.of("reason", "escalation approved by " + approval.decidedBy + ": " + (approval.note == null ? "" : approval.note)), stage.id(), null);
                return new StageResult(StageOutcome.SKIPPED, null, null);
            }
            return new StageResult(failStage(stage, st, "escalation rejected by " + approval.decidedBy + ": " + approval.note), null, null);
        }
        StageResult r = runExitGates(stage, st, pending.output(), pending.gateIndex());
        if (r.outcome() == StageOutcome.FAILED) {
            // A rejection or a clarification: back to the normal retry loop on the next scheduling pass.
            if (st.attempts - st.attemptBase() < stage.retry().maxAttempts()) {
                st.status = StageStatus.PENDING;
                return new StageResult(StageOutcome.SKIPPED, null, null);
            }
            return new StageResult(failStage(stage, st, "exhausted " + stage.retry().maxAttempts() + " attempts after human decision"), null, null);
        }
        return r;
    }

    /* ---------------------------------------------------------------------- */
    /* Approvals                                                               */
    /* ---------------------------------------------------------------------- */

    private Outcome resolveApproval(StageDefinition stage, StageState st, ApprovalSpec spec, JsonNode output) {
        ApprovalRecord record;
        synchronized (state) {
            ApprovalRecord existing = state.approvals.get(spec.gateId());
            if (existing != null && existing.decision != null && existing.consumedAt == null) {
                existing.consumedAt = Instant.now().toString();
                emit("approval.consumed", Actor.SYSTEM, payload("gate", spec.gateId(), "decision", existing.decision.name().toLowerCase(), "decidedBy", existing.decidedBy), stage.id(), st.attempts);
                return new Outcome(existing.decision, existing.decidedBy == null ? "human" : existing.decidedBy, existing.note, existing.answers);
            }
            record = new ApprovalRecord();
            record.gateId = spec.gateId();
            record.stageId = stage.id();
            record.spec = spec;
            record.requestedAt = Instant.now().toString();
            state.approvals.put(spec.gateId(), record);
        }
        synchronized (state.metrics) { state.metrics.approvalsRequested++; }
        List<Question> questions = spec.questionsFrom() == null ? null : questionsFrom(output);
        emit("approval.requested", Actor.SYSTEM, payload("gate", spec.gateId(), "reason", spec.reason(), "riskLevel", spec.riskLevel().name().toLowerCase(), "questions", questions), stage.id(), st.attempts);
        Outcome decision = cfg.approver.decide(new Request(stage.id(), spec, questions));
        if (decision == null) return null;
        synchronized (state) {
            record.decidedAt = Instant.now().toString();
            record.decision = decision.decision();
            record.decidedBy = decision.decidedBy();
            record.note = decision.note();
            record.answers = decision.answers();
            record.consumedAt = record.decidedAt;
        }
        boolean auto = "auto-approver".equals(decision.decidedBy());
        synchronized (state.metrics) { if (auto) state.metrics.approvalsAuto++; else state.metrics.approvalsHuman++; }
        emit("approval.decided", auto ? Actor.SYSTEM : Actor.HUMAN, payload("gate", spec.gateId(), "decision", decision.decision().name().toLowerCase(), "decidedBy", decision.decidedBy(), "note", decision.note(), "answers", decision.answers()), stage.id(), st.attempts);
        return decision;
    }

    /** Called by the CLI: record a human decision and make the stage schedulable again. */
    public void recordHumanDecision(String gateId, Decision decision, String decidedBy, String note, Map<String, String> answers) {
        ApprovalRecord record;
        synchronized (state) {
            record = state.approvals.get(gateId);
            if (record == null) throw new OrchestrationException("no pending approval '" + gateId + "'", OrchestrationException.Kind.CONFIG, false);
            if (record.decision != null) throw new OrchestrationException("approval '" + gateId + "' already decided (" + record.decision + ")", OrchestrationException.Kind.CONFIG, false);
            record.decidedAt = Instant.now().toString();
            record.decision = decision;
            record.decidedBy = decidedBy;
            record.note = note;
            record.answers = answers;
            state.metrics.approvalsHuman++;
            StageState st = state.stages.get(record.stageId);
            if (st != null && st.status == StageStatus.WAITING_APPROVAL) st.status = StageStatus.READY;
            state.notices.removeIf(n -> n.contains("'" + gateId + "'"));
        }
        emit("approval.decided", Actor.HUMAN, payload("gate", gateId, "decision", decision.name().toLowerCase(), "decidedBy", decidedBy, "note", note, "answers", answers), record.stageId, null);
    }

    private static List<Question> questionsFrom(JsonNode output) {
        RequirementsSpec spec = Artifacts.parse(output, RequirementsSpec.class);
        return spec.ambiguities().stream().filter(Ambiguity::open)
                .map(a -> new Question(a.id(), a.question(), a.options().stream().map(o -> o.key() + ": " + o.description()).toList(), a.recommended())).toList();
    }

    /* ---------------------------------------------------------------------- */
    /* Worktrees, success, failure, re-planning                                */
    /* ---------------------------------------------------------------------- */

    private Workspace forkWorktree(StageDefinition stage, StageState st, int attempt) {
        String rel = "worktrees/" + (stage.id() + "-a" + attempt).replaceAll("[:/]", "_");
        Workspace ws = cfg.workspace.fork(store.dir().resolve(rel));
        st.worktree = rel;
        st.baseHashes = cfg.workspace.allHashes();
        emit("workspace.forked", Actor.SYSTEM, Map.of("worktree", rel), stage.id(), attempt);
        return ws;
    }

    private void discardWorktree(StageDefinition stage, StageState st, String reason, int attempt) {
        if (st.worktree == null) return;
        Workspace.deleteTree(store.dir().resolve(st.worktree));
        synchronized (state.metrics) { state.metrics.rollbacks++; }
        emit("workspace.rollback", Actor.SYSTEM, Map.of("worktree", st.worktree, "reason", Gates.firstLine(reason)), stage.id(), attempt);
        st.worktree = null;
        st.baseHashes = null;
    }

    /** Rolling back an attempt = discarding its worktree. The main sandbox was never touched (merges happen only on success). */
    private void rollbackAttempt(StageDefinition stage, StageState st, int attempt) {
        if (stage.mutatesWorkspace() && st.worktree != null) discardWorktree(stage, st, st.lastError == null ? "attempt failed" : st.lastError, attempt);
    }

    private Workspace workspaceFor(StageDefinition stage, StageState st) {
        return stage.mutatesWorkspace() && st.worktree != null ? new Workspace(store.dir().resolve(st.worktree)) : cfg.workspace;
    }

    /** Merge a verified worktree into the main sandbox; false on conflict (retryable). */
    private boolean mergeWorktree(StageDefinition stage, StageState st, JsonNode output) {
        if (!stage.mutatesWorkspace() || st.worktree == null) return true;
        Workspace.MergeResult result;
        synchronized (cfg.workspace) {
            result = cfg.workspace.merge(Artifacts.parse(output, CodePatch.class), st.baseHashes);
        }
        if (!result.conflicts().isEmpty()) {
            st.lastError = "merge conflict: files changed in the main sandbox since fork: " + String.join(", ", result.conflicts());
            st.feedback.add("attempt " + st.attempts + " — merge: " + st.lastError);
            Controls.markFirstFailure(st);
            emit("stage.attempt-failed", Actor.SYSTEM, payload("error", st.lastError, "retryable", true, "conflicts", result.conflicts()), stage.id(), st.attempts);
            discardWorktree(stage, st, st.lastError, st.attempts);
            return false;
        }
        emit("workspace.merged", Actor.SYSTEM, Map.of("worktree", st.worktree, "merged", result.merged()), stage.id(), st.attempts);
        Workspace.deleteTree(store.dir().resolve(st.worktree));
        st.worktree = null;
        st.baseHashes = null;
        return true;
    }

    private StageResult succeedStage(StageDefinition stage, StageState st, JsonNode output) {
        String taskId = stage.dynamic() == null ? null : stage.dynamic().taskId();
        ArtifactRecord previous = taskId == null ? store.artifact(stage.produces()) : store.taskArtifact(stage.produces(), taskId);
        ArtifactRecord record = store.putArtifact(stage.produces(), taskId, output, stage.id(), st.attempts, st.inputHashes);
        st.status = StageStatus.SUCCEEDED;
        st.finishedAt = Instant.now().toString();
        st.outputHash = record.hash();
        st.pending = null;
        emit("stage.succeeded", Actor.SYSTEM, Map.of("artifact", stage.produces().name(), "version", record.version(), "hash", record.hash(), "attempts", st.attempts, "lineage", st.inputHashes), stage.id(), st.attempts);
        return new StageResult(StageOutcome.SUCCEEDED, output, previous);
    }

    /** Post-success side effects, applied on the scheduler thread after the batch joined. */
    private void afterSuccess(StageDefinition stage, StageResult r) {
        StageState st = state.stages.get(stage.id());
        // Dynamic re-planning: a different artifact than before means downstream work is stale.
        if (r.previous() != null && !r.previous().hash().equals(st.outputHash)) {
            invalidateDownstream(stage.id(), "upstream artifact '" + stage.produces().name() + "' changed (" + r.previous().hash() + " -> " + st.outputHash + ")");
        }
        if (stage.param("expands") != null) expandGraph(stage.param("expands"), Artifacts.parse(r.output(), TaskPlan.class));
        for (Injection inj : cfg.injections) {
            if (inj.afterStage().equals(stage.id()) && injectionsDone.add(inj.afterStage())) applyInjection(inj);
        }
    }

    private StageOutcome failStage(StageDefinition stage, StageState st, String message) {
        st.status = StageStatus.FAILED;
        st.finishedAt = Instant.now().toString();
        st.lastError = message;
        emit("stage.failed", Actor.SYSTEM, Map.of("error", message, "attempts", st.attempts, "onFailure", stage.onFailure().name().toLowerCase().replace('_', '-')), stage.id(), st.attempts);
        return StageOutcome.FAILED;
    }

    /** @return true when the run may continue. */
    private boolean applyFailurePolicy(StageDefinition stage) {
        StageState st = state.stages.get(stage.id());
        switch (stage.onFailure()) {
            case SKIP -> {
                st.status = StageStatus.SKIPPED;
                emit("stage.skipped", Actor.SYSTEM, payload("reason", st.lastError), stage.id(), null);
                return true;
            }
            case ROLLBACK_AND_STOP -> {
                cfg.workspace.restore(store.dir().resolve("snapshots").resolve("initial"));
                synchronized (state.metrics) { state.metrics.rollbacks++; }
                st.status = StageStatus.ROLLED_BACK;
                emit("workspace.rollback", Actor.SYSTEM, Map.of("snapshotId", "initial", "reason", "stage '" + stage.id() + "' failed with rollback-and-stop"), stage.id(), null);
                return false;
            }
            case ESCALATE -> {
                ApprovalSpec spec = ApprovalSpec.of("escalate:" + stage.id(), "stage failed after " + st.attempts + " attempt(s): " + st.lastError + ". Approve to skip this stage and continue; reject to fail the run.", RiskLevel.HIGH);
                Outcome decision = resolveApproval(stage, st, spec, null);
                if (decision == null) {
                    st.status = StageStatus.WAITING_APPROVAL;
                    st.pending = new Pending(null, stage.exitGates().size(), spec.gateId());
                    notice("Stage '" + stage.id() + "' failed and is escalated to a human: '" + spec.gateId() + "'");
                    return true;
                }
                if (decision.decision() == Decision.APPROVE) {
                    st.status = StageStatus.SKIPPED;
                    emit("stage.skipped", Actor.HUMAN, Map.of("reason", "escalation approved by " + decision.decidedBy() + ": " + (decision.note() == null ? "" : decision.note())), stage.id(), null);
                    return true;
                }
                return false;
            }
            default -> { return false; }
        }
    }

    public List<String> invalidateDownstream(String stageId, String reason) {
        List<String> affected = new ArrayList<>(graph.downstream(stageId).stream().filter(id -> {
            StageState s = state.stages.get(id);
            return s != null && s.status != StageStatus.PENDING && s.status != StageStatus.INVALIDATED;
        }).toList());
        if (affected.isEmpty()) return affected;
        synchronized (state.metrics) { state.metrics.replans++; }
        for (String id : affected) {
            StageState s = state.stages.get(id);
            s.status = StageStatus.INVALIDATED;
            s.invalidationReason = reason;
            s.pending = null;
            s.feedback = new ArrayList<>(List.of("re-planned: " + reason));
        }
        // Collapse dynamic expansions whose planner was invalidated (or is the source) so the new plan re-expands the graph.
        for (Map.Entry<String, StageDefinition> e : new LinkedHashMap<>(state.templates).entrySet()) {
            String template = e.getKey();
            StageDefinition planner = graph.all().stream().filter(n -> template.equals(n.param("expands"))).findFirst().orElse(null);
            if (planner == null || !(planner.id().equals(stageId) || affected.contains(planner.id()))) continue;
            List<String> dynamicNodes = graph.dynamicNodesOf(template);
            collapseGraph(template, e.getValue(), dynamicNodes);
            affected.removeAll(dynamicNodes);
        }
        emit("replan.triggered", Actor.SYSTEM, Map.of("source", stageId, "reason", reason, "invalidated", affected), stageId, null);
        return affected;
    }

    private void applyInjection(Injection inj) {
        String before = state.requirement;
        state.requirement = inj.requirement();
        store.putArtifact(ArtifactKind.REQUIREMENT, null, Map.of("text", inj.requirement(), "note", inj.note()), "scenario", 0, Map.of());
        emit("replan.injected", Actor.SCENARIO, Map.of("afterStage", inj.afterStage(), "kind", inj.kind(), "note", inj.note(), "before", before, "after", inj.requirement()), null, null);
        StageDefinition root = graph.all().stream().filter(s -> s.dependsOn().isEmpty()).findFirst().orElseThrow();
        StageState rootState = state.stages.get(root.id());
        rootState.status = StageStatus.INVALIDATED;
        rootState.invalidationReason = "requirement revised: " + inj.note();
        rootState.feedback = new ArrayList<>(List.of("requirement revised by stakeholder: " + inj.note()));
        invalidateDownstream(root.id(), "requirement revised: " + inj.note());
    }

    private void expandGraph(String templateId, TaskPlan plan) {
        if (!graph.has(templateId)) return; // already expanded (resume)
        StageDefinition template = graph.get(templateId);
        state.templates.put(templateId, template);
        List<WorkflowGraph.Task> tasks = plan.tasks().stream().map(t -> new WorkflowGraph.Task(t.id(), t.dependsOn(), t.title(), Map.of("verify", t.verify(), "riskLevel", t.riskLevel()))).toList();
        List<StageDefinition> created = graph.expand(templateId, tasks);
        state.stages.remove(templateId);
        for (StageDefinition node : created) state.stages.put(node.id(), new StageState(node.id()));
        syncGraphState();
        emit("graph.expanded", Actor.SYSTEM, Map.of("template", templateId, "created", created.stream().map(n -> Map.of("id", n.id(), "dependsOn", n.dependsOn(), "verify", String.valueOf(n.param("verify")))).toList()), templateId, null);
    }

    private void collapseGraph(String templateId, StageDefinition template, List<String> dynamicNodes) {
        graph = graph.collapse(templateId, template);
        for (String id : dynamicNodes) state.stages.remove(id);
        state.stages.put(templateId, new StageState(templateId));
        state.templates.remove(templateId);
        syncGraphState();
        emit("graph.collapsed", Actor.SYSTEM, Map.of("template", templateId, "removed", dynamicNodes), templateId, null);
    }

    private void syncGraphState() {
        state.graph = new ArrayList<>(graph.all());
        state.stageOrder = new ArrayList<>(graph.ids());
        synchronized (state.metrics) { state.metrics.stagesTotal = state.graph.size(); }
    }

    /* ---------------------------------------------------------------------- */
    /* Contexts                                                                */
    /* ---------------------------------------------------------------------- */

    private AgentContext agentContext(StageDefinition stage, int attempt, Workspace ws) {
        ArtifactRecord clar = store.artifact(ArtifactKind.CLARIFICATIONS);
        @SuppressWarnings("unchecked")
        Map<String, String> clarifications = clar == null ? null : (Map<String, String>) Json.convert(clar.content(), Map.class);
        StageState st = state.stages.get(stage.id());
        return new AgentContext() {
            @Override public String runId() { return state.runId; }
            @Override public StageDefinition stage() { return stage; }
            @Override public int attempt() { return attempt; }
            @Override public String requirement() { return state.requirement; }
            @Override public ArtifactRecord artifact(ArtifactKind kind) { return store.artifact(kind); }
            @Override public ArtifactRecord taskArtifact(ArtifactKind kind, String taskId) { return store.taskArtifact(kind, taskId); }
            @Override public List<ArtifactRecord> artifactsOfKind(ArtifactKind kind) { return store.artifactsOfKind(kind); }
            @Override public Map<String, String> clarifications() { return clarifications; }
            @Override public List<String> feedback() { return List.copyOf(st.feedback); }
            @Override public Workspace workspace() { return ws; }
            @Override public Toolchain toolchain() { return cfg.toolchain; }
            @Override public LlmProvider llm() { return cfg.llm; }
            @Override public void log(String type, Map<String, Object> payload) { emit(type, Actor.AGENT, payload, stage.id(), attempt); }
            @Override public void recordLlm(LlmProvider.Usage usage, String provider) {
                synchronized (state.metrics) {
                    state.metrics.llmCalls++;
                    state.metrics.llmInputTokens += usage.inputTokens();
                    state.metrics.llmOutputTokens += usage.outputTokens();
                }
                emit("llm.call", Actor.AGENT, Map.of("provider", provider, "inputTokens", usage.inputTokens(), "outputTokens", usage.outputTokens()), stage.id(), attempt);
            }
            @Override public void recordTool() { synchronized (state.metrics) { state.metrics.toolCalls++; } }
        };
    }

    private Gates.Context gateContext(StageDefinition stage, JsonNode output, Workspace ws) {
        StageState st = state.stages.get(stage.id());
        return new Gates.Context() {
            @Override public StageDefinition stage() { return stage; }
            @Override public RunState state() { return state; }
            @Override public JsonNode output() { return output; }
            @Override public Workspace workspace() { return ws; }
            @Override public Toolchain toolchain() { return cfg.toolchain; }
            @Override public PolicyEngine policy() { return cfg.policy; }
            @Override public void log(String type, Map<String, Object> payload) { emit(type, Actor.POLICY, payload, stage.id(), st == null ? null : st.attempts); }
            @Override public void recordTool() { synchronized (state.metrics) { state.metrics.toolCalls++; } }
            @Override public void recordPolicy(int verdicts, int blocks) { synchronized (state.metrics) { state.metrics.policyViolations += verdicts; state.metrics.policyBlocks += blocks; } }
        };
    }

    private void emit(String type, Actor actor, Map<String, Object> payload, String stageId, Integer attempt) {
        RunEvent e = store.emit(type, actor, payload, stageId, attempt);
        if (cfg.onEvent != null) cfg.onEvent.accept(e);
    }

    private void notice(String text) {
        synchronized (state) { state.notices.add(text); }
    }

    private RunState finish(RunStatus status, String note) {
        state.status = status;
        if (note != null) notice(note);
        Controls.finalizeMetrics(state);
        emit("run." + status.name().toLowerCase(), Actor.SYSTEM, payload("note", note, "metrics", Json.tree(state.metrics)), null, null);
        return state;
    }

    private static String agentName(StageDefinition stage) {
        return stage.agent().name().toLowerCase().replace('_', '-');
    }

    private static String preview(JsonNode output) {
        String s = Json.compact(output);
        return s.length() > 240 ? s.substring(0, 240) + "…" : s;
    }

    /** Map builder that tolerates nulls (Map.of does not). */
    static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }
}

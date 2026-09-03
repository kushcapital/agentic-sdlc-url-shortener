package dev.rajeev.orchestrator.report;

import dev.rajeev.orchestrator.agents.Artifacts;
import dev.rajeev.orchestrator.agents.Artifacts.Architecture;
import dev.rajeev.orchestrator.agents.Artifacts.ReleaseChecklist;
import dev.rajeev.orchestrator.agents.Artifacts.RequirementsSpec;
import dev.rajeev.orchestrator.agents.Artifacts.ReviewReport;
import dev.rajeev.orchestrator.agents.Artifacts.TaskPlan;
import dev.rajeev.orchestrator.agents.Artifacts.TestReport;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.RunStore;
import dev.rajeev.orchestrator.core.Types.ApprovalRecord;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.ArtifactRecord;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunMetrics;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.core.WorkflowGraph;
import dev.rajeev.orchestrator.scenario.Scenario;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Human-readable run report: what was decided, by whom, based on what, with the reliability numbers.
 * This is the artifact a reviewer reads first.
 */
public final class MarkdownReport {

    private MarkdownReport() {}

    private static <T> T art(RunStore store, ArtifactKind kind, Class<T> type) {
        ArtifactRecord r = store.artifact(kind);
        if (r == null) return null;
        try {
            return Json.convert(r.content(), type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lower(Enum<?> e) {
        return e == null ? "-" : e.name().toLowerCase().replace('_', '-');
    }

    public static String render(RunStore store, WorkflowGraph graph, Scenario scenario) {
        RunState s = store.state();
        RunMetrics m = s.metrics;
        List<RunEvent> events = store.events();
        RequirementsSpec spec = art(store, ArtifactKind.REQUIREMENTS_SPEC, RequirementsSpec.class);
        Architecture arch = art(store, ArtifactKind.ARCHITECTURE, Architecture.class);
        TaskPlan plan = art(store, ArtifactKind.TASK_PLAN, TaskPlan.class);
        TestReport tests = art(store, ArtifactKind.TEST_REPORT, TestReport.class);
        ReviewReport review = art(store, ArtifactKind.REVIEW_REPORT, ReviewReport.class);
        ReleaseChecklist release = art(store, ArtifactKind.RELEASE_CHECKLIST, ReleaseChecklist.class);

        StringBuilder o = new StringBuilder();
        o.append("# Run report: ").append(scenario.title()).append("\n\n");
        o.append("- **Run id:** `").append(s.runId).append("`\n");
        o.append("- **Scenario:** ").append(scenario.name()).append(" (").append(scenario.kind()).append(")\n");
        o.append("- **Status:** **").append(s.status.name()).append("**\n");
        o.append("- **Started / finished:** ").append(s.createdAt).append(" → ").append(s.updatedAt).append("\n");
        o.append("- **Final requirement:** ").append(s.requirement).append("\n");
        if (!s.notices.isEmpty()) o.append("- **Notices:** ").append(s.notices.stream().map(n -> "_" + n + "_").collect(Collectors.joining("; "))).append("\n");
        o.append("\n## Reliability metrics\n\n| Metric | Value |\n|---|---|\n");
        o.append("| Stages (succeeded / total) | ").append(m.stagesSucceeded).append(" / ").append(m.stagesTotal).append(" |\n");
        o.append("| Attempts / failure-driven retries | ").append(m.attemptsTotal).append(" / ").append(m.retries).append(" |\n");
        o.append("| Attempt success rate | ").append(m.successRate == null ? "n/a" : Math.round(m.successRate * 100) + "%").append(" |\n");
        o.append("| Rollbacks (discarded attempts) | ").append(m.rollbacks).append(" |\n");
        o.append("| Re-plans | ").append(m.replans).append(" |\n");
        o.append("| Approvals requested (human / auto) | ").append(m.approvalsRequested).append(" (").append(m.approvalsHuman).append(" / ").append(m.approvalsAuto).append(") |\n");
        o.append("| Policy findings / blocks | ").append(m.policyViolations).append(" / ").append(m.policyBlocks).append(" |\n");
        o.append("| MTTR (first failure → recovery) | ").append(m.mttrMs == null ? "no failures" : m.mttrMs + " ms").append(" |\n");
        o.append("| End-to-end latency | ").append(m.endToEndMs == null ? "n/a" : m.endToEndMs + " ms").append(" |\n");
        o.append("| LLM calls (in / out tokens) | ").append(m.llmCalls).append(" (").append(m.llmInputTokens).append(" / ").append(m.llmOutputTokens).append(") |\n");
        o.append("| Tool calls | ").append(m.toolCalls).append(" |\n\n");

        o.append("## Workflow graph (final)\n\n```mermaid\n").append(graph.toMermaid(s.stages)).append("```\n\n");

        o.append("## Stage timeline\n\n| Stage | Agent | Status | Attempts | Latency | Lineage (input hashes) | Notes |\n|---|---|---|---|---|---|---|\n");
        for (StageDefinition def : graph.all()) {
            StageState st = s.stages.get(def.id());
            if (st == null) continue;
            Long lat = m.stageLatencyMs.get(def.id());
            String lineage = st.inputHashes.entrySet().stream().map(e -> e.getKey().toLowerCase().replace('_', '-') + "@" + e.getValue().substring(0, Math.min(8, e.getValue().length()))).collect(Collectors.joining(", "));
            StringBuilder notes = new StringBuilder();
            if (st.invalidationReason != null) notes.append("re-planned: ").append(st.invalidationReason);
            if (st.lastError != null) notes.append(notes.length() > 0 ? "; " : "").append("last error: ").append(truncate(firstLine(st.lastError), 120));
            o.append("| ").append(def.id()).append(" | ").append(lower(def.agent())).append(" | ").append(lower(st.status)).append(" | ").append(st.attempts).append(" | ")
                    .append(lat == null ? "-" : lat + " ms").append(" | ").append(lineage.isEmpty() ? "-" : lineage).append(" | ").append(notes.length() == 0 ? "-" : notes).append(" |\n");
        }

        o.append("\n## Human checkpoints\n\n");
        if (s.approvals.isEmpty()) o.append("_No approval checkpoints were reached._\n");
        else {
            o.append("| Gate | Stage | Risk | Decision | By | Note / answers |\n|---|---|---|---|---|---|\n");
            for (ApprovalRecord a : s.approvals.values()) {
                String extra = (a.note == null ? "" : a.note) + (a.answers == null || a.answers.isEmpty() ? "" : " — " + a.answers.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")));
                o.append("| ").append(a.gateId).append(" | ").append(a.stageId).append(" | ").append(lower(a.spec.riskLevel())).append(" | ").append(a.decision == null ? "pending" : lower(a.decision)).append(" | ")
                        .append(a.decidedBy == null ? "-" : a.decidedBy).append(" | ").append(extra.isBlank() ? "-" : extra.replace("|", "\\|")).append(" |\n");
            }
        }

        o.append("\n## Policy guardrails\n\n");
        List<RunEvent> policy = events.stream().filter(e -> e.type().equals("policy.verdict")).toList();
        if (policy.isEmpty()) o.append("_No policy findings._\n");
        else {
            o.append("| Stage | Rule | Verdict | Risk | Message |\n|---|---|---|---|---|\n");
            for (RunEvent e : policy) o.append("| ").append(e.stageId()).append(" | ").append(e.payload().get("rule")).append(" | ").append(e.payload().get("verdict")).append(" | ").append(e.payload().get("riskLevel")).append(" | ").append(truncate(String.valueOf(e.payload().get("message")), 140).replace("|", "\\|")).append(" |\n");
        }

        Set<String> recoveryTypes = Set.of("stage.attempt-failed", "workspace.rollback", "stage.retry-scheduled", "replan.triggered", "replan.injected", "provider.fallback", "graph.collapsed");
        o.append("\n## Failures, retries, rollbacks and re-plans\n\n");
        List<RunEvent> recovery = events.stream().filter(e -> recoveryTypes.contains(e.type())).toList();
        if (recovery.isEmpty()) o.append("_Clean run: no retries, rollbacks or re-plans._\n");
        else for (RunEvent e : recovery) o.append("- `").append(e.ts()).append("` **").append(e.type()).append("** ").append(e.stageId() == null ? "" : "(" + e.stageId() + (e.attempt() == null ? "" : " a" + e.attempt()) + ")").append(": ").append(summarize(e.payload())).append("\n");

        if (spec != null) {
            o.append("\n## Requirement understanding\n\n**Problem statement.** ").append(spec.problemStatement()).append("\n\n**Functional requirements**\n");
            spec.functionalRequirements().forEach(r -> o.append("- ").append(r.id()).append(" (").append(r.priority()).append("): ").append(r.text()).append("\n"));
            o.append("\n**Acceptance criteria**\n");
            spec.acceptanceCriteria().forEach(a -> o.append("- ").append(a.id()).append(": Given ").append(a.given()).append(", when ").append(a.when()).append(", then ").append(a.then()).append("\n"));
            if (!spec.ambiguities().isEmpty()) {
                o.append("\n**Ambiguities identified**\n");
                spec.ambiguities().forEach(a -> o.append("- ").append(a.id()).append(a.blocking() ? " (blocking)" : "").append(": ").append(a.question()).append(" — options: ")
                        .append(a.options().stream().map(op -> op.key()).collect(Collectors.joining(" / "))).append("; recommended **").append(a.recommended()).append("**").append(a.resolution() == null ? "" : "; resolved: " + a.resolution()).append("\n"));
            }
            o.append("\n**Assumptions**\n");
            spec.assumptions().forEach(a -> o.append("- ").append(a).append("\n"));
            o.append("\n**Risks**\n");
            spec.risks().forEach(r -> o.append("- ").append(r.risk()).append(" (L:").append(r.likelihood()).append("/I:").append(r.impact()).append(") → ").append(r.mitigation()).append("\n"));
        }
        if (arch != null) {
            o.append("\n## Architecture & impact analysis\n\n").append(arch.summary()).append("\n\n**Impacted modules**\n");
            arch.impactedModules().forEach(im -> o.append("- `").append(im.path()).append("` (").append(im.changeType()).append("): ").append(im.reason()).append("\n"));
            o.append("\n**Decisions**\n");
            arch.decisions().forEach(d -> o.append("- ").append(d.id()).append(": ").append(d.decision()).append(". _Why:_ ").append(d.rationale()).append(". _Alternatives:_ ").append(String.join("; ", d.alternatives())).append(". _Trade-offs:_ ").append(d.tradeoffs()).append("\n"));
            o.append("\n**Rollback strategy.** ").append(arch.rollbackStrategy()).append("\n");
        }
        if (plan != null) {
            o.append("\n## Task decomposition\n\n").append(plan.summary()).append("\n\n| Task | Depends on | Verify | Risk | Files |\n|---|---|---|---|---|\n");
            plan.tasks().forEach(t -> o.append("| ").append(t.id()).append(": ").append(t.title()).append(" | ").append(t.dependsOn().isEmpty() ? "-" : String.join(", ", t.dependsOn())).append(" | ").append(t.verify()).append(" | ").append(t.riskLevel()).append(" | ").append(String.join(", ", t.files())).append(" |\n"));
            o.append("\n_Sequencing:_ ").append(plan.sequencingRationale()).append("\n");
        }
        if (tests != null) {
            o.append("\n## Verification\n\n- Compile: ").append(tests.typecheck().ok() ? "ok" : "FAILED").append("\n- Tests: ").append(tests.tests().passed()).append("/").append(tests.tests().total()).append(" passed in ").append(tests.tests().durationMs()).append(" ms → **").append(tests.verdict()).append("**\n");
        }
        if (review != null) {
            o.append("\n## Review\n\nVerdict: **").append(review.verdict()).append("** — ").append(review.summary()).append("\n\n");
            review.findings().forEach(f -> o.append("- [").append(f.severity()).append("/").append(f.category()).append("] ").append(f.file() == null ? "" : "`" + f.file() + "`: ").append(f.issue()).append(" → ").append(f.recommendation()).append("\n"));
        }
        if (release != null) {
            o.append("\n## Release readiness\n\nVersion ").append(release.version()).append(" — **").append(release.goNoGo().toUpperCase()).append("** (risk ").append(release.riskAssessment().level()).append(": ").append(release.riskAssessment().rationale()).append(")\n\n");
            release.checklist().forEach(c -> o.append("- [").append(c.status().equals("done") ? "x" : " ").append("] ").append(c.item()).append(c.evidence() == null ? "" : " — " + c.evidence()).append("\n"));
            o.append("\n**Rollback plan**\n");
            for (int i = 0; i < release.rollbackPlan().size(); i++) o.append(i + 1).append(". ").append(release.rollbackPlan().get(i)).append("\n");
        }
        o.append("\n## Audit trail\n\n").append(events.size()).append(" events in `events.jsonl`. Every artifact records the hashes of the artifacts it was derived from (decision lineage); every approval records who decided and why.\n");
        return o.toString();
    }

    static String summarize(Map<String, Object> payload) {
        if (payload == null) return "";
        return List.of("error", "reason", "note", "gate", "worktree", "snapshotId", "invalidated", "from", "to", "nextAttempt", "backoffMs", "removed").stream()
                .filter(payload::containsKey).map(k -> k + "=" + truncate(firstLine(String.valueOf(payload.get(k))), 160)).collect(Collectors.joining(" "));
    }

    static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    static String truncate(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    /** Unused-type guard so the Artifacts import stays meaningful for readers of this file. */
    static Class<?> artifactsType() {
        return Artifacts.class;
    }
}

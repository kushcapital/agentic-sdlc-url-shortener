package dev.rajeev.orchestrator.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.agents.Artifacts;
import dev.rajeev.orchestrator.agents.Artifacts.Ambiguity;
import dev.rajeev.orchestrator.agents.Artifacts.Finding;
import dev.rajeev.orchestrator.agents.Artifacts.ReleaseChecklist;
import dev.rajeev.orchestrator.agents.Artifacts.RequirementsSpec;
import dev.rajeev.orchestrator.agents.Artifacts.ReviewReport;
import dev.rajeev.orchestrator.agents.Artifacts.Task;
import dev.rajeev.orchestrator.agents.Artifacts.TaskPlan;
import dev.rajeev.orchestrator.agents.Artifacts.TestReport;
import dev.rajeev.orchestrator.core.Types.ApprovalSpec;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.core.Types.GateResult;
import dev.rajeev.orchestrator.core.Types.PolicyVerdict;
import dev.rajeev.orchestrator.core.Types.RiskLevel;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.Verdict;
import dev.rajeev.orchestrator.tools.Toolchain;
import dev.rajeev.orchestrator.tools.Workspace;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gate registry. A gate is a small, named, deterministic check. Stages list gate ids; the engine runs
 * entry gates before the agent and exit gates after, in the order written — that order is part of the
 * design (implementer: policy-check → apply-patch → verify-patch).
 *
 * A gate can pass, fail (the stage retries with the details as feedback), or ask for a human decision.
 */
public final class Gates {

    public interface Context {
        StageDefinition stage();
        RunState state();
        JsonNode output();
        Workspace workspace();
        Toolchain toolchain();
        PolicyEngine policy();
        void log(String type, Map<String, Object> payload);
        void recordTool();
        void recordPolicy(int verdicts, int blocks);
    }

    @FunctionalInterface
    public interface Gate {
        GateResult evaluate(Context ctx);
    }

    private static final Map<String, Gate> GATES = new LinkedHashMap<>();

    static {
        GATES.put("upstream-artifacts-present", ctx -> {
            List<String> missing = ctx.stage().consumes().stream().filter(k -> !ctx.state().artifacts.containsKey(k.name())).map(ArtifactKind::name).toList();
            return missing.isEmpty() ? GateResult.pass("upstream-artifacts-present", null) : GateResult.fail("upstream-artifacts-present", "missing artifacts: " + String.join(", ", missing));
        });

        GATES.put("workspace-ready", ctx -> GateResult.pass("workspace-ready", ctx.workspace().list().size() + " files in sandbox"));

        GATES.put("clarifications-resolved", ctx -> {
            RequirementsSpec spec = Artifacts.parse(ctx.output(), RequirementsSpec.class);
            List<Ambiguity> open = spec.ambiguities().stream().filter(Ambiguity::open).toList();
            if (open.isEmpty()) return GateResult.pass("clarifications-resolved", spec.ambiguities().size() + " ambiguities, none blocking");
            String reason = open.stream().map(a -> a.id() + ": " + a.question() + " [options: " + a.options().stream().map(o -> o.key()).collect(Collectors.joining("/")) + "; recommended: " + a.recommended() + "]").collect(Collectors.joining(" | "));
            return GateResult.approval("clarifications-resolved", open.size() + " blocking ambiguit" + (open.size() == 1 ? "y" : "ies") + " need a stakeholder decision",
                    new ApprovalSpec("clarify-requirements", reason, RiskLevel.MEDIUM, ArtifactKind.REQUIREMENTS_SPEC));
        });

        GATES.put("plan-valid", ctx -> {
            TaskPlan plan = Artifacts.parse(ctx.output(), TaskPlan.class);
            Map<String, Task> byId = plan.tasks().stream().collect(Collectors.toMap(Task::id, t -> t));
            Set<String> visiting = new HashSet<>();
            Set<String> done = new HashSet<>();
            for (Task t : plan.tasks()) {
                if (!acyclic(t.id(), byId, visiting, done)) return GateResult.fail("plan-valid", "task graph has a cycle through " + t.id());
            }
            long red = plan.tasks().stream().filter(t -> "tests-red".equals(t.verify())).count();
            return GateResult.pass("plan-valid", plan.tasks().size() + " tasks, " + red + " TDD red task(s)");
        });

        GATES.put("policy-check", ctx -> {
            CodePatch patch = Artifacts.parse(ctx.output(), CodePatch.class);
            PolicyEngine.Outcome result = ctx.policy().evaluate(new PolicyEngine.Context(patch, ctx.workspace(), ctx.stage().id(), ctx.stage().params()));
            ctx.recordPolicy(result.verdicts().size(), (int) result.verdicts().stream().filter(v -> v.verdict() == Verdict.BLOCK).count());
            for (PolicyVerdict v : result.verdicts()) ctx.log("policy.verdict", Map.of("rule", v.rule(), "verdict", v.verdict().name().toLowerCase().replace('_', '-'), "message", v.message(), "riskLevel", v.riskLevel().name().toLowerCase()));
            if (result.outcome() == Verdict.BLOCK) {
                return GateResult.fail("policy-check", "policy blocked the patch: " + result.verdicts().stream().filter(v -> v.verdict() == Verdict.BLOCK).map(v -> "[" + v.rule() + "] " + v.message()).collect(Collectors.joining("; ")));
            }
            if (result.outcome() == Verdict.REQUIRE_APPROVAL) {
                List<PolicyVerdict> reasons = result.verdicts().stream().filter(v -> v.verdict() == Verdict.REQUIRE_APPROVAL).toList();
                return GateResult.approval("policy-check", reasons.stream().map(PolicyVerdict::message).collect(Collectors.joining("; ")),
                        ApprovalSpec.of("policy:" + ctx.stage().id(), reasons.stream().map(v -> "[" + v.rule() + "] " + v.message()).collect(Collectors.joining(" | ")), result.riskLevel()));
            }
            return GateResult.pass("policy-check", ctx.policy().rules().size() + " rules evaluated, no findings");
        });

        GATES.put("apply-patch", ctx -> {
            CodePatch patch = Artifacts.parse(ctx.output(), CodePatch.class);
            ctx.recordTool();
            Workspace.Applied a = ctx.workspace().apply(patch);
            ctx.log("patch.applied", Map.of("written", a.written(), "deleted", a.deleted(), "summary", patch.summary()));
            return GateResult.pass("apply-patch", a.written().size() + " written, " + a.deleted().size() + " deleted");
        });

        GATES.put("verify-patch", ctx -> {
            String mode = ctx.stage().param("verify") == null ? "typecheck" : ctx.stage().param("verify");
            if (mode.equals("none")) return GateResult.pass("verify-patch", "no verification requested");
            ctx.recordTool();
            Toolchain.CommandResult c = ctx.toolchain().compile(ctx.workspace().root());
            ctx.log("tool.compile", Map.of("ok", c.ok(), "durationMs", c.durationMs()));
            if (!c.ok()) return GateResult.fail("verify-patch", "compilation failed:\n" + tail(c.output(), 3000));
            if (mode.equals("typecheck")) return GateResult.pass("verify-patch", "compilation ok");
            ctx.recordTool();
            Toolchain.TestSummary t = ctx.toolchain().test(ctx.workspace().root());
            ctx.log("tool.test", Map.of("ok", t.ok(), "passed", t.passed(), "failed", t.failed(), "durationMs", t.durationMs()));
            String failures = t.failures().stream().map(f -> "- " + f.name() + ": " + firstLine(f.message())).collect(Collectors.joining("\n"));
            return switch (mode) {
                case "tests-green" -> t.ok() ? GateResult.pass("verify-patch", "tests green: " + t.passed() + "/" + t.total())
                        : GateResult.fail("verify-patch", "tests failed (" + t.failed() + "/" + t.total() + "):\n" + head(failures, 3000));
                case "tests-red" -> !t.ok() && t.failed() > 0 ? GateResult.pass("verify-patch", "TDD red confirmed: " + t.failed() + " failing test(s) as expected")
                        : GateResult.fail("verify-patch", "expected newly written tests to fail before implementation, but the suite is green");
                default -> throw new OrchestrationException("unknown verify mode '" + mode + "'", OrchestrationException.Kind.CONFIG, false);
            };
        });

        GATES.put("tests-green", ctx -> {
            TestReport r = Artifacts.parse(ctx.output(), TestReport.class);
            if (r.verdict().equals("green")) return GateResult.pass("tests-green", r.tests().passed() + "/" + r.tests().total() + " passed");
            String why = !r.typecheck().ok() ? "compilation failed: " + head(r.typecheck().output(), 1500) : r.tests().failures().stream().map(f -> f.name() + ": " + firstLine(f.message())).collect(Collectors.joining("; "));
            return GateResult.fail("tests-green", why);
        });

        GATES.put("review-approved", ctx -> {
            ReviewReport r = Artifacts.parse(ctx.output(), ReviewReport.class);
            List<Finding> blocking = r.findings().stream().filter(f -> f.severity().equals("critical") || f.severity().equals("high")).toList();
            if (r.verdict().equals("approve") && blocking.isEmpty()) return GateResult.pass("review-approved", r.findings().size() + " findings, none blocking");
            String why = blocking.isEmpty() ? r.summary() : blocking.stream().map(f -> "[" + f.severity() + "] " + f.issue()).collect(Collectors.joining("; "));
            return GateResult.fail("review-approved", "review requested changes: " + why);
        });

        GATES.put("release-ready", ctx -> {
            ReleaseChecklist c = Artifacts.parse(ctx.output(), ReleaseChecklist.class);
            List<String> pending = c.checklist().stream().filter(i -> i.status().equals("pending")).map(i -> i.item()).toList();
            if (c.goNoGo().equals("go") && pending.isEmpty()) return GateResult.pass("release-ready", c.checklist().size() + " checklist items complete");
            return GateResult.fail("release-ready", c.goNoGo().equals("no-go") ? "release manager returned no-go" : "pending checklist items: " + String.join(", ", pending));
        });
    }

    private Gates() {}

    public static Gate gate(String id) {
        Gate g = GATES.get(id);
        if (g == null) throw new OrchestrationException("unknown gate '" + id + "'", OrchestrationException.Kind.CONFIG, false);
        return g;
    }

    public static Set<String> ids() {
        return GATES.keySet();
    }

    private static boolean acyclic(String id, Map<String, Task> byId, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return true;
        if (!visiting.add(id)) return false;
        for (String d : byId.get(id).dependsOn()) if (!acyclic(d, byId, visiting, done)) return false;
        visiting.remove(id);
        done.add(id);
        return true;
    }

    static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    static String head(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) : s;
    }

    static String tail(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(s.length() - n) : s;
    }
}

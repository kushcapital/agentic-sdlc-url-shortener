package dev.rajeev.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.OrchestrationException;
import dev.rajeev.orchestrator.core.Types.CodePatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Artifact contracts. Every agent output is deserialised into one of these records and then
 * {@link #validate validated} before the engine accepts it — a model (or a fixture) that returns the
 * wrong shape gets a retry with the validation errors as feedback, never a crash.
 */
public final class Artifacts {

    private Artifacts() {}

    public record FunctionalRequirement(String id, String text, String priority) {}
    public record NonFunctionalRequirement(String id, String text, String category) {}
    public record AcceptanceCriterion(String id, String given, String when, String then) {}
    public record AmbiguityOption(String key, String description, String impact) {}
    public record Ambiguity(String id, String question, List<AmbiguityOption> options, String recommended, boolean blocking, String resolution) {
        public boolean open() { return blocking && (resolution == null || resolution.isBlank()); }
    }
    public record Risk(String risk, String likelihood, String impact, String mitigation) {}

    public record RequirementsSpec(
            String problemStatement,
            String scenarioType,
            List<FunctionalRequirement> functionalRequirements,
            List<NonFunctionalRequirement> nonFunctionalRequirements,
            List<AcceptanceCriterion> acceptanceCriteria,
            List<String> assumptions,
            List<String> outOfScope,
            List<Ambiguity> ambiguities,
            List<Risk> risks) {}

    public record Component(String name, String responsibility, String path) {}
    public record ImpactedModule(String path, String changeType, String reason) {}
    public record ApiChange(String method, String path, String change) {}
    public record ArchDecision(String id, String decision, String rationale, List<String> alternatives, String tradeoffs) {}

    public record Architecture(
            String summary,
            List<Component> components,
            List<ImpactedModule> impactedModules,
            List<String> dataFlow,
            List<ApiChange> apiChanges,
            List<String> schemaChanges,
            List<ArchDecision> decisions,
            List<String> securityConsiderations,
            String rollbackStrategy) {}

    public record Task(String id, String title, String description, List<String> files, List<String> dependsOn, String verify, String riskLevel, String acceptance) {}

    public record TaskPlan(String summary, List<Task> tasks, String sequencingRationale) {}

    public record TypecheckResult(boolean ok, String output) {}
    public record TestFailure(String name, String message) {}
    public record TestsResult(boolean ok, int passed, int failed, int total, long durationMs, List<TestFailure> failures) {}

    public record TestReport(String toolchain, TypecheckResult typecheck, TestsResult tests, String verdict) {}

    public record Finding(String severity, String category, String file, String issue, String recommendation) {}

    public record ReviewReport(String verdict, String summary, List<Finding> findings, List<String> policySummary) {}

    public record RiskAssessment(String level, String rationale) {}
    public record ChecklistItem(String item, String status, String evidence) {}

    public record ReleaseChecklist(String version, String changeSummary, List<String> changes, RiskAssessment riskAssessment, List<String> rollbackPlan, List<ChecklistItem> checklist, String goNoGo) {}

    private static final Set<String> VERIFY_MODES = Set.of("none", "typecheck", "tests-red", "tests-green");
    private static final Set<String> RISK = Set.of("low", "medium", "high");

    /** Deserialise + validate; throws a retryable agent error listing every problem found. */
    public static <T> T parse(JsonNode node, Class<T> type) {
        T value = Json.convert(node, type);
        List<String> problems = validate(value);
        if (!problems.isEmpty()) {
            throw new OrchestrationException("invalid " + type.getSimpleName() + ": " + String.join("; ", problems), OrchestrationException.Kind.AGENT, true);
        }
        return value;
    }

    public static List<String> validate(Object value) {
        List<String> p = new ArrayList<>();
        switch (value) {
            case RequirementsSpec s -> {
                req(p, s.problemStatement() != null && s.problemStatement().length() >= 10, "problemStatement is required");
                req(p, s.scenarioType() != null && Set.of("greenfield", "brownfield", "ambiguous").contains(s.scenarioType()), "scenarioType must be greenfield|brownfield|ambiguous");
                req(p, nonEmpty(s.functionalRequirements()), "functionalRequirements must not be empty");
                req(p, nonEmpty(s.acceptanceCriteria()), "acceptanceCriteria must not be empty");
                req(p, s.ambiguities() != null, "ambiguities is required (may be empty)");
                if (s.ambiguities() != null) for (Ambiguity a : s.ambiguities()) req(p, a.options() != null && a.options().size() >= 2, "ambiguity " + a.id() + " needs at least two options");
                req(p, s.risks() != null && s.assumptions() != null && s.outOfScope() != null && s.nonFunctionalRequirements() != null, "risks/assumptions/outOfScope/nonFunctionalRequirements are required");
            }
            case Architecture a -> {
                req(p, a.summary() != null, "summary is required");
                req(p, nonEmpty(a.components()), "components must not be empty");
                req(p, a.impactedModules() != null, "impactedModules is required");
                if (a.impactedModules() != null) for (ImpactedModule m : a.impactedModules()) req(p, Set.of("create", "modify", "delete", "none").contains(m.changeType()), "impactedModules." + m.path() + ".changeType invalid");
                req(p, nonEmpty(a.decisions()), "decisions must not be empty");
                req(p, a.rollbackStrategy() != null, "rollbackStrategy is required");
                req(p, a.dataFlow() != null && a.apiChanges() != null && a.schemaChanges() != null && a.securityConsiderations() != null, "dataFlow/apiChanges/schemaChanges/securityConsiderations are required");
            }
            case TaskPlan t -> {
                req(p, nonEmpty(t.tasks()), "tasks must not be empty");
                if (t.tasks() != null) {
                    Set<String> ids = new java.util.HashSet<>();
                    for (Task task : t.tasks()) {
                        req(p, task.id() != null && task.id().matches("[A-Za-z0-9_-]+"), "task id invalid: " + task.id());
                        req(p, ids.add(task.id()), "duplicate task id " + task.id());
                        req(p, task.verify() != null && VERIFY_MODES.contains(task.verify()), "task " + task.id() + " verify must be none|typecheck|tests-red|tests-green");
                        req(p, task.riskLevel() != null && RISK.contains(task.riskLevel()), "task " + task.id() + " riskLevel invalid");
                        req(p, task.files() != null && task.dependsOn() != null && task.title() != null, "task " + task.id() + " needs title/files/dependsOn");
                    }
                    for (Task task : t.tasks()) if (task.dependsOn() != null) for (String d : task.dependsOn()) req(p, ids.contains(d), "task " + task.id() + " depends on unknown task " + d);
                }
                req(p, t.summary() != null && t.sequencingRationale() != null, "summary and sequencingRationale are required");
            }
            case CodePatch c -> {
                req(p, c.summary() != null && !c.summary().isBlank(), "summary is required");
                req(p, nonEmpty(c.files()), "files must not be empty");
                if (c.files() != null) for (var f : c.files()) {
                    req(p, f.path() != null && !f.path().isBlank(), "file path is required");
                    req(p, Set.of("write", "delete").contains(f.action()), "file " + f.path() + " action must be write|delete");
                    req(p, f.isDelete() || f.content() != null, "file " + f.path() + " needs content");
                }
            }
            case TestReport r -> {
                req(p, r.typecheck() != null && r.tests() != null, "typecheck and tests are required");
                req(p, Set.of("green", "red").contains(r.verdict()), "verdict must be green|red");
            }
            case ReviewReport r -> {
                req(p, Set.of("approve", "request-changes").contains(r.verdict()), "verdict must be approve|request-changes");
                req(p, r.summary() != null && r.findings() != null, "summary and findings are required");
                if (r.findings() != null) for (Finding f : r.findings()) {
                    req(p, Set.of("info", "low", "medium", "high", "critical").contains(f.severity()), "finding severity invalid: " + f.severity());
                    req(p, Set.of("security", "correctness", "maintainability", "performance", "testing", "compliance").contains(f.category()), "finding category invalid: " + f.category());
                }
            }
            case ReleaseChecklist c -> {
                req(p, c.version() != null && nonEmpty(c.changes()) && nonEmpty(c.rollbackPlan()) && nonEmpty(c.checklist()), "version/changes/rollbackPlan/checklist are required");
                req(p, c.riskAssessment() != null && RISK.contains(c.riskAssessment().level()), "riskAssessment.level invalid");
                req(p, Set.of("go", "no-go").contains(c.goNoGo()), "goNoGo must be go|no-go");
                if (c.checklist() != null) for (ChecklistItem i : c.checklist()) req(p, Set.of("done", "pending", "n/a").contains(i.status()), "checklist status invalid: " + i.status());
            }
            default -> { }
        }
        return p;
    }

    private static boolean nonEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }

    private static void req(List<String> problems, boolean ok, String message) {
        if (!ok) problems.add(message);
    }
}

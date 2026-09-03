package dev.rajeev.orchestrator.agents;

import static dev.rajeev.orchestrator.agents.Prompting.ENGINEERING_STANDARDS;
import static dev.rajeev.orchestrator.agents.Prompting.feedbackBlock;
import static dev.rajeev.orchestrator.agents.Prompting.fenced;
import static dev.rajeev.orchestrator.agents.Prompting.json;
import static dev.rajeev.orchestrator.agents.Prompting.repoMap;
import static dev.rajeev.orchestrator.agents.Prompting.structuredCall;

import dev.rajeev.orchestrator.agents.Artifacts.Architecture;
import dev.rajeev.orchestrator.agents.Artifacts.ReleaseChecklist;
import dev.rajeev.orchestrator.agents.Artifacts.RequirementsSpec;
import dev.rajeev.orchestrator.agents.Artifacts.ReviewReport;
import dev.rajeev.orchestrator.agents.Artifacts.Task;
import dev.rajeev.orchestrator.agents.Artifacts.TaskPlan;
import dev.rajeev.orchestrator.agents.Artifacts.TestFailure;
import dev.rajeev.orchestrator.agents.Artifacts.TestReport;
import dev.rajeev.orchestrator.agents.Artifacts.TestsResult;
import dev.rajeev.orchestrator.agents.Artifacts.TypecheckResult;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.Types.AgentKind;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.ArtifactRecord;
import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.tools.Toolchain;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The eight specialists. Seven are model-backed through {@link Prompting#structuredCall}; the tester is
 * tool-driven because test results must be facts, not opinions (ADR-0005).
 */
public final class Agents {

    private Agents() {}

    private static final Map<AgentKind, Agent<?>> REGISTRY = new EnumMap<>(AgentKind.class);

    static {
        for (Agent<?> a : List.of(new RequirementsAnalyst(), new Architect(), new Planner(), new Implementer(), new Tester(), new Reviewer(), new DocWriter(), new ReleaseManager())) {
            REGISTRY.put(a.kind(), a);
        }
    }

    public static Agent<?> forKind(AgentKind kind) {
        return REGISTRY.get(kind);
    }

    /* ------------------------------------------------------------------ */

    public static final class RequirementsAnalyst implements Agent<RequirementsSpec> {
        @Override public AgentKind kind() { return AgentKind.REQUIREMENTS_ANALYST; }
        @Override public Class<RequirementsSpec> outputType() { return RequirementsSpec.class; }

        @Override
        public RequirementsSpec produce(AgentContext ctx) {
            String map = ctx.workspace().list().isEmpty() ? "\n(Empty workspace: greenfield.)\n" : fenced("Repository map (existing code)", repoMap(ctx.workspace(), 120));
            StringBuilder answers = new StringBuilder();
            if (ctx.clarifications() != null && !ctx.clarifications().isEmpty()) {
                answers.append("\n### Stakeholder answers to your earlier questions\n");
                ctx.clarifications().forEach((q, a) -> answers.append("- ").append(q).append(": ").append(a).append('\n'));
                answers.append("Incorporate these answers: mark those ambiguities as resolved (set \"resolution\") and non-blocking.\n");
            }
            String user = "### Raw requirement\n" + ctx.requirement() + "\n" + map + answers + feedbackBlock(ctx) + """
                    Interpret the intent, normalise it into an engineering problem statement, list functional and non-functional requirements,
                    Given/When/Then acceptance criteria, assumptions, out-of-scope items, risks, and ambiguities.
                    For each ambiguity give at least two interpretations with impact, recommend one, and mark it blocking only if choosing wrong would waste implementation work.""";
            return structuredCall(ctx, this, "requirements_spec", ENGINEERING_STANDARDS, user);
        }
    }

    public static final class Architect implements Agent<Architecture> {
        @Override public AgentKind kind() { return AgentKind.ARCHITECT; }
        @Override public Class<Architecture> outputType() { return Architecture.class; }

        @Override
        public Architecture produce(AgentContext ctx) {
            List<String> files = ctx.workspace().list();
            StringBuilder context = new StringBuilder(files.isEmpty() ? "\n(Empty workspace: design from scratch.)\n" : fenced("Repository map", repoMap(ctx.workspace(), 120)));
            int shown = 0;
            for (String f : files) {
                if (shown >= 8) break;
                if (f.matches("src/main/java/.*/(domain|web|repository)/.*\\.java")) {
                    context.append(fenced(f, Prompting.truncate(ctx.workspace().read(f), 4000)));
                    shown++;
                }
            }
            String user = "### Requirements specification\n" + json(ctx.artifactNode(ArtifactKind.REQUIREMENTS_SPEC)) + "\n" + context + feedbackBlock(ctx) + """
                    Produce the architecture: components, impacted modules (with change type and why), data flow, API and schema changes,
                    key decisions with alternatives and trade-offs, security considerations, and a rollback strategy.""";
            return structuredCall(ctx, this, "architecture", ENGINEERING_STANDARDS, user);
        }
    }

    public static final class Planner implements Agent<TaskPlan> {
        @Override public AgentKind kind() { return AgentKind.PLANNER; }
        @Override public Class<TaskPlan> outputType() { return TaskPlan.class; }

        @Override
        public TaskPlan produce(AgentContext ctx) {
            RequirementsSpec spec = ctx.artifactAs(ArtifactKind.REQUIREMENTS_SPEC, RequirementsSpec.class);
            String user = "### Requirements\n" + json(spec == null ? null : spec.functionalRequirements()) + "\n### Acceptance criteria\n" + json(spec == null ? null : spec.acceptanceCriteria())
                    + "\n### Architecture\n" + json(ctx.artifactNode(ArtifactKind.ARCHITECTURE)) + feedbackBlock(ctx) + """
                    Decompose into implementation tasks with explicit dependencies. Tasks without a dependency edge between them will run in parallel.
                    Follow TDD: the first task writes failing tests (verify = "tests-red"); implementation tasks use "typecheck" or "tests-green".
                    Keep each task to a handful of files.""";
            return structuredCall(ctx, this, "task_plan", ENGINEERING_STANDARDS, user);
        }
    }

    public static final class Implementer implements Agent<CodePatch> {
        @Override public AgentKind kind() { return AgentKind.IMPLEMENTER; }
        @Override public Class<CodePatch> outputType() { return CodePatch.class; }

        @Override
        public CodePatch produce(AgentContext ctx) {
            TaskPlan plan = ctx.artifactAs(ArtifactKind.TASK_PLAN, TaskPlan.class);
            String taskId = ctx.stage().dynamic() == null ? null : ctx.stage().dynamic().taskId();
            Task task = plan == null ? null : plan.tasks().stream().filter(t -> t.id().equals(taskId)).findFirst().orElse(null);
            Architecture arch = ctx.artifactAs(ArtifactKind.ARCHITECTURE, Architecture.class);
            RequirementsSpec spec = ctx.artifactAs(ArtifactKind.REQUIREMENTS_SPEC, RequirementsSpec.class);
            StringBuilder files = new StringBuilder();
            if (task != null) for (String f : task.files()) if (ctx.workspace().exists(f)) files.append(fenced("Current " + f, ctx.workspace().read(f)));
            String user = "### Task\n" + json(task) + "\n### Acceptance criteria\n" + json(spec == null ? null : spec.acceptanceCriteria())
                    + "\n### Architecture decisions\n" + json(arch == null ? null : arch.decisions()) + "\n" + fenced("Repository map", repoMap(ctx.workspace(), 120)) + files + feedbackBlock(ctx)
                    + "\nReturn complete file contents for every file you write (no diffs). Include tests. Explain the change in \"summary\".";
            return structuredCall(ctx, this, "code_patch", ENGINEERING_STANDARDS, user);
        }
    }

    /** Tool-driven: no model needed to run a build. Deterministic by design. */
    public static final class Tester implements Agent<TestReport> {
        @Override public AgentKind kind() { return AgentKind.TESTER; }
        @Override public Class<TestReport> outputType() { return TestReport.class; }

        @Override
        public TestReport produce(AgentContext ctx) {
            ctx.recordTool();
            Toolchain.CommandResult compile = ctx.toolchain().compile(ctx.workspace().root());
            ctx.log("tool.compile", Map.of("ok", compile.ok(), "durationMs", compile.durationMs()));
            TestsResult tests;
            if (compile.ok()) {
                ctx.recordTool();
                Toolchain.TestSummary t = ctx.toolchain().test(ctx.workspace().root());
                ctx.log("tool.test", Map.of("ok", t.ok(), "passed", t.passed(), "failed", t.failed(), "durationMs", t.durationMs()));
                tests = new TestsResult(t.ok(), t.passed(), t.failed(), t.total(), t.durationMs(), t.failures().stream().map(f -> new TestFailure(f.name(), f.message())).toList());
            } else {
                tests = new TestsResult(false, 0, 0, 0, 0, List.of(new TestFailure("compile", "compilation failed")));
            }
            return new TestReport(ctx.toolchain().name(), new TypecheckResult(compile.ok(), Prompting.truncate(compile.output().strip(), 4000)), tests, compile.ok() && tests.ok() ? "green" : "red");
        }
    }

    public static final class Reviewer implements Agent<ReviewReport> {
        @Override public AgentKind kind() { return AgentKind.REVIEWER; }
        @Override public Class<ReviewReport> outputType() { return ReviewReport.class; }

        @Override
        public ReviewReport produce(AgentContext ctx) {
            List<Map<String, Object>> patches = new ArrayList<>();
            for (ArtifactRecord r : ctx.artifactsOfKind(ArtifactKind.CODE_PATCH)) {
                CodePatch p = Json.convert(r.content(), CodePatch.class);
                patches.add(Map.of("task", p.taskId() == null ? "" : p.taskId(), "summary", p.summary(),
                        "files", p.files().stream().map(f -> Map.of("path", f.path(), "action", f.action(), "content", Prompting.truncate(f.content(), 3000))).toList()));
            }
            Architecture arch = ctx.artifactAs(ArtifactKind.ARCHITECTURE, Architecture.class);
            String user = "### Architecture security considerations\n" + json(arch == null ? null : arch.securityConsiderations())
                    + "\n### Applied patches\n" + Prompting.truncate(json(patches), 20_000) + "\n" + fenced("Repository map", repoMap(ctx.workspace(), 120)) + feedbackBlock(ctx)
                    + "\nReview for security, correctness, maintainability, performance, testing and compliance. Verdict \"approve\" or \"request-changes\".";
            return structuredCall(ctx, this, "review_report", ENGINEERING_STANDARDS, user);
        }
    }

    public static final class DocWriter implements Agent<CodePatch> {
        @Override public AgentKind kind() { return AgentKind.DOC_WRITER; }
        @Override public Class<CodePatch> outputType() { return CodePatch.class; }

        @Override
        public CodePatch produce(AgentContext ctx) {
            RequirementsSpec spec = ctx.artifactAs(ArtifactKind.REQUIREMENTS_SPEC, RequirementsSpec.class);
            Architecture arch = ctx.artifactAs(ArtifactKind.ARCHITECTURE, Architecture.class);
            StringBuilder existing = new StringBuilder();
            for (String f : List.of("README.md", "CHANGELOG.md")) if (ctx.workspace().exists(f)) existing.append(fenced("Current " + f, Prompting.truncate(ctx.workspace().read(f), 4000)));
            String user = "### Problem statement\n" + (spec == null ? "" : spec.problemStatement()) + "\n### Decisions\n" + json(arch == null ? null : arch.decisions()) + "\n" + existing + feedbackBlock(ctx)
                    + "\nUpdate documentation: CHANGELOG entry, README section for the new behaviour, and an ADR under docs/adr/ for each significant decision.";
            return structuredCall(ctx, this, "doc_update", ENGINEERING_STANDARDS, user);
        }
    }

    public static final class ReleaseManager implements Agent<ReleaseChecklist> {
        @Override public AgentKind kind() { return AgentKind.RELEASE_MANAGER; }
        @Override public Class<ReleaseChecklist> outputType() { return ReleaseChecklist.class; }

        @Override
        public ReleaseChecklist produce(AgentContext ctx) {
            Architecture arch = ctx.artifactAs(ArtifactKind.ARCHITECTURE, Architecture.class);
            String user = "### Test report\n" + json(ctx.artifactNode(ArtifactKind.TEST_REPORT)) + "\n### Review\n" + json(ctx.artifactNode(ArtifactKind.REVIEW_REPORT))
                    + "\n### Rollback strategy from architecture\n" + (arch == null ? "" : arch.rollbackStrategy()) + feedbackBlock(ctx)
                    + "\nProduce the release-readiness checklist with a go/no-go, risk assessment and step-by-step rollback plan. No-go if tests are red or review requested changes.";
            return structuredCall(ctx, this, "release_checklist", ENGINEERING_STANDARDS, user);
        }
    }
}

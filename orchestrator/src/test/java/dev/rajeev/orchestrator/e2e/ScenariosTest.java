package dev.rajeev.orchestrator.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.orchestrator.agents.Artifacts.TestReport;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.Decision;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.RunStatus;
import dev.rajeev.orchestrator.core.Types.StageStatus;
import dev.rajeev.orchestrator.scenario.RunFactory;
import dev.rajeev.orchestrator.scenario.RunFactory.LlmMode;
import dev.rajeev.orchestrator.scenario.RunFactory.Options;
import dev.rajeev.orchestrator.scenario.RunFactory.Prepared;
import dev.rajeev.orchestrator.tools.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * End-to-end: the three bundled scenarios run through the real engine with the scripted provider and
 * the real toolchain, and must reproduce the governance behaviour they were designed to demonstrate.
 *
 * Enabled with {@code -Dorchestrator.scenarios=true} (each scenario compiles and tests a sandbox
 * several times; with Maven as the toolchain a scenario takes minutes). See docs/TESTING_AND_LIMITATIONS.md.
 */
@EnabledIfSystemProperty(named = "orchestrator.scenarios", matches = "true")
class ScenariosTest {

    static Path repo;
    static Path runs;

    @BeforeAll
    static void setUp() throws IOException {
        String root = System.getProperty("orchestrator.repoRoot", System.getenv().getOrDefault("ORCHESTRATOR_REPO_ROOT", "."));
        repo = Path.of(root).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(repo.resolve("scenarios")), "repo root must contain scenarios/: " + repo);
        runs = repo.resolve("runs").resolve("_test-" + ProcessHandle.current().pid());
        Files.createDirectories(runs);
    }

    @AfterAll
    static void tearDown() {
        if (System.getProperty("orchestrator.keepRuns") == null) Workspace.deleteTree(runs);
    }

    static Options opts(String runId, boolean autoApprove) {
        Options o = new Options();
        o.repoRoot = repo;
        o.runsDir = runs;
        o.runId = runId;
        o.llm = LlmMode.SCRIPTED;
        o.autoApprove = autoApprove;
        o.sleeper = ms -> { };
        return o;
    }

    static Prepared run(String scenario, String runId) {
        Prepared p = RunFactory.prepare(repo.resolve("scenarios").resolve(scenario), opts(runId, false));
        p.orchestrator().run();
        RunFactory.writeReports(p);
        return p;
    }

    static List<RunEvent> events(Prepared p) {
        return p.store().events();
    }

    static List<RunEvent> ofType(Prepared p, String type) {
        return events(p).stream().filter(e -> e.type().equals(type)).toList();
    }

    @SuppressWarnings("unchecked")
    static List<String> stages(RunEvent e) {
        return (List<String>) e.payload().get("stages");
    }

    static TestReport testReport(RunState s) {
        return Json.convert(s.artifacts.get(ArtifactKind.TEST_REPORT.name()).content(), TestReport.class);
    }

    @Test
    void greenfieldBuildsATestedV0FromAnEmptySandboxWithADependencyApprovalAndParallelTasks() {
        Prepared p = run("greenfield", "gf");
        RunState s = p.store().state();
        assertEquals(RunStatus.COMPLETED, s.status);
        assertEquals(s.metrics.stagesTotal, s.metrics.stagesSucceeded);
        assertEquals(Decision.APPROVE, s.approvals.get("policy:implement:T1").decision);
        assertEquals("scripted-stakeholder", s.approvals.get("policy:implement:T1").decidedBy);
        List<List<String>> parallel = ofType(p, "scheduler.parallel").stream().map(ScenariosTest::stages).toList();
        assertTrue(parallel.contains(List.of("implement:T3a", "implement:T3b")), parallel.toString());
        assertTrue(parallel.contains(List.of("test", "review", "docs")));
        RunEvent red = events(p).stream().filter(e -> e.type().equals("gate.evaluated") && "implement:T2".equals(e.stageId()) && "verify-patch".equals(e.payload().get("gate"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(red.payload().get("details")).contains("TDD red confirmed"));
        TestReport report = testReport(s);
        assertEquals("green", report.verdict());
        assertEquals(11, report.tests().total());
        assertTrue(Files.exists(p.store().dir().resolve("sandbox/src/main/java/demo/LinkController.java")));
        assertTrue(Files.exists(p.store().dir().resolve("report.html")));
    }

    @Test
    void brownfieldPolicyBlockCompileFailureRollbackProtectedFileApprovalIsolatedWorktreesGreenMerge() {
        Prepared p = run("brownfield", "bf");
        RunState s = p.store().state();
        assertEquals(RunStatus.COMPLETED, s.status);
        assertEquals(1, s.metrics.policyBlocks);
        assertEquals(2, s.metrics.retries);
        assertEquals(2, s.metrics.rollbacks);
        assertEquals(3, s.metrics.approvalsHuman);
        assertNotNull(s.metrics.mttrMs);
        assertEquals(2, s.stages.get("implement:T2").attempts);
        assertEquals(2, s.stages.get("implement:T3").attempts);
        RunEvent block = ofType(p, "policy.verdict").stream().filter(e -> "implement:T2".equals(e.stageId())).findFirst().orElseThrow();
        assertEquals("path-allowlist", block.payload().get("rule"));
        assertEquals("block", block.payload().get("verdict"));
        RunEvent t3fail = ofType(p, "stage.attempt-failed").stream().filter(e -> "implement:T3".equals(e.stageId())).findFirst().orElseThrow();
        assertTrue(String.valueOf(t3fail.payload().get("error")).contains("compilation failed"));
        assertTrue(ofType(p, "scheduler.parallel").stream().map(ScenariosTest::stages).toList().contains(List.of("implement:T3", "implement:T4")));
        assertFalse(ofType(p, "workspace.forked").isEmpty());
        List<String> merged = ofType(p, "workspace.merged").stream().map(RunEvent::stageId).toList();
        assertTrue(merged.containsAll(List.of("implement:T1", "implement:T2", "implement:T3", "implement:T4", "docs")), merged.toString());
        RunEvent retry = events(p).stream().filter(e -> e.type().equals("stage.attempt") && "implement:T3".equals(e.stageId()) && Integer.valueOf(2).equals(e.attempt())).findFirst().orElseThrow();
        assertTrue(String.valueOf(retry.payload().get("feedback")).contains("switch expression does not cover"), "compiler feedback reached the retry");
        TestReport report = testReport(s);
        assertEquals("green", report.verdict());
        assertEquals(106, report.tests().total());
        assertTrue(s.artifacts.get("RELEASE_CHECKLIST").inputHashes().keySet().containsAll(List.of("TEST_REPORT", "REVIEW_REPORT", "ARCHITECTURE")));
    }

    @Test
    void ambiguousClarificationMidRunRevisionReplanRejectedApprovalFeedsBackThenCompletes() {
        Prepared p = run("ambiguous", "am");
        RunState s = p.store().state();
        assertEquals(RunStatus.COMPLETED, s.status);
        assertEquals(Decision.APPROVE, s.approvals.get("clarify-requirements").decision);
        assertEquals(Map.of("AMB-1", "A"), s.approvals.get("clarify-requirements").answers);
        assertEquals("A", s.artifacts.get("CLARIFICATIONS").content().get("AMB-1").asText());
        assertEquals(3, s.stages.get("requirements").attempts);
        assertEquals(2, s.artifacts.get("REQUIREMENTS_SPEC").version()); // attempt 1 (unresolved) is never accepted as an artifact
        assertEquals(2, s.artifacts.get("ARCHITECTURE").version());
        assertEquals(2, s.artifacts.get("TASK_PLAN").version());
        assertTrue(s.metrics.replans >= 1);
        assertEquals(Decision.REJECT, s.approvals.get("policy:implement:T2").decision);
        assertEquals(2, s.stages.get("implement:T2").attempts);
        List<String> types = events(p).stream().map(RunEvent::type).toList();
        assertTrue(types.containsAll(List.of("replan.injected", "replan.triggered", "graph.collapsed", "graph.expanded")));
        assertEquals(2, ofType(p, "graph.expanded").size());
        RunEvent retry = events(p).stream().filter(e -> e.type().equals("stage.attempt") && "implement:T2".equals(e.stageId()) && Integer.valueOf(2).equals(e.attempt())).findFirst().orElseThrow();
        assertTrue(String.valueOf(retry.payload().get("feedback")).contains("rejected"));
        assertTrue(ofType(p, "scheduler.parallel").stream().map(ScenariosTest::stages).toList().contains(List.of("implement:T2", "implement:T3")));
        assertTrue(s.requirement.contains("minimum length of 6"));
        assertEquals(100, testReport(s).tests().total());
    }

    @Test
    void pausesAtACheckpointPersistsAndResumesFromTheSameGateAfterACliStyleDecision() throws IOException {
        // Same brownfield scenario with the scripted decisions removed → the run must pause at design-review.
        Path dir = runs.resolve("scenario-pausing");
        Files.createDirectories(dir);
        var base = Json.parse(Files.readString(repo.resolve("scenarios/brownfield/scenario.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) base).putObject("approvals");
        ((com.fasterxml.jackson.databind.node.ObjectNode) base).put("fixtures", repo.resolve("scenarios/brownfield/fixtures").toString());
        Files.writeString(dir.resolve("scenario.json"), Json.write(base));

        Prepared first = RunFactory.prepare(dir, opts("pause", false));
        RunState paused = first.orchestrator().run();
        assertEquals(RunStatus.PAUSED, paused.status);
        assertEquals(StageStatus.WAITING_APPROVAL, paused.stages.get("architecture").status);
        assertEquals("design-review", paused.stages.get("architecture").pending.approvalGateId());
        assertNull(paused.approvals.get("design-review").decision);
        RunFactory.writeReports(first);

        // A new process: reopen, record the decision, resume. The stage continues from its saved output; the agent is not re-run.
        Prepared second = RunFactory.reopen("pause", opts("pause", false));
        second.orchestrator().recordHumanDecision("design-review", Decision.APPROVE, "rajeev", "LGTM", null);
        assertEquals(StageStatus.READY, second.store().state().stages.get("architecture").status);
        RunState resumed = second.orchestrator().run();
        assertEquals(RunStatus.PAUSED, resumed.status); // next unscripted checkpoint: the protected file in T3
        assertEquals(StageStatus.SUCCEEDED, resumed.stages.get("architecture").status);
        assertEquals(1, resumed.stages.get("architecture").attempts);
        assertEquals("rajeev", resumed.approvals.get("design-review").decidedBy);
        assertNotNull(resumed.approvals.get("design-review").consumedAt);
        assertEquals(StageStatus.WAITING_APPROVAL, resumed.stages.get("implement:T3").status);
        List<String> types = events(second).stream().map(RunEvent::type).toList();
        assertTrue(types.containsAll(List.of("run.paused", "run.resumed", "stage.resumed", "approval.consumed")));

        // Rejecting from the CLI feeds the reason back; with no further scripted patch the failure policy
        // (rollback-and-stop) restores the sandbox to the initial snapshot — never half-applied.
        Prepared third = RunFactory.reopen("pause", opts("pause", false));
        third.orchestrator().recordHumanDecision("policy:implement:T3", Decision.REJECT, "rajeev", "explain the handler change first", null);
        RunState after = third.orchestrator().run();
        assertTrue(String.join("\n", after.stages.get("implement:T3").feedback).contains("explain the handler change first"));
        assertEquals(3, after.stages.get("implement:T3").attempts);
        assertEquals(StageStatus.ROLLED_BACK, after.stages.get("implement:T3").status);
        assertEquals(RunStatus.FAILED, after.status);
        assertFalse(Files.exists(third.store().dir().resolve("sandbox/src/test/java/dev/rajeev/shortener/web/ExpiryIntegrationTest.java")));
        assertTrue(events(third).stream().anyMatch(e -> e.type().equals("workspace.rollback") && "initial".equals(e.payload().get("snapshotId"))));
    }

    @Test
    void safeStopHaltsBeforeAnyStageAndResumesToCompletion() throws IOException {
        Prepared p = RunFactory.prepare(repo.resolve("scenarios/greenfield"), opts("stop", true));
        Files.writeString(p.store().dir().resolve("STOP"), "operator");
        RunState state = p.orchestrator().run();
        assertEquals(RunStatus.STOPPED, state.status);
        assertTrue(String.join(" ", state.notices).contains("STOP file present"));
        assertTrue(state.stages.values().stream().allMatch(s -> s.status == StageStatus.PENDING));
        Files.delete(p.store().dir().resolve("STOP"));
        assertEquals(RunStatus.COMPLETED, RunFactory.reopen("stop", opts("stop", true)).orchestrator().run().status);
    }

    @Test
    void autoApproveModeStampsEveryDecisionAsAutoInTheAuditLog() throws IOException {
        Path dir = runs.resolve("scenario-auto");
        Files.createDirectories(dir);
        var base = Json.parse(Files.readString(repo.resolve("scenarios/greenfield/scenario.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) base).putObject("approvals");
        ((com.fasterxml.jackson.databind.node.ObjectNode) base).put("fixtures", repo.resolve("scenarios/greenfield/fixtures").toString());
        Files.writeString(dir.resolve("scenario.json"), Json.write(base));
        Prepared p = RunFactory.prepare(dir, opts("auto", true));
        RunState s = p.orchestrator().run();
        assertEquals(RunStatus.COMPLETED, s.status);
        assertEquals(3, s.metrics.approvalsAuto);
        assertEquals(0, s.metrics.approvalsHuman);
        assertTrue(s.approvals.values().stream().allMatch(a -> "auto-approver".equals(a.decidedBy)));
    }
}

package dev.rajeev.orchestrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.orchestrator.core.Approvals.Outcome;
import dev.rajeev.orchestrator.core.Approvals.Question;
import dev.rajeev.orchestrator.core.Approvals.Request;
import dev.rajeev.orchestrator.core.Approvals.ScriptedDecision;
import dev.rajeev.orchestrator.core.Types.ApprovalSpec;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.ArtifactRecord;
import dev.rajeev.orchestrator.core.Types.Decision;
import dev.rajeev.orchestrator.core.Types.RetryPolicy;
import dev.rajeev.orchestrator.core.Types.RiskLevel;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunMetrics;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.StageStatus;
import dev.rajeev.orchestrator.workflows.SdlcWorkflow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlsTest {

    @Test
    void backoffGrowsExponentiallyByDefault() {
        RetryPolicy p = RetryPolicy.of(3, 100);
        assertEquals(100, Controls.backoffDelay(p, 1));
        assertEquals(200, Controls.backoffDelay(p, 2));
        assertEquals(400, Controls.backoffDelay(p, 3));
        assertEquals(100, Controls.backoffDelay(new RetryPolicy(3, 100, 1.0), 3));
    }

    @Test
    void safeStopTriggersOnSignalStopFileAndBudgets() throws IOException {
        Path dir = Files.createTempDirectory("stop-");
        Controls.SafeStop c = new Controls.SafeStop(dir.resolve("STOP"), new Controls.Budget(60_000, 5, 5));
        RunMetrics m = new RunMetrics();
        assertNull(c.shouldStop(m));
        m.toolCalls = 6;
        assertTrue(c.shouldStop(m).contains("tool-call budget"));
        m.toolCalls = 0;
        m.llmCalls = 6;
        assertTrue(c.shouldStop(m).contains("LLM-call budget"));
        m.llmCalls = 0;
        Files.writeString(dir.resolve("STOP"), "");
        assertEquals("STOP file present", c.shouldStop(m));
        Controls.SafeStop c2 = new Controls.SafeStop(dir.resolve("nope"), Controls.Budget.DEFAULT);
        c2.requestStop("operator");
        OrchestrationException e = assertThrows(OrchestrationException.class, () -> c2.assertRunning(m));
        assertEquals(OrchestrationException.Kind.STOPPED, e.kind());
    }

    @Test
    void runStoreAppendsEventsSnapshotsStateVersionsArtifactsWithLineageAndReloads() throws IOException {
        Path dir = Files.createTempDirectory("runs-");
        RunStore store = RunStore.create(dir, "r1", "s", "req", SdlcWorkflow.stages());
        store.emit("x", Types.Actor.SYSTEM, Map.of("a", 1), "requirements", null);
        ArtifactRecord a1 = store.putArtifact(ArtifactKind.REQUIREMENTS_SPEC, null, Map.of("v", 1), "requirements", 1, Map.of());
        ArtifactRecord a2 = store.putArtifact(ArtifactKind.REQUIREMENTS_SPEC, null, Map.of("v", 2), "requirements", 2, Map.of("REQUIREMENT", "abc"));
        assertEquals(1, a1.version());
        assertEquals(2, a2.version());
        assertNotEquals(a1.hash(), a2.hash());
        assertEquals(Map.of("REQUIREMENTS_SPEC", a2.hash()), store.currentHashes(List.of(ArtifactKind.REQUIREMENTS_SPEC, ArtifactKind.ARCHITECTURE)));
        List<RunEvent> events = store.events();
        assertEquals(List.of("run.created", "x"), events.stream().map(RunEvent::type).toList());
        assertEquals("requirements", events.get(1).stageId());
        RunStore reloaded = RunStore.load(dir, "r1");
        assertEquals(2, reloaded.artifact(ArtifactKind.REQUIREMENTS_SPEC).version());
        reloaded.emit("y", Types.Actor.SYSTEM, null, null, null);
        assertEquals(List.of(1L, 2L, 3L), reloaded.events().stream().map(RunEvent::seq).toList());
        assertThrows(OrchestrationException.class, () -> RunStore.load(dir, "missing"));
        assertTrue(Files.exists(dir.resolve("r1/artifacts/REQUIREMENTS_SPEC.v2.json")));
    }

    @Test
    void finalizeMetricsDerivesMttrLatenciesAndSuccessRate() throws IOException {
        RunStore store = RunStore.create(Files.createTempDirectory("runs-"), "r2", "s", "req", SdlcWorkflow.stages());
        RunState s = store.state();
        var req = s.stages.get("requirements");
        req.status = StageStatus.SUCCEEDED;
        req.attempts = 2;
        req.startedAt = "2026-01-01T00:00:00Z";
        req.firstFailureAt = "2026-01-01T00:00:01Z";
        req.finishedAt = "2026-01-01T00:00:04Z";
        s.stages.get("architecture").status = StageStatus.FAILED;
        s.metrics.attemptsTotal = 3;
        s.metrics.retries = 1;
        RunMetrics m = Controls.finalizeMetrics(s);
        assertEquals(3000L, m.mttrMs);
        assertEquals(4000L, m.stageLatencyMs.get("requirements"));
        assertEquals(1, m.stagesSucceeded);
        assertEquals(1, m.stagesFailed);
        assertEquals(0.333, m.successRate, 0.001);
    }

    @Test
    void approversPausingAutoAndScripted() {
        Request req = new Request("s", ApprovalSpec.of("g", "r", RiskLevel.HIGH), null);
        assertNull(new Approvals.Pausing().decide(req));
        Outcome auto = new Approvals.Auto().decide(new Request("s", ApprovalSpec.of("g", "r", RiskLevel.HIGH), List.of(new Question("Q", "?", List.of("a", "b"), "b"))));
        assertEquals(Decision.APPROVE, auto.decision());
        assertEquals("auto-approver", auto.decidedBy());
        assertEquals(Map.of("Q", "b"), auto.answers());
        Approvals.Scripted s = new Approvals.Scripted(Map.of(
                "g", List.of(new ScriptedDecision(Decision.REJECT, "first", null, 1)),
                "h", List.of(new ScriptedDecision(Decision.APPROVE, null, null, null))), new Approvals.Pausing());
        assertEquals(Decision.REJECT, s.decide(req).decision());
        assertNull(s.decide(req)); // second request of g → fallback (pausing)
        Request h = new Request("s", ApprovalSpec.of("h", "r", RiskLevel.LOW), null);
        assertEquals(Decision.APPROVE, s.decide(h).decision());
        assertEquals(Decision.APPROVE, s.decide(h).decision()); // single default entry keeps applying
    }
}

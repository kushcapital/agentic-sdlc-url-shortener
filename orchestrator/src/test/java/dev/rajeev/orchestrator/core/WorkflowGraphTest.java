package dev.rajeev.orchestrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.orchestrator.core.Types.AgentKind;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.FailurePolicy;
import dev.rajeev.orchestrator.core.Types.Phase;
import dev.rajeev.orchestrator.core.Types.RetryPolicy;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.core.Types.StageStatus;
import dev.rajeev.orchestrator.workflows.SdlcWorkflow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

    static StageDefinition node(String id, String... deps) {
        return new StageDefinition(id, id, Phase.IMPLEMENTATION, AgentKind.IMPLEMENTER, List.of(deps), List.of(), ArtifactKind.CODE_PATCH, List.of(), List.of(), null, RetryPolicy.of(1, 0), FailurePolicy.FAIL_RUN, false, 1000, null, Map.of());
    }

    static Map<String, StageState> states(Map<String, StageStatus> m) {
        Map<String, StageState> out = new HashMap<>();
        m.forEach((id, status) -> {
            StageState s = new StageState(id);
            s.status = status;
            out.put(id, s);
        });
        return out;
    }

    @Test
    void validatesUnknownDependenciesCyclesAndDuplicates() {
        assertThrows(OrchestrationException.class, () -> new WorkflowGraph(List.of(node("a", "zzz"))));
        assertThrows(OrchestrationException.class, () -> new WorkflowGraph(List.of(node("a", "b"), node("b", "a"))));
        assertThrows(OrchestrationException.class, () -> new WorkflowGraph(List.of(node("a"), node("a"))));
    }

    @Test
    void ordersTopologicallyAndComputesTransitiveDownstream() {
        WorkflowGraph g = new WorkflowGraph(List.of(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c")));
        assertEquals(List.of("a", "b", "c", "d"), g.ids());
        assertEquals(List.of("b", "c", "d"), g.downstream("a"));
        assertEquals(List.of("d"), g.downstream("b"));
        assertTrue(g.downstream("d").isEmpty());
    }

    @Test
    void reportsReadyStages() {
        WorkflowGraph g = new WorkflowGraph(List.of(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c")));
        assertEquals(List.of("a"), g.ready(states(Map.of("a", StageStatus.PENDING, "b", StageStatus.PENDING, "c", StageStatus.PENDING, "d", StageStatus.PENDING))).stream().map(StageDefinition::id).toList());
        assertEquals(List.of("b", "c"), g.ready(states(Map.of("a", StageStatus.SUCCEEDED, "b", StageStatus.PENDING, "c", StageStatus.INVALIDATED, "d", StageStatus.PENDING))).stream().map(StageDefinition::id).toList());
        assertEquals(List.of("d"), g.ready(states(Map.of("a", StageStatus.SUCCEEDED, "b", StageStatus.SKIPPED, "c", StageStatus.SUCCEEDED, "d", StageStatus.PENDING))).stream().map(StageDefinition::id).toList());
        assertTrue(g.ready(states(Map.of("a", StageStatus.SUCCEEDED, "b", StageStatus.FAILED, "c", StageStatus.SUCCEEDED, "d", StageStatus.PENDING))).isEmpty());
    }

    @Test
    void expandsATemplateIntoTaskNodesAndRewiresDependentsThenCollapses() {
        WorkflowGraph g = new WorkflowGraph(List.of(node("plan"), node("impl", "plan"), node("test", "impl")));
        StageDefinition template = g.get("impl");
        List<StageDefinition> created = g.expand("impl", List.of(
                new WorkflowGraph.Task("T1", List.of(), "one", Map.of("verify", "tests-red")),
                new WorkflowGraph.Task("T2", List.of("T1"), "two", Map.of()),
                new WorkflowGraph.Task("T3", List.of("T1"), "three", Map.of())));
        assertEquals(List.of("impl:T1", "impl:T2", "impl:T3"), created.stream().map(StageDefinition::id).toList());
        assertFalse(g.has("impl"));
        assertEquals(List.of("plan", "impl:T1"), g.get("impl:T2").dependsOn());
        assertEquals(List.of("impl:T1", "impl:T2", "impl:T3"), g.get("test").dependsOn());
        assertEquals("T3", g.get("impl:T3").dynamic().taskId());
        assertEquals("tests-red", g.get("impl:T1").param("verify"));
        Map<String, StageState> st = states(Map.of("plan", StageStatus.SUCCEEDED, "impl:T1", StageStatus.SUCCEEDED, "impl:T2", StageStatus.PENDING, "impl:T3", StageStatus.PENDING, "test", StageStatus.PENDING));
        assertEquals(List.of("impl:T2", "impl:T3"), g.ready(st).stream().map(StageDefinition::id).toList()); // parallel path
        WorkflowGraph back = g.collapse("impl", template);
        assertEquals(List.of("plan", "impl", "test"), back.ids());
        assertEquals(List.of("impl"), back.get("test").dependsOn());
    }

    @Test
    void shippedSdlcWorkflowIsAValidDagWithParallelBranchesJoiningAtRelease() {
        WorkflowGraph g = new WorkflowGraph(SdlcWorkflow.stages());
        assertEquals(List.of("requirements", "architecture", "plan", "implement", "test", "review", "docs", "release"), g.ids());
        assertEquals(List.of("test", "review", "docs"), g.get("release").dependsOn());
        assertEquals(7, g.downstream("requirements").size());
        String mermaid = g.toMermaid(null);
        assertTrue(mermaid.contains("requirements --> architecture"));
        assertTrue(mermaid.contains("architecture{{\""));
    }
}

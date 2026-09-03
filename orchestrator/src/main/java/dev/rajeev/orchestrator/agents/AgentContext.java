package dev.rajeev.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.ArtifactRecord;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.llm.LlmProvider;
import dev.rajeev.orchestrator.tools.Toolchain;
import dev.rajeev.orchestrator.tools.Workspace;
import java.util.List;
import java.util.Map;

/**
 * What an agent sees. Note what it does NOT get: the run store, the graph, the approval table.
 * Agents produce artifacts; the engine decides what happens to them.
 */
public interface AgentContext {

    String runId();

    StageDefinition stage();

    int attempt();

    String requirement();

    /** Latest version of an artifact, or null. */
    ArtifactRecord artifact(ArtifactKind kind);

    ArtifactRecord taskArtifact(ArtifactKind kind, String taskId);

    List<ArtifactRecord> artifactsOfKind(ArtifactKind kind);

    /** Human answers to clarification questions, when any exist (else null). */
    Map<String, String> clarifications();

    /** Failure feedback from previous attempts (test output, validation errors, rejection notes). */
    List<String> feedback();

    Workspace workspace();

    Toolchain toolchain();

    LlmProvider llm();

    void log(String type, Map<String, Object> payload);

    void recordLlm(LlmProvider.Usage usage, String provider);

    void recordTool();

    default <T> T artifactAs(ArtifactKind kind, Class<T> type) {
        ArtifactRecord r = artifact(kind);
        return r == null ? null : dev.rajeev.orchestrator.core.Json.convert(r.content(), type);
    }

    default JsonNode artifactNode(ArtifactKind kind) {
        ArtifactRecord r = artifact(kind);
        return r == null ? null : r.content();
    }
}

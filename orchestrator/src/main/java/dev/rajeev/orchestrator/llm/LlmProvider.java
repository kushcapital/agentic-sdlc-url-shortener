package dev.rajeev.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Types.AgentKind;

/**
 * The single seam between the engine and any model. Agents describe what they want (system + user
 * prompt + output schema name); providers return JSON that the agent layer validates. Swapping Claude
 * for a local model, or for recorded fixtures, changes nothing above this line.
 */
public interface LlmProvider {

    record Request(AgentKind agent, String stageId, int attempt, String taskId, String system, String user, String schemaName, JsonNode schema, int maxTokens) {}

    record Usage(long inputTokens, long outputTokens) {}

    record Response(JsonNode output, Usage usage, String provider, String model) {}

    String name();

    /** Cheap availability probe (API key present, fixtures dir exists...). */
    boolean available();

    Response complete(Request request);

    class ProviderException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String provider;
        private final boolean retryable;

        public ProviderException(String message, String provider, boolean retryable) {
            super(message);
            this.provider = provider;
            this.retryable = retryable;
        }

        public String provider() { return provider; }
        public boolean retryable() { return retryable; }
    }
}

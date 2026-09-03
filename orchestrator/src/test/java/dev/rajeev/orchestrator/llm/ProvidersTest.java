package dev.rajeev.orchestrator.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.Types.AgentKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProvidersTest {

    static LlmProvider.Request req(String stageId, int attempt) {
        return new LlmProvider.Request(AgentKind.PLANNER, stageId, attempt, null, "s", "u", "x", Json.parse("{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}}}"), 100);
    }

    @Test
    void scriptedProviderResolvesAttemptSpecificFixturesAndSimulatesFailures() throws IOException {
        Path dir = Files.createTempDirectory("fx-");
        Files.writeString(dir.resolve("plan.json"), "{\"answer\":\"default\"}");
        Files.writeString(dir.resolve("plan.attempt2.json"), "{\"answer\":\"second\"}");
        Files.writeString(dir.resolve("implement_T1.json"), "{\"answer\":\"task\"}");
        Files.writeString(dir.resolve("boom.json"), "{\"__error\":\"outage\",\"__retryable\":true}");
        ScriptedProvider p = new ScriptedProvider(dir);
        assertTrue(p.available());
        assertEquals("default", p.complete(req("plan", 1)).output().get("answer").asText());
        assertEquals("second", p.complete(req("plan", 2)).output().get("answer").asText());
        assertEquals("default", p.complete(req("plan", 3)).output().get("answer").asText());
        assertEquals("task", p.complete(req("implement:T1", 1)).output().get("answer").asText());
        LlmProvider.ProviderException e = assertThrows(LlmProvider.ProviderException.class, () -> p.complete(req("boom", 1)));
        assertTrue(e.retryable());
        assertFalse(assertThrows(LlmProvider.ProviderException.class, () -> p.complete(req("missing", 1))).retryable());
    }

    @Test
    void anthropicProviderForcesAToolCallWithTheSchemaAndExtractsItsInput() {
        List<String> sent = new ArrayList<>();
        AnthropicProvider p = new AnthropicProvider("key", "test-model", body -> {
            sent.add(body);
            return new AnthropicProvider.HttpReply(200, "{\"content\":[{\"type\":\"text\",\"text\":\"…\"},{\"type\":\"tool_use\",\"name\":\"emit_x\",\"input\":{\"answer\":\"from-model\"}}],\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"stop_reason\":\"tool_use\"}");
        });
        assertTrue(p.available());
        LlmProvider.Response r = p.complete(req("plan", 1));
        assertEquals("from-model", r.output().get("answer").asText());
        assertEquals(10, r.usage().inputTokens());
        assertEquals("anthropic", r.provider());
        JsonNode body = Json.parse(sent.get(0));
        assertEquals("tool", body.path("tool_choice").path("type").asText());
        assertEquals("emit_x", body.path("tool_choice").path("name").asText());
        assertTrue(body.path("tools").get(0).path("input_schema").path("properties").has("answer"));
        assertEquals("test-model", body.path("model").asText());
    }

    @Test
    void anthropicProviderClassifiesFailures() {
        assertFalse(new AnthropicProvider(null, "m", null).available());
        assertTrue(assertThrows(LlmProvider.ProviderException.class, () -> new AnthropicProvider("k", "m", b -> new AnthropicProvider.HttpReply(429, "slow down")).complete(req("plan", 1))).retryable());
        assertFalse(assertThrows(LlmProvider.ProviderException.class, () -> new AnthropicProvider("k", "m", b -> new AnthropicProvider.HttpReply(400, "bad")).complete(req("plan", 1))).retryable());
        LlmProvider.ProviderException e = assertThrows(LlmProvider.ProviderException.class, () -> new AnthropicProvider("k", "m", b -> new AnthropicProvider.HttpReply(200, "{\"content\":[{\"type\":\"text\"}],\"stop_reason\":\"end_turn\"}")).complete(req("plan", 1)));
        assertTrue(e.getMessage().contains("did not call"));
    }

    @Test
    void providerChainFallsBackAndReportsIt() throws IOException {
        Path dir = Files.createTempDirectory("fx-");
        Files.writeString(dir.resolve("plan.json"), "{\"answer\":\"scripted\"}");
        LlmProvider broken = new LlmProvider() {
            @Override public String name() { return "broken"; }
            @Override public boolean available() { return true; }
            @Override public Response complete(Request r) { throw new ProviderException("down", "broken", true); }
        };
        LlmProvider absent = new LlmProvider() {
            @Override public String name() { return "absent"; }
            @Override public boolean available() { return false; }
            @Override public Response complete(Request r) { throw new IllegalStateException("never"); }
        };
        List<String> fallbacks = new ArrayList<>();
        ProviderChain chain = new ProviderChain(List.of(absent, broken, new ScriptedProvider(dir)), f -> fallbacks.add(f.from() + "->" + f.to() + ":" + f.reason()));
        assertEquals("absent>broken>scripted", chain.name());
        assertEquals("scripted", chain.complete(req("plan", 1)).provider());
        assertEquals(List.of("broken->scripted:down"), fallbacks);
        assertThrows(LlmProvider.ProviderException.class, () -> new ProviderChain(List.of(absent), null).complete(req("plan", 1)));
    }
}

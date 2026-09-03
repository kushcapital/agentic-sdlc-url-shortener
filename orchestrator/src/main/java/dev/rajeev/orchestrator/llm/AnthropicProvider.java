package dev.rajeev.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rajeev.orchestrator.core.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;

/**
 * Live provider on the Anthropic Messages API (plain {@link HttpClient}, no SDK). Structured output is
 * enforced by forcing a single tool call whose {@code input_schema} is the artifact's JSON schema — the
 * model cannot answer in prose, and the engine still validates the payload before trusting it.
 *
 * The transport is injectable so unit tests can drive this class without a key.
 */
public final class AnthropicProvider implements LlmProvider {

    public record HttpReply(int status, String body) {}

    private final String apiKey;
    private final String model;
    private final Function<String, HttpReply> transport;

    public AnthropicProvider() {
        this(System.getenv("ANTHROPIC_API_KEY"), System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-sonnet-4-5"), null);
    }

    public AnthropicProvider(String apiKey, String model, Function<String, HttpReply> transport) {
        this.apiKey = apiKey;
        this.model = model;
        this.transport = transport != null ? transport : this::post;
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    private HttpReply post(String body) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpReply(res.statusCode(), res.body());
        } catch (IOException e) {
            throw new ProviderException("anthropic request failed: " + e.getMessage(), name(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted", name(), false);
        }
    }

    @Override
    public Response complete(Request req) {
        if (!available()) throw new ProviderException("ANTHROPIC_API_KEY is not set", name(), false);
        String toolName = "emit_" + req.schemaName();
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : 8192);
        body.put("system", req.system());
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", req.user());
        ObjectNode tool = body.putArray("tools").addObject();
        tool.put("name", toolName);
        tool.put("description", "Return the " + req.schemaName() + " as structured data.");
        tool.set("input_schema", req.schema());
        ObjectNode choice = body.putObject("tool_choice");
        choice.put("type", "tool");
        choice.put("name", toolName);

        HttpReply reply = transport.apply(Json.compact(body));
        if (reply.status() != 200) {
            boolean retryable = reply.status() == 429 || reply.status() >= 500;
            throw new ProviderException("anthropic returned HTTP " + reply.status() + ": " + abbreviate(reply.body()), name(), retryable);
        }
        JsonNode res = Json.parse(reply.body());
        JsonNode call = null;
        for (JsonNode c : res.path("content")) {
            if ("tool_use".equals(c.path("type").asText()) && toolName.equals(c.path("name").asText())) call = c;
        }
        if (call == null) throw new ProviderException("model did not call " + toolName + " (stop_reason=" + res.path("stop_reason").asText() + ")", name(), true);
        return new Response(call.get("input"), new Usage(res.path("usage").path("input_tokens").asLong(), res.path("usage").path("output_tokens").asLong()), name(), model);
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}

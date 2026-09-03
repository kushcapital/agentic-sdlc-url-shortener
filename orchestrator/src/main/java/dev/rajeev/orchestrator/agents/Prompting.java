package dev.rajeev.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.OrchestrationException;
import dev.rajeev.orchestrator.llm.LlmProvider;
import dev.rajeev.orchestrator.tools.Workspace;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers: the structured call with schema validation, repository maps, prompt fragments. */
public final class Prompting {

    public static final String ENGINEERING_STANDARDS = """
            You are one specialist in an agentic SDLC pipeline for a Java 21 / Spring Boot URL shortener service (Maven layout).
            Rules you operate under:
            - You produce ONLY the structured artifact requested; the orchestrator decides what happens next.
            - Be concrete: reference real file paths from the repository map when one is provided.
            - Prefer small, reviewable changes. Never touch files outside src/, docs/, README.md, CHANGELOG.md, pom.xml.
            - Never include secrets, credentials or destructive commands.
            - Source changes must come with tests. Security-relevant modules (UrlPolicy, ApiExceptionHandler) require reviewer approval.
            """;

    private static final Map<String, JsonNode> SCHEMAS = new ConcurrentHashMap<>();
    private static final Pattern EXPORTS = Pattern.compile("^\\s*public\\s+(?:final\\s+|abstract\\s+|static\\s+)*(?:class|interface|record|enum)\\s+([A-Za-z0-9_]+)", Pattern.MULTILINE);

    private Prompting() {}

    public static JsonNode schema(String name) {
        return SCHEMAS.computeIfAbsent(name, n -> {
            try (InputStream in = Prompting.class.getResourceAsStream("/schemas/" + n + ".json")) {
                if (in == null) throw new OrchestrationException("missing schema resource " + n, OrchestrationException.Kind.CONFIG, false);
                return Json.parse(new String(in.readAllBytes()));
            } catch (IOException e) {
                throw new OrchestrationException("cannot read schema " + n, OrchestrationException.Kind.CONFIG, false, e);
            }
        });
    }

    /** Call the provider and validate the result; validation failures are retryable agent errors. */
    public static <T> T structuredCall(AgentContext ctx, Agent<T> agent, String schemaName, String system, String user) {
        LlmProvider.Response res = ctx.llm().complete(new LlmProvider.Request(agent.kind(), ctx.stage().id(), ctx.attempt(),
                ctx.stage().dynamic() == null ? null : ctx.stage().dynamic().taskId(), system, user, schemaName, schema(schemaName), 8192));
        ctx.recordLlm(res.usage(), res.provider());
        return Artifacts.parse(res.output(), agent.outputType());
    }

    /** Compact repository map: file list plus the public types each source file declares. */
    public static String repoMap(Workspace ws, int maxFiles) {
        StringBuilder sb = new StringBuilder();
        List<String> files = ws.list().stream().filter(f -> !f.startsWith(".") && !f.endsWith(".class")).limit(maxFiles).toList();
        for (String f : files) {
            sb.append(f);
            if (f.endsWith(".java")) {
                Matcher m = EXPORTS.matcher(ws.read(f));
                List<String> types = new java.util.ArrayList<>();
                while (m.find()) types.add(m.group(1));
                if (!types.isEmpty()) sb.append("  declares: ").append(String.join(", ", types));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String fenced(String label, String body) {
        return "\n### " + label + "\n```\n" + body + "\n```\n";
    }

    public static String feedbackBlock(AgentContext ctx) {
        if (ctx.feedback().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n### Feedback from previous attempts (fix these)\n");
        for (int i = 0; i < ctx.feedback().size(); i++) sb.append(i + 1).append(". ").append(ctx.feedback().get(i)).append('\n');
        return sb.toString();
    }

    public static String json(Object o) {
        return o == null ? "null" : Json.write(o);
    }

    static String truncate(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

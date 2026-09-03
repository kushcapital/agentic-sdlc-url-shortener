package dev.rajeev.orchestrator.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Approvals.ScriptedDecision;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.OrchestrationException;
import dev.rajeev.orchestrator.core.Orchestrator.Injection;
import dev.rajeev.orchestrator.core.Types.Decision;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scenario: the raw requirement, what to seed the sandbox with, the stakeholder's scripted decisions
 * at checkpoints, injected mid-run revisions, policy overrides, and the expectations the e2e tests assert.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scenario(
        String name,
        String title,
        String kind,
        String description,
        String requirement,
        String seedFrom,
        String fixtures,
        String toolchain,
        Map<String, List<ScriptedDecision>> approvals,
        List<Injection> injections,
        Policy policy,
        Map<String, Object> expectations,
        String scenarioDir) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(List<String> protectedPaths, Budget changeBudget) {}

    public record Budget(int maxFiles, int maxLines) {}

    public Scenario {
        if (fixtures == null) fixtures = "fixtures";
        if (toolchain == null) toolchain = "auto";
        if (approvals == null) approvals = Map.of();
        if (injections == null) injections = List.of();
        if (expectations == null) expectations = Map.of();
    }

    public Scenario withScenarioDir(String dir) {
        return new Scenario(name, title, kind, description, requirement, seedFrom, fixtures, toolchain, approvals, injections, policy, expectations, dir);
    }

    /** Reads scenario.json; a decision may be a single object or an array of objects. */
    public static Scenario load(Path scenarioDir) {
        Path file = scenarioDir.resolve("scenario.json");
        if (!Files.exists(file)) throw new OrchestrationException("scenario.json not found in " + scenarioDir, OrchestrationException.Kind.CONFIG, false);
        try {
            return parse(Json.parse(Files.readString(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Scenario parse(JsonNode node) {
        Map<String, List<ScriptedDecision>> approvals = new LinkedHashMap<>();
        JsonNode a = node.path("approvals");
        a.fieldNames().forEachRemaining(gate -> {
            JsonNode v = a.get(gate);
            List<ScriptedDecision> list = new ArrayList<>();
            if (v.isArray()) for (JsonNode d : v) list.add(decision(d));
            else list.add(decision(v));
            approvals.put(gate, list);
        });
        List<Injection> injections = new ArrayList<>();
        for (JsonNode i : node.path("injections")) injections.add(new Injection(i.path("afterStage").asText(), i.path("kind").asText("revise-requirement"), i.path("requirement").asText(), i.path("note").asText()));
        Policy policy = null;
        if (node.hasNonNull("policy")) {
            JsonNode p = node.get("policy");
            List<String> prot = null;
            if (p.hasNonNull("protectedPaths")) {
                prot = new ArrayList<>();
                for (JsonNode s : p.get("protectedPaths")) prot.add(s.asText());
            }
            Budget b = p.hasNonNull("changeBudget") ? new Budget(p.get("changeBudget").path("maxFiles").asInt(20), p.get("changeBudget").path("maxLines").asInt(1500)) : null;
            policy = new Policy(prot, b);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> expectations = node.hasNonNull("expectations") ? (Map<String, Object>) Json.convert(node.get("expectations"), Map.class) : Map.of();
        return new Scenario(node.path("name").asText(), node.path("title").asText(), node.path("kind").asText(), node.path("description").asText(), node.path("requirement").asText(),
                node.hasNonNull("seedFrom") ? node.get("seedFrom").asText() : null, node.path("fixtures").asText("fixtures"), node.path("toolchain").asText("auto"),
                approvals, injections, policy, expectations, node.hasNonNull("scenarioDir") ? node.get("scenarioDir").asText() : null);
    }

    private static ScriptedDecision decision(JsonNode d) {
        Map<String, String> answers = null;
        if (d.hasNonNull("answers")) {
            Map<String, String> collected = new LinkedHashMap<>();
            d.get("answers").fields().forEachRemaining(e -> collected.put(e.getKey(), e.getValue().asText()));
            answers = collected;
        }
        return new ScriptedDecision(Decision.valueOf(d.path("decision").asText("approve").toUpperCase()), d.hasNonNull("note") ? d.get("note").asText() : null, answers, d.hasNonNull("onRequest") ? d.get("onRequest").asInt() : null);
    }
}

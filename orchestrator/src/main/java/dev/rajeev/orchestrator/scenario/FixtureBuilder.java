package dev.rajeev.orchestrator.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.tools.Workspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds {@code fixtures/<stage>.json} from {@code authoring/} for every scenario.
 * <pre>
 *  authoring/&lt;stage&gt;.json                 → copied verbatim (requirements, architecture, plan, review, release)
 *  authoring/&lt;stage&gt;/meta.json + files/** → assembled into a CodePatch fixture (implement_*, docs)
 * </pre>
 * Keeping patches as real files (not JSON strings) is what makes them reviewable and lets the same
 * compiler and test suite run against them.
 */
public final class FixtureBuilder {

    private FixtureBuilder() {}

    public static int build(Path scenariosDir, String only) {
        int scenarios = 0;
        try (Stream<Path> dirs = Files.list(scenariosDir)) {
            for (Path scenario : dirs.filter(Files::isDirectory).sorted().toList()) {
                if (only != null && !scenario.getFileName().toString().equals(only)) continue;
                Path authoring = scenario.resolve("authoring");
                if (!Files.isDirectory(authoring)) continue;
                Path fixtures = scenario.resolve("fixtures");
                Workspace.deleteTree(fixtures);
                Files.createDirectories(fixtures);
                int count = 0;
                try (Stream<Path> entries = Files.list(authoring)) {
                    for (Path entry : entries.sorted().toList()) {
                        String name = entry.getFileName().toString();
                        if (Files.isRegularFile(entry) && name.endsWith(".json")) {
                            Files.copy(entry, fixtures.resolve(name));
                            count++;
                        } else if (Files.isDirectory(entry)) {
                            Files.writeString(fixtures.resolve(name + ".json"), Json.write(patchFixture(entry)));
                            count++;
                        }
                    }
                }
                System.out.println(scenario.getFileName() + ": " + count + " fixtures");
                scenarios++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return scenarios;
    }

    private static JsonNode patchFixture(Path dir) throws IOException {
        JsonNode meta = Json.parse(Files.readString(dir.resolve("meta.json")));
        if (meta.hasNonNull("__error")) {
            ObjectNode err = Json.MAPPER.createObjectNode();
            err.put("__error", meta.get("__error").asText());
            return err;
        }
        ObjectNode patch = Json.MAPPER.createObjectNode();
        patch.put("summary", meta.path("summary").asText());
        if (meta.hasNonNull("taskId")) patch.put("taskId", meta.get("taskId").asText());
        ArrayNode files = patch.putArray("files");
        Path filesDir = dir.resolve("files");
        if (Files.isDirectory(filesDir)) {
            try (Stream<Path> all = Files.walk(filesDir)) {
                for (Path f : all.filter(Files::isRegularFile).sorted().toList()) {
                    ObjectNode entry = files.addObject();
                    entry.put("path", filesDir.relativize(f).toString().replace('\\', '/'));
                    entry.put("action", "write");
                    entry.put("content", Files.readString(f));
                }
            }
        }
        for (JsonNode d : meta.path("delete")) {
            ObjectNode entry = files.addObject();
            entry.put("path", d.asText());
            entry.put("action", "delete");
        }
        ArrayNode notes = patch.putArray("notes");
        for (JsonNode n : meta.path("notes")) notes.add(n.asText());
        return patch;
    }

    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : "scenarios");
        List<String> rest = args.length > 1 ? List.of(args).subList(1, args.length) : List.of();
        build(dir, rest.isEmpty() ? null : rest.get(0));
    }
}

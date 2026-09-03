package dev.rajeev.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deterministic provider that replays authored agent outputs from a fixtures directory. It is what makes
 * every scenario reproducible offline and what the e2e suite runs against.
 * <pre>
 *   implement_T2.attempt2.json  -> attempt-specific (used to script a failing first try)
 *   implement_T2.json           -> default
 * </pre>
 * A fixture of the form {"__error": "..."} simulates a provider failure so retry / fallback paths can be
 * exercised on purpose.
 */
public final class ScriptedProvider implements LlmProvider {

    private final Path fixturesDir;

    public ScriptedProvider(Path fixturesDir) {
        this.fixturesDir = fixturesDir;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public boolean available() {
        return Files.isDirectory(fixturesDir);
    }

    public Path fixturePath(Request req) {
        String base = req.stageId().replaceAll("[^A-Za-z0-9_-]", "_");
        Path specific = fixturesDir.resolve(base + ".attempt" + req.attempt() + ".json");
        if (Files.exists(specific)) return specific;
        Path generic = fixturesDir.resolve(base + ".json");
        return Files.exists(generic) ? generic : null;
    }

    @Override
    public Response complete(Request req) {
        Path path = fixturePath(req);
        if (path == null) throw new ProviderException("no fixture for stage '" + req.stageId() + "' (attempt " + req.attempt() + ") in " + fixturesDir, name(), false);
        JsonNode raw;
        try {
            raw = Json.parse(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (raw.hasNonNull("__error")) {
            throw new ProviderException("scripted failure: " + raw.get("__error").asText(), name(), !raw.has("__retryable") || raw.get("__retryable").asBoolean());
        }
        int chars = Json.compact(raw).length();
        return new Response(raw, new Usage(req.user().length() / 4, chars / 4), name(), "fixtures");
    }
}

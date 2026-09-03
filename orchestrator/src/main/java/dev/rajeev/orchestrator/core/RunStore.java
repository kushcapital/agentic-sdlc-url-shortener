package dev.rajeev.orchestrator.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rajeev.orchestrator.core.Types.Actor;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.ArtifactRecord;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.StageState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-sourced run persistence.
 * <pre>
 *  runs/&lt;runId&gt;/events.jsonl   append-only audit log (never rewritten)
 *  runs/&lt;runId&gt;/state.json     snapshot for fast resume
 *  runs/&lt;runId&gt;/artifacts/     one JSON file per artifact version
 * </pre>
 * Every state mutation goes through {@link #emit}, which appends the event first and then snapshots.
 * If the process dies between the two, resume replays from the snapshot and the log still holds the
 * truth — the audit trail is never behind the state, only ever ahead of it. Methods are synchronized
 * because parallel stages share one store.
 */
public final class RunStore {

    private final Path dir;
    private final RunState state;
    private long seq;

    public RunStore(Path rootDir, RunState state) {
        this.dir = rootDir.resolve(state.runId);
        this.state = state;
        try {
            Files.createDirectories(dir.resolve("artifacts"));
            Path events = eventsPath();
            if (Files.exists(events)) {
                try (var lines = Files.lines(events)) {
                    seq = lines.filter(l -> !l.isBlank()).count();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static RunStore create(Path rootDir, String runId, String scenario, String requirement, List<StageDefinition> graph) {
        RunState s = new RunState();
        s.runId = runId;
        s.scenario = scenario;
        s.requirement = requirement;
        s.createdAt = Instant.now().toString();
        s.updatedAt = s.createdAt;
        s.graph = new ArrayList<>(graph);
        for (StageDefinition d : graph) {
            s.stages.put(d.id(), new StageState(d.id()));
            s.stageOrder.add(d.id());
        }
        s.metrics.stagesTotal = graph.size();
        RunStore store = new RunStore(rootDir, s);
        store.emit("run.created", Actor.SYSTEM, Map.of("scenario", scenario, "requirement", requirement), null, null);
        return store;
    }

    public static RunStore load(Path rootDir, String runId) {
        Path path = rootDir.resolve(runId).resolve("state.json");
        if (!Files.exists(path)) throw new OrchestrationException("run '" + runId + "' not found at " + path, OrchestrationException.Kind.CONFIG, false);
        try {
            return new RunStore(rootDir, Json.read(Files.readString(path), RunState.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RunState state() { return state; }

    public Path dir() { return dir; }

    public Path eventsPath() { return dir.resolve("events.jsonl"); }

    public synchronized RunEvent emit(String type, Actor actor, Map<String, Object> payload, String stageId, Integer attempt) {
        RunEvent event = new RunEvent(++seq, Instant.now().toString(), state.runId, type, actor, stageId, attempt, payload == null ? null : new LinkedHashMap<>(payload));
        try {
            Files.writeString(eventsPath(), Json.compact(event) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        state.updatedAt = event.ts();
        snapshot();
        return event;
    }

    public synchronized List<RunEvent> events() {
        if (!Files.exists(eventsPath())) return List.of();
        try (var lines = Files.lines(eventsPath())) {
            return lines.filter(l -> !l.isBlank()).map(l -> Json.read(l, RunEvent.class)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void snapshot() {
        try {
            Files.writeString(dir.resolve("state.json"), Json.write(state));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Store an artifact version under {@code kind} (or {@code kind:taskId} for dynamic nodes); returns the record with its hash. */
    public synchronized ArtifactRecord putArtifact(ArtifactKind kind, String taskId, Object content, String producedBy, int attempt, Map<String, String> inputHashes) {
        String key = taskId == null ? kind.name() : kind.name() + ":" + taskId;
        ArtifactRecord previous = state.artifacts.get(key);
        JsonNode node = content instanceof JsonNode n ? n : Json.tree(content);
        ArtifactRecord record = new ArtifactRecord(kind, producedBy, attempt, (previous == null ? 0 : previous.version()) + 1, Json.hash(node), new LinkedHashMap<>(inputHashes), Instant.now().toString(), node);
        state.artifacts.put(key, record);
        try {
            String base = key.replaceAll("[:/]", "_");
            Files.writeString(dir.resolve("artifacts").resolve(base + ".v" + record.version() + ".json"), Json.write(record));
            Files.writeString(dir.resolve("artifacts").resolve(base + ".json"), Json.write(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        snapshot();
        return record;
    }

    public synchronized ArtifactRecord artifact(ArtifactKind kind) {
        return state.artifacts.get(kind.name());
    }

    public synchronized ArtifactRecord taskArtifact(ArtifactKind kind, String taskId) {
        return state.artifacts.get(kind.name() + ":" + taskId);
    }

    public synchronized List<ArtifactRecord> artifactsOfKind(ArtifactKind kind) {
        return state.artifacts.values().stream().filter(a -> a.kind() == kind).sorted((a, b) -> a.createdAt().compareTo(b.createdAt())).toList();
    }

    public synchronized Map<String, String> currentHashes(List<ArtifactKind> kinds) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ArtifactKind k : kinds) {
            ArtifactRecord a = state.artifacts.get(k.name());
            if (a != null) out.put(k.name(), a.hash());
        }
        return out;
    }
}

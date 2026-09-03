package dev.rajeev.orchestrator.cli;

import dev.rajeev.orchestrator.core.Controls;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.RunStore;
import dev.rajeev.orchestrator.core.Types.Actor;
import dev.rajeev.orchestrator.core.Types.ApprovalRecord;
import dev.rajeev.orchestrator.core.Types.Decision;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.RunStatus;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.scenario.FixtureBuilder;
import dev.rajeev.orchestrator.scenario.RunFactory;
import dev.rajeev.orchestrator.scenario.RunFactory.LlmMode;
import dev.rajeev.orchestrator.scenario.RunFactory.Options;
import dev.rajeev.orchestrator.scenario.RunFactory.Prepared;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The human side of "controlled autonomy".
 * <pre>
 *   run --scenario &lt;name&gt; [--auto-approve] [--llm auto|anthropic|scripted] [--run-id id] [--toolchain maven|javac] [--quiet]
 *   resume &lt;runId&gt;                         continue a paused/stopped run
 *   approve &lt;runId&gt; &lt;gateId&gt; [--decision approve|reject] [--note ..] [--answer q=a ..]
 *   status &lt;runId&gt;                         where is the run, what is it waiting for
 *   stop &lt;runId&gt;                           safe-stop a run from another shell
 *   rollback &lt;runId&gt;                       restore the sandbox to the initial snapshot
 *   report &lt;runId&gt;                         regenerate report.md / report.html
 *   fixtures [scenario]                    rebuild scenarios/&lt;name&gt;/fixtures from authoring/
 *   list
 * </pre>
 */
public final class OrchestratorCli {

    private static final Pattern INTERESTING = Pattern.compile("^(stage\\.(started|succeeded|failed|attempt-failed|retry-scheduled|waiting-approval|skipped|resumed)|approval\\.|policy\\.verdict|workspace\\.rollback|replan\\.|graph\\.|scheduler\\.|provider\\.fallback|run\\.)");

    private final Path repoRoot;
    private final Path runsDir;
    private final Path scenariosDir;
    private final PrintStream out;

    public OrchestratorCli(Path repoRoot, Path runsDir, PrintStream out) {
        this.repoRoot = repoRoot;
        this.runsDir = runsDir;
        this.scenariosDir = repoRoot.resolve("scenarios");
        this.out = out;
    }

    record Args(List<String> positional, Map<String, List<String>> flags) {
        String flag(String name) {
            List<String> v = flags.get(name);
            return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
        }

        boolean has(String name) {
            return flags.containsKey(name);
        }
    }

    static Args parse(String[] argv) {
        List<String> positional = new ArrayList<>();
        Map<String, List<String>> flags = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = "true";
                if (key.contains("=")) {
                    value = key.substring(key.indexOf('=') + 1);
                    key = key.substring(0, key.indexOf('='));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    value = argv[++i];
                }
                flags.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            } else {
                positional.add(a);
            }
        }
        return new Args(positional, flags);
    }

    public int execute(String[] argv) {
        Args args = parse(argv);
        String cmd = args.positional().isEmpty() ? "" : args.positional().get(0);
        return switch (cmd) {
            case "run" -> run(args);
            case "resume" -> resume(args);
            case "approve" -> approve(args);
            case "status" -> status(args);
            case "stop" -> stop(args);
            case "rollback" -> rollback(args);
            case "report" -> report(args);
            case "fixtures" -> { FixtureBuilder.build(scenariosDir, args.positional().size() > 1 ? args.positional().get(1) : null); yield 0; }
            case "list" -> list();
            default -> { usage(); yield cmd.isEmpty() ? 0 : 1; }
        };
    }

    private Options options(Args args) {
        Options o = new Options();
        o.repoRoot = repoRoot;
        o.runsDir = runsDir;
        o.llm = LlmMode.valueOf((args.flag("llm") == null ? "auto" : args.flag("llm")).toUpperCase());
        o.autoApprove = args.has("auto-approve");
        o.toolchain = args.flag("toolchain");
        if (args.flag("concurrency") != null) o.concurrency = Integer.parseInt(args.flag("concurrency"));
        if (!args.has("quiet")) o.onEvent = this::printEvent;
        return o;
    }

    private Path scenarioDir(String name) {
        Path direct = Path.of(name);
        Path dir = Files.exists(direct.resolve("scenario.json")) ? direct : scenariosDir.resolve(name);
        if (!Files.exists(dir.resolve("scenario.json"))) throw new IllegalArgumentException("scenario '" + name + "' not found (looked in " + dir + ")");
        return dir;
    }

    private void printEvent(RunEvent e) {
        if (!INTERESTING.matcher(e.type()).find()) return;
        StringBuilder bits = new StringBuilder();
        if (e.payload() != null) {
            for (String k : List.of("agent", "gate", "decision", "decidedBy", "rule", "verdict", "reason", "error", "note", "invalidated", "created", "stages", "outcomes", "from", "to")) {
                Object v = e.payload().get(k);
                if (v == null) continue;
                String s = v instanceof String str ? str.lines().findFirst().orElse("") : Json.compact(v);
                bits.append(k).append('=').append(s.length() > 110 ? s.substring(0, 110) : s).append("  ");
            }
        }
        out.printf("  %-24s %-22s %s%n", e.type(), e.stageId() == null ? "" : e.stageId(), bits.toString().strip());
    }

    private void summarize(RunState s) {
        out.println();
        out.println("Run " + s.runId + " → " + s.status.name());
        for (String n : s.notices) out.println("  • " + n);
        out.println("  artifacts: " + runsDir.resolve(s.runId));
        if (s.status == RunStatus.PAUSED) {
            out.println("  next: orchestrate approve " + s.runId + " <gateId> --decision approve|reject [--note \"...\"] [--answer id=option]");
            out.println("        orchestrate resume " + s.runId);
        }
    }

    private int run(Args args) {
        String name = args.flag("scenario");
        if (name == null) throw new IllegalArgumentException("--scenario <name> is required");
        Options opts = options(args);
        opts.runId = args.flag("run-id");
        Prepared p = RunFactory.prepare(scenarioDir(name), opts);
        out.println("Scenario: " + p.scenario().title());
        out.println("Run id:   " + p.store().state().runId);
        out.println("LLM:      " + p.orchestrator().llmName() + "  approver: " + (opts.autoApprove ? "auto (demo)" : "pausing (human via CLI)") + "  toolchain: " + p.orchestrator().toolchainName());
        out.println();
        RunState state = p.orchestrator().run();
        RunFactory.writeReports(p);
        summarize(state);
        return state.status == RunStatus.COMPLETED || state.status == RunStatus.PAUSED ? 0 : 1;
    }

    private int resume(Args args) {
        Prepared p = RunFactory.reopen(positional(args, 1, "resume <runId>"), options(args));
        RunState state = p.orchestrator().run();
        RunFactory.writeReports(p);
        summarize(state);
        return state.status == RunStatus.COMPLETED || state.status == RunStatus.PAUSED ? 0 : 1;
    }

    private int approve(Args args) {
        String runId = positional(args, 1, "approve <runId> <gateId>");
        String gateId = positional(args, 2, "approve <runId> <gateId>");
        Options opts = options(args);
        opts.onEvent = null;
        Prepared p = RunFactory.reopen(runId, opts);
        Decision decision = Decision.valueOf((args.flag("decision") == null ? "approve" : args.flag("decision")).toUpperCase());
        Map<String, String> answers = new LinkedHashMap<>();
        for (String kv : args.flags().getOrDefault("answer", List.of())) {
            int i = kv.indexOf('=');
            if (i > 0) answers.put(kv.substring(0, i), kv.substring(i + 1));
        }
        String by = System.getenv().getOrDefault("USER", "human");
        p.orchestrator().recordHumanDecision(gateId, decision, by, args.flag("note"), answers.isEmpty() ? null : answers);
        p.store().snapshot();
        out.println("Recorded " + decision.name().toLowerCase() + " for '" + gateId + "' on run " + runId + ". Resume with: orchestrate resume " + runId);
        return 0;
    }

    private int status(Args args) {
        RunState s = RunStore.load(runsDir, positional(args, 1, "status <runId>")).state();
        out.println("Run " + s.runId + " (" + s.scenario + ") — " + s.status.name());
        for (String id : s.stageOrder) {
            StageState st = s.stages.get(id);
            if (st != null) out.printf("  %-24s %-18s attempts=%d%s%n", id, st.status.name().toLowerCase().replace('_', '-'), st.attempts, st.lastError == null ? "" : "  " + st.lastError.lines().findFirst().orElse(""));
        }
        List<ApprovalRecord> pending = s.approvals.values().stream().filter(a -> a.decision == null).toList();
        if (!pending.isEmpty()) {
            out.println("Pending approvals:");
            for (ApprovalRecord a : pending) out.println("  " + a.gateId + " (" + a.spec.riskLevel().name().toLowerCase() + "): " + a.spec.reason());
        }
        for (String n : s.notices) out.println("  • " + n);
        return 0;
    }

    private int stop(Args args) {
        try {
            Files.writeString(runsDir.resolve(positional(args, 1, "stop <runId>")).resolve("STOP"), Instant.now().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.println("STOP file written; the run will halt at its next checkpoint.");
        return 0;
    }

    private int rollback(Args args) {
        Options opts = options(args);
        opts.onEvent = null;
        Prepared p = RunFactory.reopen(positional(args, 1, "rollback <runId>"), opts);
        p.workspace().restore(p.store().dir().resolve("snapshots").resolve("initial"));
        RunState s = p.store().state();
        s.status = RunStatus.STOPPED;
        s.metrics.rollbacks++;
        s.notices.add("manual rollback to initial snapshot");
        Controls.finalizeMetrics(s);
        p.store().emit("workspace.rollback", Actor.HUMAN, Map.of("snapshotId", "initial", "reason", "manual rollback via CLI"), null, null);
        RunFactory.writeReports(p);
        out.println("Sandbox restored to the initial snapshot; run marked stopped.");
        return 0;
    }

    private int report(Args args) {
        Options opts = options(args);
        opts.onEvent = null;
        Prepared p = RunFactory.reopen(positional(args, 1, "report <runId>"), opts);
        out.println(RunFactory.writeReports(p).markdown());
        return 0;
    }

    private int list() {
        if (!Files.isDirectory(runsDir)) return 0;
        try (Stream<Path> dirs = Files.list(runsDir)) {
            for (Path d : dirs.sorted().toList()) {
                Path state = d.resolve("state.json");
                if (!Files.exists(state)) continue;
                RunState s = Json.read(Files.readString(state), RunState.class);
                out.printf("%-40s %-12s %-10s %s%n", s.runId, s.scenario, s.status.name().toLowerCase(), s.updatedAt);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return 0;
    }

    private static String positional(Args args, int index, String usage) {
        if (args.positional().size() <= index) throw new IllegalArgumentException(usage);
        return args.positional().get(index);
    }

    private void usage() {
        out.println("""
                usage: orchestrate <run|resume|approve|status|stop|rollback|report|fixtures|list> ...

                  run --scenario greenfield|brownfield|ambiguous [--auto-approve] [--llm auto|anthropic|scripted] [--run-id id] [--toolchain maven|javac] [--quiet]
                  resume <runId>
                  approve <runId> <gateId> [--decision approve|reject] [--note "..."] [--answer id=option]
                  status <runId>
                  stop <runId>
                  rollback <runId>
                  report <runId>
                  fixtures [scenario]
                  list""");
    }
}

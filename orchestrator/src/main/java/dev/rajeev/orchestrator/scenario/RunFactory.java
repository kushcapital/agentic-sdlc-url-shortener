package dev.rajeev.orchestrator.scenario;

import dev.rajeev.orchestrator.core.Approvals;
import dev.rajeev.orchestrator.core.Approvals.Approver;
import dev.rajeev.orchestrator.core.Controls;
import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.Orchestrator;
import dev.rajeev.orchestrator.core.PolicyEngine;
import dev.rajeev.orchestrator.core.RunStore;
import dev.rajeev.orchestrator.core.Types.Actor;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.llm.AnthropicProvider;
import dev.rajeev.orchestrator.llm.LlmProvider;
import dev.rajeev.orchestrator.llm.ProviderChain;
import dev.rajeev.orchestrator.llm.ScriptedProvider;
import dev.rajeev.orchestrator.report.HtmlReport;
import dev.rajeev.orchestrator.report.MarkdownReport;
import dev.rajeev.orchestrator.tools.CommandRunner;
import dev.rajeev.orchestrator.tools.JavacToolchain;
import dev.rajeev.orchestrator.tools.MavenToolchain;
import dev.rajeev.orchestrator.tools.Toolchain;
import dev.rajeev.orchestrator.tools.Workspace;
import dev.rajeev.orchestrator.workflows.SdlcWorkflow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Creates and reopens runs: sandbox seeding, store, provider/approver/policy/toolchain wiring, reports. */
public final class RunFactory {

    public enum LlmMode { AUTO, ANTHROPIC, SCRIPTED }

    public static final class Options {
        public Path repoRoot;
        public Path runsDir;
        public String runId;
        public LlmMode llm = LlmMode.AUTO;
        public boolean autoApprove;
        public Integer concurrency;
        public LongConsumer sleeper;
        public Consumer<RunEvent> onEvent;
        public Controls.Budget budget = Controls.Budget.DEFAULT;
        public String toolchain; // overrides the scenario's when set
    }

    public record Prepared(Orchestrator orchestrator, RunStore store, Workspace workspace, Scenario scenario, Path scenarioDir) {}

    private RunFactory() {}

    public static Prepared prepare(Path scenarioDir, Options opts) {
        Scenario scenario = Scenario.load(scenarioDir);
        String runId = opts.runId != null ? opts.runId : scenario.name() + "-" + Instant.now().toString().replaceAll("[:.]", "-").substring(0, 19);
        RunStore store = RunStore.create(opts.runsDir, runId, scenario.name(), scenario.requirement(), SdlcWorkflow.stages());
        Path sandbox = store.dir().resolve("sandbox");
        Workspace workspace = scenario.seedFrom() != null ? Workspace.seedFrom(opts.repoRoot.resolve(scenario.seedFrom()), sandbox) : new Workspace(sandbox);
        store.putArtifact(ArtifactKind.REQUIREMENT, null, Map.of("text", scenario.requirement()), "scenario", 0, Map.of());
        Path abs = scenarioDir.toAbsolutePath().normalize();
        Path rel = opts.repoRoot.toAbsolutePath().normalize().relativize(abs);
        String persisted = rel.startsWith("..") ? abs.toString() : rel.toString().replace('\\', '/');
        try {
            Files.writeString(store.dir().resolve("scenario.json"), Json.write(scenario.withScenarioDir(persisted)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Prepared(wire(store, workspace, scenario, abs, opts), store, workspace, scenario, abs);
    }

    public static Prepared reopen(String runId, Options opts) {
        RunStore store = RunStore.load(opts.runsDir, runId);
        Scenario saved;
        try {
            saved = Scenario.parse(Json.parse(Files.readString(store.dir().resolve("scenario.json"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Workspace workspace = new Workspace(store.dir().resolve("sandbox"));
        Path scenarioDir = opts.repoRoot.resolve(saved.scenarioDir()).toAbsolutePath().normalize();
        return new Prepared(wire(store, workspace, saved, scenarioDir, opts), store, workspace, saved, scenarioDir);
    }

    private static Orchestrator wire(RunStore store, Workspace workspace, Scenario scenario, Path scenarioDir, Options opts) {
        Orchestrator.Config cfg = new Orchestrator.Config();
        cfg.store = store;
        cfg.workspace = workspace;
        cfg.toolchain = toolchain(opts.toolchain != null ? opts.toolchain : scenario.toolchain());
        cfg.llm = provider(opts.llm, scenarioDir.resolve(scenario.fixtures()), fb -> store.emit("provider.fallback", Actor.SYSTEM, Map.of("from", fb.from(), "to", fb.to(), "reason", fb.reason()), null, null));
        cfg.approver = approver(scenario, opts.autoApprove);
        cfg.policy = policy(scenario);
        cfg.safeStop = new Controls.SafeStop(store.dir().resolve("STOP"), opts.budget);
        cfg.injections = scenario.injections();
        if (opts.concurrency != null) cfg.concurrency = opts.concurrency;
        if (opts.sleeper != null) cfg.sleeper = opts.sleeper;
        cfg.onEvent = opts.onEvent;
        return new Orchestrator(cfg);
    }

    public static LlmProvider provider(LlmMode mode, Path fixturesDir, Consumer<ProviderChain.Fallback> onFallback) {
        ScriptedProvider scripted = new ScriptedProvider(fixturesDir);
        if (mode == LlmMode.SCRIPTED) return scripted;
        AnthropicProvider anthropic = new AnthropicProvider();
        if (mode == LlmMode.ANTHROPIC && !anthropic.available()) throw new IllegalStateException("--llm anthropic requested but ANTHROPIC_API_KEY is not set");
        return new ProviderChain(List.of(anthropic, scripted), onFallback);
    }

    public static Approver approver(Scenario scenario, boolean autoApprove) {
        Approver fallback = autoApprove ? new Approvals.Auto() : new Approvals.Pausing();
        return scenario.approvals().isEmpty() ? fallback : new Approvals.Scripted(scenario.approvals(), fallback);
    }

    public static PolicyEngine policy(Scenario scenario) {
        PolicyEngine.Config cfg = PolicyEngine.Config.DEFAULT;
        if (scenario.policy() != null) {
            if (scenario.policy().protectedPaths() != null) cfg = cfg.withProtectedPaths(scenario.policy().protectedPaths());
            if (scenario.policy().changeBudget() != null) cfg = cfg.withBudget(scenario.policy().changeBudget().maxFiles(), scenario.policy().changeBudget().maxLines());
        }
        return new PolicyEngine(cfg);
    }

    /**
     * auto → javac when ORCHESTRATOR_CLASSPATH is set (fast, Maven-free), else maven when {@code mvn} is on the PATH.
     */
    public static Toolchain toolchain(String name) {
        String env = System.getenv("ORCHESTRATOR_TOOLCHAIN");
        String pick = name == null || name.equals("auto") ? (env != null && !env.isBlank() ? env : null) : name;
        if (pick == null) {
            String cp = System.getenv("ORCHESTRATOR_CLASSPATH");
            pick = cp != null && !cp.isBlank() ? "javac" : CommandRunner.onPath("mvn") ? "maven" : "javac";
        }
        return switch (pick) {
            case "maven" -> new MavenToolchain();
            case "javac" -> new JavacToolchain();
            default -> throw new IllegalArgumentException("unknown toolchain '" + pick + "' (maven|javac|auto)");
        };
    }

    public record Reports(String markdown, String html) {}

    public static Reports writeReports(Prepared p) {
        String md = MarkdownReport.render(p.store(), p.orchestrator().graph(), p.scenario());
        String html = HtmlReport.render(p.store(), p.orchestrator().graph(), p.scenario());
        try {
            Files.writeString(p.store().dir().resolve("report.md"), md);
            Files.writeString(p.store().dir().resolve("report.html"), html);
            Files.writeString(p.store().dir().resolve("metrics.json"), Json.write(p.store().state().metrics));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Reports(md, html);
    }
}

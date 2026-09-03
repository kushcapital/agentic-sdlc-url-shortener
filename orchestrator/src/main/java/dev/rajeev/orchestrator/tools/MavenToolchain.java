package dev.rajeev.orchestrator.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies a sandbox that is a Maven project. {@code -o} (offline) is used when the local repository
 * already has the dependencies, which is true after the first build; set {@code ORCHESTRATOR_MAVEN_ONLINE=1}
 * to allow downloads during a run.
 */
public final class MavenToolchain implements Toolchain {

    private final long timeoutMs;
    private final String mvn;

    public MavenToolchain() {
        this(600_000);
    }

    public MavenToolchain(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        String configured = System.getenv("ORCHESTRATOR_MVN");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        this.mvn = configured != null && !configured.isBlank() ? configured : windows ? "mvn.cmd" : "mvn";
    }

    @Override
    public String name() {
        return "maven";
    }

    private List<String> base() {
        List<String> cmd = new ArrayList<>(List.of(mvn, "-B", "-q", "-Dstyle.color=never"));
        if (!"1".equals(System.getenv("ORCHESTRATOR_MAVEN_ONLINE"))) cmd.add("-o");
        return cmd;
    }

    @Override
    public CommandResult compile(Path workspaceDir) {
        List<String> cmd = base();
        cmd.addAll(List.of("-DskipTests", "test-compile"));
        return CommandRunner.run(cmd, workspaceDir, timeoutMs, Map.of());
    }

    @Override
    public TestSummary test(Path workspaceDir) {
        List<String> cmd = base();
        cmd.addAll(List.of("-Dsurefire.printSummary=true", "test"));
        CommandResult res = CommandRunner.run(cmd, workspaceDir, timeoutMs, Map.of());
        JUnitXml.Counts c = JUnitXml.parseDirectory(workspaceDir.resolve("target").resolve("surefire-reports"));
        if (c.total() == 0 && !res.ok()) return TestSummary.broken(res.output(), res.durationMs());
        return new TestSummary(res.ok() && c.failed() == 0, c.passed(), c.failed(), c.total(), res.durationMs(), c.failures(), res.output());
    }
}

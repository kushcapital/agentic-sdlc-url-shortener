package dev.rajeev.orchestrator.tools;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Maven-free verification of a standard Maven layout ({@code src/main/java}, {@code src/test/java}):
 * {@code javac} against an explicit classpath, then the JUnit Platform console launcher, then the
 * legacy XML report. Used as the fast inner loop and wherever Maven Central is unreachable.
 *
 * Configuration (environment):
 * <ul>
 *   <li>{@code ORCHESTRATOR_CLASSPATH} — path-separated jars/directories, or a single directory of jars</li>
 *   <li>{@code ORCHESTRATOR_JUNIT_CONSOLE} — the junit-platform-console-standalone jar</li>
 * </ul>
 */
public final class JavacToolchain implements Toolchain {

    private final List<String> classpath;
    private final Path junitConsole;
    private final long timeoutMs;

    public JavacToolchain() {
        this(fromEnv("ORCHESTRATOR_CLASSPATH"), Path.of(System.getenv().getOrDefault("ORCHESTRATOR_JUNIT_CONSOLE", "/usr/share/java/junit-platform-console-standalone.jar")), 300_000);
    }

    public JavacToolchain(List<String> classpath, Path junitConsole, long timeoutMs) {
        this.classpath = List.copyOf(classpath);
        this.junitConsole = junitConsole;
        this.timeoutMs = timeoutMs;
    }

    public static List<String> fromEnv(String var) {
        String v = System.getenv(var);
        if (v == null || v.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String entry : v.split(File.pathSeparator)) {
            Path p = Path.of(entry);
            if (Files.isDirectory(p) && !entry.endsWith("classes")) {
                try (Stream<Path> jars = Files.list(p)) {
                    jars.filter(j -> j.toString().endsWith(".jar")).sorted().forEach(j -> out.add(j.toString()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                out.add(entry);
            }
        }
        return out;
    }

    @Override
    public String name() {
        return "javac";
    }

    private static List<String> sources(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyResources(Path from, Path to) {
        if (Files.isDirectory(from)) Workspace.copyTree(from, to);
    }

    private String cp(List<String> extra) {
        List<String> all = new ArrayList<>(extra);
        all.addAll(classpath);
        return String.join(File.pathSeparator, all);
    }

    @Override
    public CommandResult compile(Path ws) {
        long started = System.currentTimeMillis();
        Path out = ws.resolve("target");
        Path classes = out.resolve("classes");
        Path testClasses = out.resolve("test-classes");
        Workspace.deleteTree(out);
        try {
            Files.createDirectories(classes);
            Files.createDirectories(testClasses);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> main = sources(ws.resolve("src/main/java"));
        if (main.isEmpty()) return new CommandResult(false, 1, "no sources under src/main/java", System.currentTimeMillis() - started);
        List<String> cmd = new ArrayList<>(List.of("javac", "-parameters", "-Xlint:none", "-d", classes.toString(), "-cp", cp(List.of())));
        cmd.addAll(main);
        CommandResult r = CommandRunner.run(cmd, ws, timeoutMs, Map.of());
        if (!r.ok()) return r;
        copyResources(ws.resolve("src/main/resources"), classes);
        List<String> tests = sources(ws.resolve("src/test/java"));
        if (tests.isEmpty()) return new CommandResult(true, 0, r.output() + "\n(no tests to compile)", System.currentTimeMillis() - started);
        List<String> tcmd = new ArrayList<>(List.of("javac", "-parameters", "-Xlint:none", "-d", testClasses.toString(), "-cp", cp(List.of(classes.toString(), junitConsole.toString()))));
        tcmd.addAll(tests);
        CommandResult t = CommandRunner.run(tcmd, ws, timeoutMs, Map.of());
        copyResources(ws.resolve("src/test/resources"), testClasses);
        return new CommandResult(t.ok(), t.exitCode(), r.output() + t.output(), System.currentTimeMillis() - started);
    }

    @Override
    public TestSummary test(Path ws) {
        CommandResult c = compile(ws);
        if (!c.ok()) return TestSummary.broken("compilation failed:\n" + c.output(), c.durationMs());
        Path classes = ws.resolve("target/classes");
        Path testClasses = ws.resolve("target/test-classes");
        Path reports = ws.resolve("target/junit-reports");
        Workspace.deleteTree(reports);
        String pkg = rootPackage(ws.resolve("src/test/java"));
        if (pkg == null) return new TestSummary(true, 0, 0, 0, c.durationMs(), List.of(), "no tests");
        List<String> cmd = new ArrayList<>(List.of("java", "-jar", junitConsole.toString(), "execute",
                "-cp", cp(List.of(testClasses.toString(), classes.toString())),
                "--select-package", pkg, "--details=summary", "--disable-banner", "--reports-dir=" + reports));
        CommandResult r = CommandRunner.run(cmd, ws, timeoutMs, Map.of());
        JUnitXml.Counts counts = JUnitXml.parseDirectory(reports);
        if (counts.total() == 0 && !r.ok()) return TestSummary.broken(r.output(), c.durationMs() + r.durationMs());
        return new TestSummary(r.ok() && counts.failed() == 0, counts.passed(), counts.failed(), counts.total(), c.durationMs() + r.durationMs(), counts.failures(), r.output());
    }

    /** Shallowest package that contains test sources, e.g. {@code dev.rajeev.shortener}. */
    static String rootPackage(Path testSrc) {
        List<String> files = sources(testSrc);
        if (files.isEmpty()) return null;
        String common = null;
        for (String f : files) {
            Path rel = testSrc.relativize(Path.of(f)).getParent();
            String pkg = rel == null ? "" : rel.toString().replace(File.separatorChar, '.');
            if (common == null) common = pkg;
            else {
                while (!pkg.startsWith(common)) common = common.contains(".") ? common.substring(0, common.lastIndexOf('.')) : "";
            }
        }
        return common == null || common.isEmpty() ? null : common;
    }
}

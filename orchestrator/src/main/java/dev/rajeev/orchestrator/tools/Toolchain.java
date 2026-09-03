package dev.rajeev.orchestrator.tools;

import java.nio.file.Path;
import java.util.List;

/**
 * How the orchestrator verifies a workspace. The engine only knows {@link #compile} and {@link #test};
 * the adapter knows the build system.
 * <ul>
 *   <li>{@link MavenToolchain}: {@code mvn compile test-compile} / {@code mvn test} + surefire XML — the
 *       reviewer's default.</li>
 *   <li>{@link JavacToolchain}: javac + the JUnit console launcher against an explicit classpath — the
 *       fast inner loop, and the only option in an environment without Maven Central.</li>
 * </ul>
 */
public interface Toolchain {

    record CommandResult(boolean ok, int exitCode, String output, long durationMs) {}

    record Failure(String name, String message) {}

    record TestSummary(boolean ok, int passed, int failed, int total, long durationMs, List<Failure> failures, String rawOutput) {
        public static TestSummary broken(String output, long durationMs) {
            return new TestSummary(false, 0, 0, 0, durationMs, List.of(new Failure("toolchain", output.length() > 2000 ? output.substring(output.length() - 2000) : output)), output);
        }
    }

    String name();

    CommandResult compile(Path workspaceDir);

    TestSummary test(Path workspaceDir);
}

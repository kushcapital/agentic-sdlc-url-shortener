package dev.rajeev.orchestrator.tools;

import dev.rajeev.orchestrator.tools.Toolchain.CommandResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a subprocess with merged stdout/stderr, a hard timeout, and colour disabled. */
public final class CommandRunner {

    private CommandRunner() {}

    public static CommandResult run(List<String> command, Path cwd, long timeoutMs, Map<String, String> env) {
        long started = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            pb.environment().put("NO_COLOR", "1");
            pb.environment().put("MAVEN_OPTS", pb.environment().getOrDefault("MAVEN_OPTS", "") + " -Dstyle.color=never");
            pb.environment().put("JAVA_TOOL_OPTIONS", pb.environment().getOrDefault("JAVA_TOOL_OPTIONS", ""));
            if (env != null) pb.environment().putAll(env);
            Process p = pb.start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new CommandResult(false, -1, new String(out, StandardCharsets.UTF_8) + "\n[timed out after " + timeoutMs + " ms]", System.currentTimeMillis() - started);
            }
            return new CommandResult(p.exitValue() == 0, p.exitValue(), new String(out, StandardCharsets.UTF_8), System.currentTimeMillis() - started);
        } catch (IOException e) {
            return new CommandResult(false, -1, "failed to start " + command.get(0) + ": " + e.getMessage(), System.currentTimeMillis() - started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, -1, "interrupted", System.currentTimeMillis() - started);
        }
    }

    public static boolean onPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (Path.of(dir, executable).toFile().canExecute() || Path.of(dir, executable + ".cmd").toFile().canExecute()) return true;
        }
        return false;
    }
}

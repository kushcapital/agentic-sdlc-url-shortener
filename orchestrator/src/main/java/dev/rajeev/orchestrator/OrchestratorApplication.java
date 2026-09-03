package dev.rajeev.orchestrator;

import dev.rajeev.orchestrator.cli.OrchestratorCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot shell around the engine: {@code java -jar orchestrator.jar run --scenario brownfield}.
 * The engine itself is framework-free (see {@code core/}); Spring provides the runnable jar, the
 * externalised configuration and a place to plug real integrations (a Jira/Slack {@code Approver},
 * a database-backed {@code RunStore}) as beans.
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(new SpringApplicationBuilder(OrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                .run(args)));
    }

    /** Repo root: ORCHESTRATOR_REPO_ROOT, else the nearest ancestor of the working directory that has a scenarios/ folder. */
    static Path repoRoot() {
        String env = System.getenv("ORCHESTRATOR_REPO_ROOT");
        if (env != null && !env.isBlank()) return Path.of(env).toAbsolutePath().normalize();
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("scenarios"))) return dir;
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    @Bean
    CommandLineRunner cli(ExitCodeHolder holder) {
        return args -> {
            Path root = repoRoot();
            String runs = System.getenv("ORCHESTRATOR_RUNS_DIR");
            Path runsDir = runs != null && !runs.isBlank() ? Path.of(runs).toAbsolutePath().normalize() : root.resolve("runs");
            try {
                holder.code = new OrchestratorCli(root, runsDir, System.out).execute(args);
            } catch (RuntimeException e) {
                System.err.println("error: " + e.getMessage());
                holder.code = 1;
            }
        };
    }

    @Bean
    ExitCodeHolder exitCodeHolder() {
        return new ExitCodeHolder();
    }

    static final class ExitCodeHolder implements ExitCodeGenerator {
        volatile int code;

        @Override
        public int getExitCode() {
            return code;
        }
    }
}

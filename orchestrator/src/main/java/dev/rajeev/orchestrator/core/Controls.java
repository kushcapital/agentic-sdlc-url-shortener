package dev.rajeev.orchestrator.core;

import dev.rajeev.orchestrator.core.Types.RetryPolicy;
import dev.rajeev.orchestrator.core.Types.RunMetrics;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.core.Types.StageStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Retry backoff, the cooperative kill switch with budgets, and metrics finalisation. */
public final class Controls {

    private Controls() {}

    public static long backoffDelay(RetryPolicy policy, int attempt) {
        return Math.round(policy.backoffMs() * Math.pow(policy.factor(), attempt - 1));
    }

    public record Budget(long maxWallClockMs, int maxToolCalls, int maxLlmCalls) {
        public static final Budget DEFAULT = new Budget(30 * 60_000L, 600, 150);
    }

    /**
     * Cooperative kill switch. Triggers: a STOP file in the run directory (an operator can stop a run
     * from another shell), SIGINT/SIGTERM via a shutdown hook, and budget exhaustion (wall-clock, tool
     * calls, LLM calls — bounded autonomy). The engine checks it at every stage boundary and before
     * every agent call, persists state, and exits with status STOPPED.
     */
    public static final class SafeStop {
        private final Path stopFile;
        private final Budget budget;
        private final long startedAt = System.currentTimeMillis();
        private volatile String requested;

        public SafeStop(Path stopFile, Budget budget) {
            this.stopFile = stopFile;
            this.budget = budget;
        }

        public void requestStop(String reason) {
            requested = reason;
        }

        public String shouldStop(RunMetrics m) {
            if (requested != null) return requested;
            if (Files.exists(stopFile)) return "STOP file present";
            if (System.currentTimeMillis() - startedAt > budget.maxWallClockMs()) return "wall-clock budget exceeded (" + budget.maxWallClockMs() + " ms)";
            if (m.toolCalls > budget.maxToolCalls()) return "tool-call budget exceeded (" + budget.maxToolCalls() + ")";
            if (m.llmCalls > budget.maxLlmCalls()) return "LLM-call budget exceeded (" + budget.maxLlmCalls() + ")";
            return null;
        }

        public void assertRunning(RunMetrics m) {
            String reason = shouldStop(m);
            if (reason != null) throw new OrchestrationException("safe-stop: " + reason, OrchestrationException.Kind.STOPPED, false);
        }

        public Thread shutdownHook() {
            Thread t = new Thread(() -> requestStop("signal received"), "safe-stop");
            Runtime.getRuntime().addShutdownHook(t);
            return t;
        }
    }

    public static RunMetrics finalizeMetrics(RunState state) {
        RunMetrics m = state.metrics;
        List<StageState> stages = List.copyOf(state.stages.values());
        m.stagesTotal = stages.size();
        m.stagesSucceeded = (int) stages.stream().filter(s -> s.status == StageStatus.SUCCEEDED || s.status == StageStatus.SKIPPED).count();
        m.stagesFailed = (int) stages.stream().filter(s -> s.status == StageStatus.FAILED || s.status == StageStatus.ROLLED_BACK).count();
        // attemptsTotal and retries are counted live: retries are failure-driven re-attempts only; re-plans are counted separately.
        List<StageState> recoveries = stages.stream().filter(s -> s.firstFailureAt != null && s.status == StageStatus.SUCCEEDED && s.finishedAt != null).toList();
        m.mttrMs = recoveries.isEmpty() ? null : Math.round(recoveries.stream().mapToLong(s -> Instant.parse(s.finishedAt).toEpochMilli() - Instant.parse(s.firstFailureAt).toEpochMilli()).average().orElse(0));
        for (StageState s : stages) {
            if (s.startedAt != null && s.finishedAt != null) m.stageLatencyMs.put(s.id, Instant.parse(s.finishedAt).toEpochMilli() - Instant.parse(s.startedAt).toEpochMilli());
        }
        m.endToEndMs = Instant.parse(state.updatedAt).toEpochMilli() - Instant.parse(state.createdAt).toEpochMilli();
        m.successRate = m.attemptsTotal == 0 ? null : Math.round(1000.0 * (m.attemptsTotal - m.retries - (m.stagesFailed > 0 ? 1 : 0)) / m.attemptsTotal) / 1000.0;
        return m;
    }

    public static void markFirstFailure(StageState s) {
        if (s.firstFailureAt == null) s.firstFailureAt = Instant.now().toString();
    }
}

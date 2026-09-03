package dev.rajeev.orchestrator.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared vocabulary for the engine. Deliberately generic: stages, gates, artifacts, approvals,
 * policies — never URL shorteners. The SDLC-specific shape lives in workflows/ and agents/.
 *
 * Records are immutable; the mutable run state ({@link StageState}, {@link RunState}, {@link RunMetrics})
 * are plain classes because the engine mutates them in place and persists them as a snapshot.
 */
public final class Types {

    private Types() {}

    public enum Phase { REQUIREMENTS, DESIGN, PLANNING, IMPLEMENTATION, TESTING, REVIEW, DOCUMENTATION, RELEASE }

    public enum AgentKind { REQUIREMENTS_ANALYST, ARCHITECT, PLANNER, IMPLEMENTER, TESTER, REVIEWER, DOC_WRITER, RELEASE_MANAGER }

    public enum ArtifactKind { REQUIREMENT, CLARIFICATIONS, REQUIREMENTS_SPEC, ARCHITECTURE, TASK_PLAN, CODE_PATCH, TEST_REPORT, REVIEW_REPORT, DOC_UPDATE, RELEASE_CHECKLIST }

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public enum StageStatus { PENDING, READY, RUNNING, WAITING_APPROVAL, SUCCEEDED, FAILED, SKIPPED, INVALIDATED, ROLLED_BACK }

    public enum RunStatus { CREATED, RUNNING, PAUSED, COMPLETED, FAILED, STOPPED }

    public enum FailurePolicy { FAIL_RUN, ROLLBACK_AND_STOP, ESCALATE, SKIP }

    public enum Actor { SYSTEM, AGENT, HUMAN, POLICY, SCENARIO }

    public enum Verdict { ALLOW, REQUIRE_APPROVAL, BLOCK }

    public enum Decision { APPROVE, REJECT }

    public record RetryPolicy(int maxAttempts, long backoffMs, double factor) {
        public static RetryPolicy of(int maxAttempts, long backoffMs) { return new RetryPolicy(maxAttempts, backoffMs, 2.0); }
    }

    /** A human checkpoint. {@code questionsFrom} marries the checkpoint to clarification questions in an artifact. */
    public record ApprovalSpec(String gateId, String reason, RiskLevel riskLevel, ArtifactKind questionsFrom) {
        public static ApprovalSpec of(String gateId, String reason, RiskLevel risk) { return new ApprovalSpec(gateId, reason, risk, null); }
    }

    public record GateResult(String gateId, boolean passed, String details, ApprovalSpec requiresApproval) {
        public static GateResult pass(String gateId, String details) { return new GateResult(gateId, true, details, null); }
        public static GateResult fail(String gateId, String details) { return new GateResult(gateId, false, details, null); }
        public static GateResult approval(String gateId, String details, ApprovalSpec spec) { return new GateResult(gateId, false, details, spec); }
    }

    public record Dynamic(String fromStage, String taskId) {}

    public record StageDefinition(
            String id,
            String name,
            Phase phase,
            AgentKind agent,
            List<String> dependsOn,
            List<ArtifactKind> consumes,
            ArtifactKind produces,
            List<String> entryGates,
            List<String> exitGates,
            ApprovalSpec approval,
            RetryPolicy retry,
            FailurePolicy onFailure,
            boolean mutatesWorkspace,
            long timeoutMs,
            Dynamic dynamic,
            Map<String, Object> params) {

        public StageDefinition {
            dependsOn = List.copyOf(dependsOn);
            consumes = List.copyOf(consumes);
            entryGates = List.copyOf(entryGates);
            exitGates = List.copyOf(exitGates);
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public StageDefinition withDependsOn(List<String> deps) {
            return new StageDefinition(id, name, phase, agent, deps, consumes, produces, entryGates, exitGates, approval, retry, onFailure, mutatesWorkspace, timeoutMs, dynamic, params);
        }

        public StageDefinition asDynamic(String newId, String newName, List<String> deps, Dynamic dyn, Map<String, Object> extraParams) {
            Map<String, Object> merged = new LinkedHashMap<>(params);
            merged.putAll(extraParams);
            return new StageDefinition(newId, newName, phase, agent, deps, consumes, produces, entryGates, exitGates, approval, retry, onFailure, mutatesWorkspace, timeoutMs, dyn, merged);
        }

        public String param(String key) {
            Object v = params.get(key);
            return v == null ? null : v.toString();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArtifactRecord(
            ArtifactKind kind,
            String producedBy,
            int attempt,
            int version,
            String hash,
            Map<String, String> inputHashes,
            String createdAt,
            JsonNode content) {}

    /** Persisted output of a stage that paused at a checkpoint mid-way through its exit gates. */
    public record Pending(JsonNode output, int gateIndex, String approvalGateId) {}

    public static final class StageState {
        public String id;
        public StageStatus status = StageStatus.PENDING;
        public int attempts;
        public Integer attemptBase;
        public String startedAt;
        public String finishedAt;
        public String firstFailureAt;
        public String lastError;
        public List<String> feedback = new ArrayList<>();
        public Map<String, String> inputHashes = new LinkedHashMap<>();
        public String outputHash;
        public String worktree;
        public Map<String, String> baseHashes;
        public String invalidationReason;
        public Pending pending;

        public StageState() {}

        public StageState(String id) { this.id = id; }

        public int attemptBase() { return attemptBase == null ? 0 : attemptBase; }
    }

    public static final class ApprovalRecord {
        public String gateId;
        public String stageId;
        public ApprovalSpec spec;
        public String requestedAt;
        public String decidedAt;
        public Decision decision;
        public String decidedBy;
        public String note;
        public Map<String, String> answers;
        public String consumedAt;
    }

    public static final class RunMetrics {
        public int stagesTotal;
        public int stagesSucceeded;
        public int stagesFailed;
        public int attemptsTotal;
        public int retries;
        public int rollbacks;
        public int replans;
        public int approvalsRequested;
        public int approvalsHuman;
        public int approvalsAuto;
        public int policyViolations;
        public int policyBlocks;
        public int llmCalls;
        public long llmInputTokens;
        public long llmOutputTokens;
        public int toolCalls;
        public Long mttrMs;
        public Long endToEndMs;
        public Map<String, Long> stageLatencyMs = new LinkedHashMap<>();
        public Double successRate;
    }

    public static final class RunState {
        public String runId;
        public String scenario;
        public RunStatus status = RunStatus.CREATED;
        public String createdAt;
        public String updatedAt;
        public String requirement;
        public Map<String, StageState> stages = new LinkedHashMap<>();
        public List<String> stageOrder = new ArrayList<>();
        public List<StageDefinition> graph = new ArrayList<>();
        public Map<String, StageDefinition> templates = new LinkedHashMap<>();
        public Map<String, ArtifactRecord> artifacts = new LinkedHashMap<>();
        public Map<String, ApprovalRecord> approvals = new LinkedHashMap<>();
        public RunMetrics metrics = new RunMetrics();
        public List<String> notices = new ArrayList<>();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunEvent(long seq, String ts, String runId, String type, Actor actor, String stageId, Integer attempt, Map<String, Object> payload) {}

    public record PolicyVerdict(String rule, Verdict verdict, String message, RiskLevel riskLevel, Object evidence) {}

    public record PatchFile(String path, String action, String content) {
        public boolean isDelete() { return "delete".equals(action); }
    }

    public record CodePatch(String summary, String taskId, List<PatchFile> files, List<String> dependencyChanges, List<String> notes) {
        public CodePatch {
            files = files == null ? List.of() : List.copyOf(files);
            dependencyChanges = dependencyChanges == null ? List.of() : List.copyOf(dependencyChanges);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }
}

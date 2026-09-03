package dev.rajeev.orchestrator.workflows;

import dev.rajeev.orchestrator.core.Types.AgentKind;
import dev.rajeev.orchestrator.core.Types.ApprovalSpec;
import dev.rajeev.orchestrator.core.Types.ArtifactKind;
import dev.rajeev.orchestrator.core.Types.FailurePolicy;
import dev.rajeev.orchestrator.core.Types.Phase;
import dev.rajeev.orchestrator.core.Types.RetryPolicy;
import dev.rajeev.orchestrator.core.Types.RiskLevel;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import java.util.List;
import java.util.Map;

/**
 * The SDLC workflow as an explicit dependency graph.
 * <pre>
 *   requirements ─► architecture ─► plan ─► implement:* ─┬─► test ───┐
 *                     (design           (expands into    ├─► review ─┼─► release
 *                      sign-off)         one node per    └─► docs ───┘   (go/no-go
 *                                        planned task)                    approval)
 * </pre>
 * Non-linear on purpose: implement:* nodes run in parallel where the plan allows and join at
 * test/review/docs; those three are parallel branches that join at release; any stage can be
 * invalidated and re-run when an upstream artifact changes.
 */
public final class SdlcWorkflow {

    private static final RetryPolicy FAST = RetryPolicy.of(2, 50);
    private static final RetryPolicy CODE = RetryPolicy.of(3, 100);

    private SdlcWorkflow() {}

    private static StageDefinition stage(String id, String name, Phase phase, AgentKind agent, List<String> deps, List<ArtifactKind> consumes, ArtifactKind produces,
                                         List<String> entry, List<String> exit, ApprovalSpec approval, RetryPolicy retry, FailurePolicy onFailure, boolean mutates, long timeoutMs, Map<String, Object> params) {
        return new StageDefinition(id, name, phase, agent, deps, consumes, produces, entry, exit, approval, retry, onFailure, mutates, timeoutMs, null, params);
    }

    public static List<StageDefinition> stages() {
        return List.of(
                stage("requirements", "Requirement understanding", Phase.REQUIREMENTS, AgentKind.REQUIREMENTS_ANALYST, List.of(), List.of(), ArtifactKind.REQUIREMENTS_SPEC,
                        List.of("workspace-ready"), List.of("clarifications-resolved"), null, RetryPolicy.of(3, 50), FailurePolicy.FAIL_RUN, false, 120_000, Map.of()),
                stage("architecture", "Architecture & impact analysis", Phase.DESIGN, AgentKind.ARCHITECT, List.of("requirements"), List.of(ArtifactKind.REQUIREMENTS_SPEC), ArtifactKind.ARCHITECTURE,
                        List.of("upstream-artifacts-present"), List.of(),
                        ApprovalSpec.of("design-review", "Architecture and impacted-module analysis need a human design sign-off before implementation starts.", RiskLevel.MEDIUM),
                        FAST, FailurePolicy.FAIL_RUN, false, 120_000, Map.of()),
                stage("plan", "Task decomposition", Phase.PLANNING, AgentKind.PLANNER, List.of("architecture"), List.of(ArtifactKind.REQUIREMENTS_SPEC, ArtifactKind.ARCHITECTURE), ArtifactKind.TASK_PLAN,
                        List.of("upstream-artifacts-present"), List.of("plan-valid"), null, FAST, FailurePolicy.FAIL_RUN, false, 120_000, Map.of("expands", "implement")),
                stage("implement", "Implementation", Phase.IMPLEMENTATION, AgentKind.IMPLEMENTER, List.of("plan"), List.of(ArtifactKind.REQUIREMENTS_SPEC, ArtifactKind.ARCHITECTURE, ArtifactKind.TASK_PLAN), ArtifactKind.CODE_PATCH,
                        List.of("upstream-artifacts-present"), List.of("policy-check", "apply-patch", "verify-patch"), null, CODE, FailurePolicy.ROLLBACK_AND_STOP, true, 600_000, Map.of()),
                stage("test", "Verification (compile + full suite)", Phase.TESTING, AgentKind.TESTER, List.of("implement"), List.of(ArtifactKind.TASK_PLAN), ArtifactKind.TEST_REPORT,
                        List.of("upstream-artifacts-present"), List.of("tests-green"), null, RetryPolicy.of(1, 0), FailurePolicy.ESCALATE, false, 900_000, Map.of()),
                stage("review", "Security & quality review", Phase.REVIEW, AgentKind.REVIEWER, List.of("implement"), List.of(ArtifactKind.ARCHITECTURE, ArtifactKind.TASK_PLAN), ArtifactKind.REVIEW_REPORT,
                        List.of("upstream-artifacts-present"), List.of("review-approved"), null, FAST, FailurePolicy.ESCALATE, false, 120_000, Map.of()),
                stage("docs", "Documentation", Phase.DOCUMENTATION, AgentKind.DOC_WRITER, List.of("implement"), List.of(ArtifactKind.REQUIREMENTS_SPEC, ArtifactKind.ARCHITECTURE), ArtifactKind.DOC_UPDATE,
                        List.of("upstream-artifacts-present"), List.of("policy-check", "apply-patch"), null, FAST, FailurePolicy.SKIP, true, 120_000, Map.of()),
                stage("release", "Release readiness", Phase.RELEASE, AgentKind.RELEASE_MANAGER, List.of("test", "review", "docs"), List.of(ArtifactKind.TEST_REPORT, ArtifactKind.REVIEW_REPORT, ArtifactKind.ARCHITECTURE), ArtifactKind.RELEASE_CHECKLIST,
                        List.of("upstream-artifacts-present"), List.of("release-ready"),
                        ApprovalSpec.of("release-approval", "Releasing to production is a high-impact action; a human owns the go/no-go.", RiskLevel.HIGH),
                        FAST, FailurePolicy.FAIL_RUN, false, 120_000, Map.of()));
    }
}

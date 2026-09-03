package dev.rajeev.orchestrator.core;

import dev.rajeev.orchestrator.core.Types.ApprovalSpec;
import dev.rajeev.orchestrator.core.Types.Decision;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human checkpoint abstraction.
 * <ul>
 *   <li>{@link Pausing}: never decides; the run persists as PAUSED and a human uses the CLI (default).</li>
 *   <li>{@link Scripted}: decisions authored in scenario.json (reproducible scenarios, including rejections
 *       and clarification answers).</li>
 *   <li>{@link Auto}: approves everything, stamped {@code auto-approver} in the audit log. Demo mode only.</li>
 * </ul>
 */
public final class Approvals {

    private Approvals() {}

    public record Question(String id, String question, List<String> options, String recommended) {}

    public record Request(String stageId, ApprovalSpec spec, List<Question> questions) {}

    public record Outcome(Decision decision, String decidedBy, String note, Map<String, String> answers) {}

    public interface Approver {
        String name();
        /** @return null to pause the run and wait for a human. */
        Outcome decide(Request request);
    }

    public static final class Pausing implements Approver {
        @Override public String name() { return "pausing"; }
        @Override public Outcome decide(Request request) { return null; }
    }

    public static final class Auto implements Approver {
        @Override public String name() { return "auto"; }

        @Override
        public Outcome decide(Request request) {
            Map<String, String> answers = null;
            if (request.questions() != null && !request.questions().isEmpty()) {
                answers = new LinkedHashMap<>();
                for (Question q : request.questions()) answers.put(q.id(), q.recommended());
            }
            return new Outcome(Decision.APPROVE, "auto-approver", "auto-approved (" + request.spec().riskLevel().name().toLowerCase() + " risk) — demo mode", answers);
        }
    }

    /** One scripted decision; {@code onRequest} applies it only to the Nth request of that gate (1-based). */
    public record ScriptedDecision(Decision decision, String note, Map<String, String> answers, Integer onRequest) {}

    public static final class Scripted implements Approver {
        private final Map<String, List<ScriptedDecision>> script;
        private final Approver fallback;
        private final Map<String, Integer> seen = new HashMap<>();

        public Scripted(Map<String, List<ScriptedDecision>> script, Approver fallback) {
            this.script = script;
            this.fallback = fallback;
        }

        @Override public String name() { return "scripted"; }

        @Override
        public synchronized Outcome decide(Request request) {
            String gate = request.spec().gateId();
            int n = seen.merge(gate, 1, Integer::sum);
            List<ScriptedDecision> list = script.getOrDefault(gate, List.of());
            ScriptedDecision match = list.stream().filter(d -> (d.onRequest() == null ? 1 : d.onRequest()) == n).findFirst()
                    .orElseGet(() -> list.size() == 1 && list.get(0).onRequest() == null && n > 1 ? list.get(0) : null);
            if (match == null) return fallback.decide(request);
            return new Outcome(match.decision(), "scripted-stakeholder", match.note(), match.answers());
        }
    }
}

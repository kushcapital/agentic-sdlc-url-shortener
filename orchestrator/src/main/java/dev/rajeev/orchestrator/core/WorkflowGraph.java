package dev.rajeev.orchestrator.core;

import dev.rajeev.orchestrator.core.Types.Dynamic;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.core.Types.StageStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit dependency graph of stages. Validated on construction (unknown dependencies, cycles) so a
 * broken workflow fails before any agent runs. Supports runtime expansion: the planner's task list
 * becomes real nodes wired by task dependencies, and dependents are rewired onto the new leaves
 * (fan-out / fan-in).
 */
public final class WorkflowGraph {

    public record Task(String id, List<String> dependsOn, String title, Map<String, Object> params) {}

    private final Map<String, StageDefinition> nodes = new LinkedHashMap<>();

    public WorkflowGraph(List<StageDefinition> stages) {
        for (StageDefinition s : stages) add(s);
        validate();
    }

    private void add(StageDefinition stage) {
        if (nodes.containsKey(stage.id())) throw new OrchestrationException("duplicate stage id '" + stage.id() + "'", OrchestrationException.Kind.CONFIG, false);
        nodes.put(stage.id(), stage);
    }

    public StageDefinition get(String id) {
        StageDefinition s = nodes.get(id);
        if (s == null) throw new OrchestrationException("unknown stage '" + id + "'", OrchestrationException.Kind.CONFIG, false);
        return s;
    }

    public boolean has(String id) {
        return nodes.containsKey(id);
    }

    public List<StageDefinition> all() {
        return ids().stream().map(nodes::get).toList();
    }

    public List<String> ids() {
        return topologicalOrder();
    }

    public void validate() {
        for (StageDefinition s : nodes.values()) {
            for (String dep : s.dependsOn()) {
                if (!nodes.containsKey(dep)) throw new OrchestrationException("stage '" + s.id() + "' depends on unknown stage '" + dep + "'", OrchestrationException.Kind.CONFIG, false);
            }
        }
        topologicalOrder();
    }

    /** Kahn's algorithm; insertion order among ready nodes keeps runs deterministic. */
    public List<String> topologicalOrder() {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        Map<String, List<String>> out = new HashMap<>();
        for (StageDefinition s : nodes.values()) {
            indeg.put(s.id(), s.dependsOn().size());
            for (String d : s.dependsOn()) out.computeIfAbsent(d, k -> new ArrayList<>()).add(s.id());
        }
        Deque<String> queue = new ArrayDeque<>();
        indeg.forEach((id, n) -> { if (n == 0) queue.add(id); });
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String next : out.getOrDefault(id, List.of())) {
                int n = indeg.merge(next, -1, Integer::sum);
                if (n == 0) queue.add(next);
            }
        }
        if (order.size() != nodes.size()) throw new OrchestrationException("workflow graph contains a cycle", OrchestrationException.Kind.CONFIG, false);
        return order;
    }

    /** Direct and transitive dependents of a stage — what must re-run when it changes. */
    public List<String> downstream(String id) {
        Set<String> result = new LinkedHashSet<>();
        collectDownstream(id, result);
        return topologicalOrder().stream().filter(result::contains).toList();
    }

    private void collectDownstream(String target, Set<String> result) {
        for (StageDefinition s : nodes.values()) {
            if (s.dependsOn().contains(target) && result.add(s.id())) collectDownstream(s.id(), result);
        }
    }

    /** Stages whose dependencies have all succeeded/skipped and which are themselves waiting. */
    public List<StageDefinition> ready(Map<String, StageState> states) {
        List<StageDefinition> out = new ArrayList<>();
        for (StageDefinition s : all()) {
            StageState st = states.get(s.id());
            if (st == null) continue;
            boolean waiting = st.status == StageStatus.PENDING || st.status == StageStatus.READY || st.status == StageStatus.INVALIDATED;
            if (!waiting) continue;
            boolean depsDone = s.dependsOn().stream().allMatch(d -> {
                StageState ds = states.get(d);
                return ds != null && (ds.status == StageStatus.SUCCEEDED || ds.status == StageStatus.SKIPPED);
            });
            if (depsDone) out.add(s);
        }
        return out;
    }

    /**
     * Expand a template node into one node per task, wired by the task's own dependencies, and make
     * every stage that depended on the template depend on all the new nodes (fan-out / fan-in join).
     */
    public List<StageDefinition> expand(String templateId, List<Task> tasks) {
        StageDefinition template = get(templateId);
        List<StageDefinition> created = new ArrayList<>();
        for (Task t : tasks) {
            List<String> deps = new ArrayList<>(template.dependsOn());
            for (String d : t.dependsOn()) deps.add(templateId + ":" + d);
            StageDefinition node = template.asDynamic(templateId + ":" + t.id(), template.name() + ": " + t.title(), deps, new Dynamic(templateId, t.id()), t.params());
            nodes.put(node.id(), node);
            created.add(node);
        }
        List<String> newIds = created.stream().map(StageDefinition::id).toList();
        for (Map.Entry<String, StageDefinition> e : new ArrayList<>(nodes.entrySet())) {
            StageDefinition s = e.getValue();
            if (s.dependsOn().contains(templateId)) {
                List<String> deps = new ArrayList<>(s.dependsOn().stream().filter(d -> !d.equals(templateId)).toList());
                deps.addAll(newIds);
                nodes.put(e.getKey(), s.withDependsOn(deps));
            }
        }
        nodes.remove(templateId);
        validate();
        return created;
    }

    /** Reverse of {@link #expand}: remove the dynamic nodes and restore the template as their dependents' dependency. */
    public WorkflowGraph collapse(String templateId, StageDefinition template) {
        Set<String> removed = new LinkedHashSet<>(dynamicNodesOf(templateId));
        List<StageDefinition> remaining = new ArrayList<>();
        for (StageDefinition s : nodes.values()) {
            if (removed.contains(s.id())) continue;
            if (s.dependsOn().stream().anyMatch(removed::contains)) {
                List<String> deps = new ArrayList<>(s.dependsOn().stream().filter(d -> !removed.contains(d)).toList());
                deps.add(templateId);
                remaining.add(s.withDependsOn(deps));
            } else {
                remaining.add(s);
            }
        }
        remaining.add(template);
        return new WorkflowGraph(remaining);
    }

    public List<String> dynamicNodesOf(String templateId) {
        return nodes.values().stream().filter(s -> s.dynamic() != null && s.dynamic().fromStage().equals(templateId)).map(StageDefinition::id).toList();
    }

    /** Mermaid rendering for reports. */
    public String toMermaid(Map<String, StageState> states) {
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        for (StageDefinition s : all()) {
            StageState st = states == null ? null : states.get(s.id());
            String label = s.id() + "<br/>" + s.agent().name().toLowerCase().replace('_', '-') + (st != null ? "<br/>[" + st.status.name().toLowerCase().replace('_', '-') + "]" : "");
            String shape = s.approval() != null ? "{{\"" + label + "\"}}" : "[\"" + label + "\"]";
            sb.append("  ").append(sanitize(s.id())).append(shape).append('\n');
            for (String d : s.dependsOn()) sb.append("  ").append(sanitize(d)).append(" --> ").append(sanitize(s.id())).append('\n');
        }
        return sb.toString();
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }
}

package dev.rajeev.orchestrator.report;

import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.RunStore;
import dev.rajeev.orchestrator.core.Types.ApprovalRecord;
import dev.rajeev.orchestrator.core.Types.RunEvent;
import dev.rajeev.orchestrator.core.Types.RunMetrics;
import dev.rajeev.orchestrator.core.Types.RunState;
import dev.rajeev.orchestrator.core.Types.StageDefinition;
import dev.rajeev.orchestrator.core.Types.StageState;
import dev.rajeev.orchestrator.core.WorkflowGraph;
import dev.rajeev.orchestrator.scenario.Scenario;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained HTML view of a run: the DAG as inline SVG coloured by status, the metrics strip, the
 * checkpoint table, the full Markdown report, and the raw event timeline. No external assets.
 */
public final class HtmlReport {

    private static final Map<String, String> COLOR = Map.of(
            "succeeded", "#1a7f37", "failed", "#cf222e", "rolled-back", "#cf222e", "waiting-approval", "#9a6700",
            "running", "#0969da", "skipped", "#6e7781", "invalidated", "#8250df", "pending", "#8c959f", "ready", "#8c959f");

    private HtmlReport() {}

    public static String render(RunStore store, WorkflowGraph graph, Scenario scenario) {
        RunState s = store.state();
        RunMetrics m = s.metrics;
        List<RunEvent> events = store.events();
        String md = MarkdownReport.render(store, graph, scenario);

        List<String[]> tiles = List.of(
                new String[] {"Status", s.status.name().toLowerCase()},
                new String[] {"Stages", m.stagesSucceeded + "/" + m.stagesTotal},
                new String[] {"Attempts / retries", m.attemptsTotal + " / " + m.retries},
                new String[] {"Success rate", m.successRate == null ? "n/a" : Math.round(m.successRate * 100) + "%"},
                new String[] {"Rollbacks", String.valueOf(m.rollbacks)},
                new String[] {"Re-plans", String.valueOf(m.replans)},
                new String[] {"Approvals (human/auto)", m.approvalsHuman + "/" + m.approvalsAuto},
                new String[] {"Policy findings/blocks", m.policyViolations + "/" + m.policyBlocks},
                new String[] {"MTTR", m.mttrMs == null ? "—" : m.mttrMs + " ms"},
                new String[] {"End-to-end", m.endToEndMs == null ? "—" : String.format("%.1f s", m.endToEndMs / 1000.0)},
                new String[] {"LLM calls", String.valueOf(m.llmCalls)},
                new String[] {"Tool calls", String.valueOf(m.toolCalls)});

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Run ").append(esc(s.runId)).append("</title>\n<style>\n")
                .append(":root{color-scheme:light;--fg:#1f2328;--muted:#57606a;--line:#d0d7de;--bg:#fff;--tile:#f6f8fa}")
                .append("body{margin:0;font:14px/1.5 -apple-system,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;color:var(--fg);background:var(--bg)}")
                .append("main{max-width:1180px;margin:0 auto;padding:24px}h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 10px;border-bottom:1px solid var(--line);padding-bottom:4px}")
                .append(".sub{color:var(--muted);margin-bottom:16px}.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px}")
                .append(".tile{background:var(--tile);border:1px solid var(--line);border-radius:8px;padding:10px 12px}.tile .k{font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}.tile .v{font-size:20px;font-weight:600}")
                .append(".dag{overflow-x:auto;border:1px solid var(--line);border-radius:8px;padding:8px;background:var(--tile)}table{border-collapse:collapse;width:100%;font-size:13px}th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;vertical-align:top}th{background:var(--tile)}")
                .append("td.approve{color:#1a7f37;font-weight:600}td.reject{color:#cf222e;font-weight:600}td.pending{color:#9a6700}")
                .append(".actor{font-size:11px;padding:1px 6px;border-radius:10px;background:#eaeef2}.actor.human{background:#fff8c5}.actor.policy{background:#ffebe9}.actor.agent{background:#ddf4ff}.actor.scenario{background:#fbefff}")
                .append("code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;white-space:pre-wrap;word-break:break-word}details summary{cursor:pointer;color:var(--muted)}")
                .append("pre.md{white-space:pre-wrap;background:var(--tile);border:1px solid var(--line);border-radius:8px;padding:12px;font-size:12.5px}.legend span{display:inline-block;margin-right:12px}.legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:4px;vertical-align:middle}")
                .append("</style></head><body><main>\n");
        h.append("<h1>").append(esc(scenario.title())).append("</h1>\n<div class=\"sub\">Run <code>").append(esc(s.runId)).append("</code> · scenario <b>").append(esc(scenario.name())).append("</b> (").append(esc(scenario.kind())).append(") · ").append(esc(s.createdAt)).append(" → ").append(esc(s.updatedAt)).append("</div>\n<div class=\"tiles\">");
        for (String[] t : tiles) h.append("<div class=\"tile\"><div class=\"k\">").append(esc(t[0])).append("</div><div class=\"v\">").append(esc(t[1])).append("</div></div>");
        h.append("</div>\n<h2>Workflow graph</h2>\n<div class=\"legend\">");
        COLOR.forEach((k, c) -> h.append("<span><i style=\"background:").append(c).append("\"></i>").append(k).append("</span>"));
        h.append("</div>\n<div class=\"dag\">").append(renderDag(graph, s.stages)).append("</div>\n");
        h.append("<h2>Requirement</h2>\n<p>").append(esc(s.requirement)).append("</p>\n");
        if (!s.notices.isEmpty()) h.append("<p><b>Notices:</b> ").append(String.join(" · ", s.notices.stream().map(HtmlReport::esc).toList())).append("</p>\n");
        h.append("<h2>Human checkpoints</h2>\n");
        if (s.approvals.isEmpty()) h.append("<p>No checkpoints reached.</p>\n");
        else {
            h.append("<table><thead><tr><th>Gate</th><th>Stage</th><th>Risk</th><th>Decision</th><th>By</th><th>Note / answers</th></tr></thead><tbody>");
            for (ApprovalRecord a : s.approvals.values()) {
                String decision = a.decision == null ? "pending" : a.decision.name().toLowerCase();
                h.append("<tr><td>").append(esc(a.gateId)).append("</td><td>").append(esc(a.stageId)).append("</td><td>").append(a.spec.riskLevel().name().toLowerCase()).append("</td><td class=\"").append(decision).append("\">").append(decision)
                        .append("</td><td>").append(esc(a.decidedBy == null ? "-" : a.decidedBy)).append("</td><td>").append(esc((a.note == null ? "" : a.note) + (a.answers == null ? "" : " — " + Json.compact(a.answers)))).append("</td></tr>");
            }
            h.append("</tbody></table>\n");
        }
        h.append("<h2>Full report</h2>\n<details open><summary>Markdown report (same content as report.md)</summary><pre class=\"md\">").append(esc(md)).append("</pre></details>\n");
        h.append("<h2>Audit trail (").append(events.size()).append(" events)</h2>\n<details><summary>Show every event</summary>\n<table><thead><tr><th>#</th><th>Time</th><th>Actor</th><th>Type</th><th>Stage</th><th>Payload</th></tr></thead><tbody>");
        for (RunEvent e : events) {
            String actor = e.actor().name().toLowerCase();
            String payload = e.payload() == null ? "{}" : Json.compact(e.payload());
            h.append("<tr><td>").append(e.seq()).append("</td><td>").append(esc(e.ts().length() > 23 ? e.ts().substring(11, 23) : e.ts())).append("</td><td><span class=\"actor ").append(actor).append("\">").append(actor).append("</span></td><td>").append(esc(e.type())).append("</td><td>")
                    .append(esc(e.stageId() == null ? "" : e.stageId())).append(e.attempt() == null ? "" : " <small>a" + e.attempt() + "</small>").append("</td><td><code>").append(esc(payload.length() > 220 ? payload.substring(0, 220) : payload)).append("</code></td></tr>");
        }
        h.append("</tbody></table>\n</details>\n</main></body></html>\n");
        return h.toString();
    }

    /** Layered DAG layout: columns by longest-path depth, rows by order. Pure SVG. */
    static String renderDag(WorkflowGraph graph, Map<String, StageState> states) {
        List<StageDefinition> nodes = graph.all();
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (StageDefinition n : nodes) depth.put(n.id(), n.dependsOn().stream().mapToInt(d -> depth.getOrDefault(d, 0) + 1).max().orElse(0));
        Map<Integer, List<String>> columns = new LinkedHashMap<>();
        for (StageDefinition n : nodes) columns.computeIfAbsent(depth.get(n.id()), k -> new ArrayList<>()).add(n.id());
        int w = 168, hh = 54, gx = 60, gy = 18;
        Map<String, int[]> pos = new LinkedHashMap<>();
        int maxRows = 1;
        for (Map.Entry<Integer, List<String>> e : columns.entrySet()) {
            maxRows = Math.max(maxRows, e.getValue().size());
            for (int i = 0; i < e.getValue().size(); i++) pos.put(e.getValue().get(i), new int[] {20 + e.getKey() * (w + gx), 20 + i * (hh + gy)});
        }
        int width = 40 + columns.size() * (w + gx), height = 40 + maxRows * (hh + gy);
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height + "\" viewBox=\"0 0 " + width + " " + height + "\" font-family=\"ui-monospace, Menlo, monospace\" font-size=\"11\">");
        svg.append("<defs><marker id=\"arr\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L8,4 L0,8 z\" fill=\"#8c959f\"/></marker></defs>");
        for (StageDefinition n : nodes) {
            int[] to = pos.get(n.id());
            for (String d : n.dependsOn()) {
                int[] from = pos.get(d);
                int x1 = from[0] + w, y1 = from[1] + hh / 2, x2 = to[0], y2 = to[1] + hh / 2, cx = (x1 + x2) / 2;
                svg.append("<path d=\"M").append(x1).append(',').append(y1).append(" C").append(cx).append(',').append(y1).append(' ').append(cx).append(',').append(y2).append(' ').append(x2).append(',').append(y2).append("\" fill=\"none\" stroke=\"#8c959f\" stroke-width=\"1.5\" marker-end=\"url(#arr)\"/>");
            }
        }
        for (StageDefinition n : nodes) {
            int[] p = pos.get(n.id());
            StageState st = states.get(n.id());
            String status = st == null ? "pending" : st.status.name().toLowerCase().replace('_', '-');
            String c = COLOR.getOrDefault(status, "#8c959f");
            String label = n.id().length() > 22 ? n.id().substring(0, 21) + "…" : n.id();
            svg.append("<g><rect x=\"").append(p[0]).append("\" y=\"").append(p[1]).append("\" width=\"").append(w).append("\" height=\"").append(hh).append("\" rx=\"8\" fill=\"#fff\" stroke=\"").append(c).append("\" stroke-width=\"2\"").append(n.approval() != null ? " stroke-dasharray=\"6 3\"" : "").append("/>");
            svg.append("<text x=\"").append(p[0] + 10).append("\" y=\"").append(p[1] + 20).append("\" font-weight=\"600\" fill=\"#1f2328\">").append(esc(label)).append("</text>");
            svg.append("<text x=\"").append(p[0] + 10).append("\" y=\"").append(p[1] + 36).append("\" fill=\"#57606a\">").append(esc(n.agent().name().toLowerCase().replace('_', '-'))).append("</text>");
            svg.append("<text x=\"").append(p[0] + w - 10).append("\" y=\"").append(p[1] + 48).append("\" text-anchor=\"end\" fill=\"").append(c).append("\">").append(status).append(st != null && st.attempts > 0 ? " ×" + st.attempts : "").append("</text></g>");
        }
        return svg.append("</svg>").toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}

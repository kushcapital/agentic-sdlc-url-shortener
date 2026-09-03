package dev.rajeev.orchestrator.core;

import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.core.Types.PatchFile;
import dev.rajeev.orchestrator.core.Types.PolicyVerdict;
import dev.rajeev.orchestrator.core.Types.RiskLevel;
import dev.rajeev.orchestrator.core.Types.Verdict;
import dev.rajeev.orchestrator.tools.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic guardrails evaluated on every code patch before it touches a worktree. Rules never
 * call a model — a guardrail that can be talked out of its decision is not a guardrail (ADR-0005).
 *
 * Verdicts: ALLOW → proceed; REQUIRE_APPROVAL → proceed only after a human approves; BLOCK → reject
 * the patch (the stage retries with the message as feedback). A rule that throws is a BLOCK.
 */
public final class PolicyEngine {

    public record Context(CodePatch patch, Workspace workspace, String stageId, Map<String, Object> stageParams) {
        public String param(String key) {
            Object v = stageParams == null ? null : stageParams.get(key);
            return v == null ? null : v.toString();
        }
    }

    public interface Rule {
        String id();
        String description();
        /** @return a verdict, or null when the rule has nothing to say. */
        PolicyVerdict evaluate(Context ctx);
    }

    public record Config(List<String> allowedPathPrefixes, List<String> protectedPaths, int maxFiles, int maxLines, int maxDeletes, boolean requireTestChangesForSrc) {
        public static final Config DEFAULT = new Config(
                List.of("src/", "docs/", "README.md", "CHANGELOG.md", "pom.xml", "openapi.yaml"),
                List.of("src/main/java/dev/rajeev/shortener/domain/UrlPolicy.java", "src/main/java/dev/rajeev/shortener/web/ApiExceptionHandler.java"),
                20, 1500, 2, true);

        public Config withProtectedPaths(List<String> paths) { return new Config(allowedPathPrefixes, paths, maxFiles, maxLines, maxDeletes, requireTestChangesForSrc); }
        public Config withBudget(int files, int lines) { return new Config(allowedPathPrefixes, protectedPaths, files, lines, maxDeletes, requireTestChangesForSrc); }
    }

    public record Outcome(List<PolicyVerdict> verdicts, Verdict outcome, RiskLevel riskLevel) {}

    private record SecretPattern(Pattern pattern, String label) {}

    private static final List<SecretPattern> SECRETS = List.of(
            new SecretPattern(Pattern.compile("AKIA[0-9A-Z]{16}"), "AWS access key id"),
            new SecretPattern(Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"), "Anthropic API key"),
            new SecretPattern(Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"), "private key block"),
            new SecretPattern(Pattern.compile("(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*[\"'][A-Za-z0-9_\\-/+]{16,}[\"']"), "hard-coded credential"),
            new SecretPattern(Pattern.compile("ghp_[A-Za-z0-9]{36}"), "GitHub token"));

    private static final Pattern DANGER = Pattern.compile("(rm\\s+-rf\\s+[/~]|curl\\s+[^|\\n]*\\|\\s*(ba)?sh|Runtime\\.getRuntime\\(\\)\\.exec\\(|new\\s+ProcessBuilder\\()");

    private final List<Rule> rules;

    public PolicyEngine() {
        this(Config.DEFAULT);
    }

    public PolicyEngine(Config cfg) {
        this.rules = buildRules(cfg);
    }

    public PolicyEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    public Outcome evaluate(Context ctx) {
        List<PolicyVerdict> verdicts = new ArrayList<>();
        for (Rule r : rules) {
            try {
                PolicyVerdict v = r.evaluate(ctx);
                if (v != null) verdicts.add(v);
            } catch (RuntimeException e) {
                verdicts.add(new PolicyVerdict(r.id(), Verdict.BLOCK, "rule raised: " + e.getMessage(), RiskLevel.HIGH, null));
            }
        }
        Verdict outcome = verdicts.stream().anyMatch(v -> v.verdict() == Verdict.BLOCK) ? Verdict.BLOCK
                : verdicts.stream().anyMatch(v -> v.verdict() == Verdict.REQUIRE_APPROVAL) ? Verdict.REQUIRE_APPROVAL : Verdict.ALLOW;
        RiskLevel risk = verdicts.stream().anyMatch(v -> v.riskLevel() == RiskLevel.HIGH) ? RiskLevel.HIGH
                : verdicts.stream().anyMatch(v -> v.riskLevel() == RiskLevel.MEDIUM) ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new Outcome(verdicts, outcome, risk);
    }

    private static PolicyVerdict v(String rule, Verdict verdict, String message, RiskLevel risk, Object evidence) {
        return new PolicyVerdict(rule, verdict, message, risk, evidence);
    }

    private static Rule rule(String id, String description, java.util.function.Function<Context, PolicyVerdict> fn) {
        return new Rule() {
            @Override public String id() { return id; }
            @Override public String description() { return description; }
            @Override public PolicyVerdict evaluate(Context ctx) { return fn.apply(ctx); }
        };
    }

    private static boolean isTestFile(String path) {
        return path.startsWith("src/test/") || path.matches(".*(Test|IT)\\.java$");
    }

    private static boolean isSourceFile(String path) {
        return path.startsWith("src/main/") && path.endsWith(".java");
    }

    public static List<Rule> buildRules(Config cfg) {
        return List.of(
                rule("path-allowlist", "Patches may only touch source, tests, docs and declared build files.", ctx -> {
                    List<String> bad = ctx.patch().files().stream().map(PatchFile::path)
                            .filter(p -> cfg.allowedPathPrefixes().stream().noneMatch(a -> p.equals(a) || p.startsWith(a)))
                            .toList();
                    return bad.isEmpty() ? null : v("path-allowlist", Verdict.BLOCK, "patch touches files outside the allowlist: " + String.join(", ", bad), RiskLevel.HIGH, bad);
                }),
                rule("no-secrets", "No credentials or private keys may be written to the workspace.", ctx -> {
                    for (PatchFile f : ctx.patch().files()) {
                        if (f.content() == null) continue;
                        for (SecretPattern s : SECRETS) {
                            if (s.pattern().matcher(f.content()).find()) return v("no-secrets", Verdict.BLOCK, s.label() + " detected in " + f.path(), RiskLevel.HIGH, Map.of("file", f.path()));
                        }
                    }
                    return null;
                }),
                rule("no-dangerous-code", "No destructive shell commands, process spawning or network exfiltration in source or scripts.", ctx -> {
                    for (PatchFile f : ctx.patch().files()) {
                        if (f.content() != null && (f.path().endsWith(".java") || f.path().endsWith(".sh") || f.path().endsWith(".xml")) && DANGER.matcher(f.content()).find()) {
                            return v("no-dangerous-code", Verdict.BLOCK, "dangerous command pattern in " + f.path(), RiskLevel.HIGH, Map.of("file", f.path()));
                        }
                    }
                    return null;
                }),
                rule("dependency-change-requires-approval", "Adding or changing Maven dependencies is a supply-chain decision; a human signs off.", ctx -> {
                    PatchFile pom = ctx.patch().files().stream().filter(f -> f.path().equals("pom.xml") && !f.isDelete()).findFirst().orElse(null);
                    if (pom == null || pom.content() == null) return null;
                    List<String> before = ctx.workspace().exists("pom.xml") ? PomDependencies.coordinates(ctx.workspace().read("pom.xml")) : List.of();
                    List<String> after = PomDependencies.coordinates(pom.content());
                    List<String> changed = new ArrayList<>();
                    for (String a : after) if (!before.contains(a)) changed.add("+" + a);
                    for (String b : before) if (!after.contains(b)) changed.add("-" + b);
                    return changed.isEmpty() ? null : v("dependency-change-requires-approval", Verdict.REQUIRE_APPROVAL, "dependency changes detected: " + String.join(", ", changed), RiskLevel.HIGH, changed);
                }),
                rule("protected-files-require-approval", "Security-sensitive modules (URL policy, HTTP error mapping) need a reviewer's approval.", ctx -> {
                    List<String> hits = ctx.patch().files().stream().map(PatchFile::path).filter(cfg.protectedPaths()::contains).toList();
                    return hits.isEmpty() ? null : v("protected-files-require-approval", Verdict.REQUIRE_APPROVAL, "patch modifies protected files: " + String.join(", ", hits), RiskLevel.MEDIUM, hits);
                }),
                rule("change-budget", "Large patches are hard to review; beyond the budget a human decides.", ctx -> {
                    Workspace.DiffStats stats = ctx.workspace().diffStats(ctx.patch());
                    int lines = stats.linesAdded() + stats.linesRemoved();
                    if (stats.files() <= cfg.maxFiles() && lines <= cfg.maxLines()) return null;
                    return v("change-budget", Verdict.REQUIRE_APPROVAL, "patch exceeds change budget (" + stats.files() + " files, " + lines + " lines)", RiskLevel.MEDIUM, Map.of("files", stats.files(), "lines", lines));
                }),
                rule("bounded-deletes", "Mass deletion is never autonomous.", ctx -> {
                    List<String> deletes = ctx.patch().files().stream().filter(PatchFile::isDelete).map(PatchFile::path).toList();
                    return deletes.size() <= cfg.maxDeletes() ? null : v("bounded-deletes", Verdict.BLOCK, "patch deletes " + deletes.size() + " files (max " + cfg.maxDeletes() + ")", RiskLevel.HIGH, deletes);
                }),
                rule("tests-accompany-source", "Source changes must ship with test changes, unless the stage is verified green against an existing acceptance suite (TDD: tests came first).", ctx -> {
                    if (!cfg.requireTestChangesForSrc()) return null;
                    if ("tests-green".equals(ctx.param("verify"))) return null;
                    boolean touchesSrc = ctx.patch().files().stream().anyMatch(f -> isSourceFile(f.path()));
                    boolean touchesTest = ctx.patch().files().stream().anyMatch(f -> isTestFile(f.path()));
                    return !touchesSrc || touchesTest ? null : v("tests-accompany-source", Verdict.REQUIRE_APPROVAL, "source changed without any test change", RiskLevel.MEDIUM, null);
                }));
    }

    /** Minimal dependency extraction from a pom: groupId:artifactId[:version] of every &lt;dependency&gt;. */
    public static final class PomDependencies {
        private static final Pattern DEP = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
        private static final Pattern TAG = Pattern.compile("<(groupId|artifactId|version|scope)>\\s*([^<]+?)\\s*</\\1>");

        private PomDependencies() {}

        public static List<String> coordinates(String pom) {
            List<String> out = new ArrayList<>();
            var m = DEP.matcher(pom);
            while (m.find()) {
                String g = null, a = null, ver = null;
                var t = TAG.matcher(m.group(1));
                while (t.find()) {
                    switch (t.group(1)) {
                        case "groupId" -> g = t.group(2);
                        case "artifactId" -> a = t.group(2);
                        case "version" -> ver = t.group(2);
                        default -> { }
                    }
                }
                if (g != null && a != null) out.add(g + ":" + a + (ver != null ? ":" + ver : ""));
            }
            return out;
        }
    }
}

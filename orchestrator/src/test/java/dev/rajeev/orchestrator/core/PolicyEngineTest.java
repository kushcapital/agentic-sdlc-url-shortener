package dev.rajeev.orchestrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.core.Types.PatchFile;
import dev.rajeev.orchestrator.core.Types.PolicyVerdict;
import dev.rajeev.orchestrator.core.Types.RiskLevel;
import dev.rajeev.orchestrator.core.Types.Verdict;
import dev.rajeev.orchestrator.tools.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final String POM = "<project><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>";
    private final PolicyEngine engine = new PolicyEngine();
    private Workspace ws;

    @BeforeEach
    void setUp() throws IOException {
        ws = new Workspace(Files.createTempDirectory("policy-"));
        ws.write("pom.xml", POM);
        ws.write("src/main/java/A.java", "class A {}\n");
    }

    static CodePatch patch(PatchFile... files) {
        return new CodePatch("t", null, List.of(files), null, null);
    }

    static PatchFile write(String path, String content) {
        return new PatchFile(path, "write", content);
    }

    PolicyEngine.Outcome eval(CodePatch p) {
        return engine.evaluate(new PolicyEngine.Context(p, ws, "s", Map.of()));
    }

    @Test
    void allowsANormalSourcePlusTestPatch() {
        PolicyEngine.Outcome r = eval(patch(write("src/main/java/B.java", "class B {}"), write("src/test/java/BTest.java", "class BTest {}")));
        assertEquals(Verdict.ALLOW, r.outcome());
        assertTrue(r.verdicts().isEmpty());
    }

    @Test
    void blocksWritesOutsideTheAllowlistIncludingPathTraversal() {
        for (String path : List.of(".env.local", ".github/workflows/x.yml", "../escape.java", "target/x.class", "scripts/deploy.sh")) {
            PolicyEngine.Outcome r = eval(patch(write(path, "x")));
            assertEquals(Verdict.BLOCK, r.outcome(), path);
            assertEquals("path-allowlist", r.verdicts().get(0).rule(), path);
        }
    }

    @Test
    void blocksSecretsAndDangerousCode() {
        String key = "AKIA" + "ABCDEFGHIJKLMNOP";
        assertTrue(eval(patch(write("src/main/java/K.java", "String k = \"" + key + "\";"), write("src/test/java/KTest.java", ""))).verdicts().stream().map(PolicyVerdict::rule).toList().contains("no-secrets"));
        assertEquals(Verdict.BLOCK, eval(patch(write("src/main/java/K.java", "String pem = \"-----BEGIN RSA PRIVATE KEY-----\";"), write("src/test/java/KTest.java", ""))).outcome());
        assertTrue(eval(patch(write("src/main/java/X.java", "new ProcessBuilder(\"rm\", \"-rf\", \"/\")"), write("src/test/java/XTest.java", ""))).verdicts().stream().map(PolicyVerdict::rule).toList().contains("no-dangerous-code"));
    }

    @Test
    void requiresApprovalForDependencyChangesReportingWhatChanged() {
        String pom2 = POM.replace("</dependencies>", "<dependency><groupId>com.example</groupId><artifactId>leftpad</artifactId><version>1.0</version></dependency></dependencies>");
        PolicyEngine.Outcome r = eval(patch(write("pom.xml", pom2)));
        assertEquals(Verdict.REQUIRE_APPROVAL, r.outcome());
        assertEquals(RiskLevel.HIGH, r.riskLevel());
        assertEquals("dependency-change-requires-approval", r.verdicts().get(0).rule());
        assertEquals(List.of("+com.example:leftpad:1.0"), r.verdicts().get(0).evidence());
        assertEquals(Verdict.ALLOW, eval(patch(write("pom.xml", POM.replace("<project>", "<project><name>x</name>")))).outcome());
    }

    @Test
    void requiresApprovalForProtectedFilesAndUntestedSourceUnlessVerifiedGreen() {
        String protectedFile = PolicyEngine.Config.DEFAULT.protectedPaths().get(0);
        assertEquals(List.of("protected-files-require-approval"), eval(patch(write(protectedFile, "x"), write("src/test/java/T.java", ""))).verdicts().stream().map(PolicyVerdict::rule).toList());
        CodePatch noTests = patch(write("src/main/java/A.java", "class A { int x; }"));
        assertEquals(List.of("tests-accompany-source"), eval(noTests).verdicts().stream().map(PolicyVerdict::rule).toList());
        assertEquals(Verdict.ALLOW, engine.evaluate(new PolicyEngine.Context(noTests, ws, "s", Map.of("verify", "tests-green"))).outcome());
    }

    @Test
    void enforcesChangeBudgetAndBoundedDeletes() {
        PatchFile[] many = new PatchFile[26];
        for (int i = 0; i < 25; i++) many[i] = write("src/main/java/F" + i + ".java", "x");
        many[25] = write("src/test/java/T.java", "");
        assertEquals(List.of("change-budget"), eval(patch(many)).verdicts().stream().map(PolicyVerdict::rule).toList());
        assertEquals(Verdict.BLOCK, eval(patch(new PatchFile("src/main/java/1.java", "delete", null), new PatchFile("src/main/java/2.java", "delete", null), new PatchFile("src/main/java/3.java", "delete", null))).outcome());
    }

    @Test
    void blockWinsOverApprovalAndRiskIsTheMax() {
        PolicyEngine.Outcome r = eval(patch(write(PolicyEngine.Config.DEFAULT.protectedPaths().get(0), "x"), write(".env", "x")));
        assertEquals(Verdict.BLOCK, r.outcome());
        assertEquals(RiskLevel.HIGH, r.riskLevel());
    }

    @Test
    void isConfigurable() {
        PolicyEngine custom = new PolicyEngine(new PolicyEngine.Config(List.of("src/"), List.of(), 20, 1500, 2, false));
        assertEquals(Verdict.ALLOW, custom.evaluate(new PolicyEngine.Context(patch(write("src/main/java/A.java", "x")), ws, "s", Map.of())).outcome());
        assertEquals(List.of("org.springframework.boot:spring-boot-starter-web"), PolicyEngine.PomDependencies.coordinates(POM));
    }
}

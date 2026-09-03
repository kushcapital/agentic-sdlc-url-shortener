package dev.rajeev.orchestrator.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JUnitXmlTest {

    @Test
    void parsesSurefireStyleReportsIncludingErrorsAndNestedSuites() throws IOException {
        Path dir = Files.createTempDirectory("reports-");
        Files.writeString(dir.resolve("TEST-a.xml"), """
                <testsuite name="A" tests="3" failures="1" errors="1" skipped="0">
                  <testcase classname="A" name="ok"/>
                  <testcase classname="A" name="bad"><failure message="expected 1 but was 2">trace</failure></testcase>
                  <testcase classname="A" name="boom"><error message="NPE"/></testcase>
                </testsuite>""");
        Files.writeString(dir.resolve("TEST-b.xml"), """
                <testsuites><testsuite name="B" tests="2" failures="0" errors="0" skipped="1">
                  <testcase classname="B" name="ok"/><testcase classname="B" name="skipped"><skipped/></testcase>
                </testsuite></testsuites>""");
        JUnitXml.Counts c = JUnitXml.parseDirectory(dir);
        assertEquals(5, c.total());
        assertEquals(2, c.failed());
        assertEquals(1, c.skipped());
        assertEquals(2, c.passed());
        assertEquals("A.bad", c.failures().get(0).name());
        assertEquals("expected 1 but was 2", c.failures().get(0).message());
        assertEquals(0, JUnitXml.parseDirectory(dir.resolve("missing")).total());
    }

    @Test
    void rootPackageIsTheShallowestPackageWithTests() throws IOException {
        Path src = Files.createTempDirectory("src-");
        Files.createDirectories(src.resolve("dev/rajeev/a"));
        Files.createDirectories(src.resolve("dev/rajeev/b/c"));
        Files.writeString(src.resolve("dev/rajeev/a/ATest.java"), "");
        Files.writeString(src.resolve("dev/rajeev/b/c/CTest.java"), "");
        assertEquals("dev.rajeev", JavacToolchain.rootPackage(src));
    }
}

package dev.rajeev.orchestrator.tools;

import dev.rajeev.orchestrator.tools.Toolchain.Failure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses JUnit-style XML reports: Maven surefire ({@code target/surefire-reports/TEST-*.xml}) and the
 * JUnit console launcher's legacy reports ({@code TEST-junit-jupiter.xml}). No XML library beyond the JDK.
 */
public final class JUnitXml {

    public record Counts(int total, int failed, int skipped, List<Failure> failures) {
        public int passed() { return total - failed - skipped; }
    }

    private JUnitXml() {}

    public static Counts parseDirectory(Path dir) {
        int total = 0, failed = 0, skipped = 0;
        List<Failure> failures = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new Counts(0, 0, 0, failures);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                Counts c = parseFile(f);
                total += c.total();
                failed += c.failed();
                skipped += c.skipped();
                failures.addAll(c.failures());
            }
        } catch (IOException e) {
            return new Counts(0, 0, 0, List.of(new Failure("reports", "cannot read " + dir + ": " + e.getMessage())));
        }
        return new Counts(total, failed, skipped, failures);
    }

    public static Counts parseFile(Path file) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder().parse(file.toFile());
            NodeList suites = doc.getElementsByTagName("testsuite");
            int total = 0, failed = 0, skipped = 0;
            List<Failure> failures = new ArrayList<>();
            for (int i = 0; i < suites.getLength(); i++) {
                Element suite = (Element) suites.item(i);
                if (suite.getParentNode() instanceof Element parent && parent.getTagName().equals("testsuite")) continue; // nested: counted by parent
                total += intAttr(suite, "tests");
                failed += intAttr(suite, "failures") + intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
            }
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                for (String tag : List.of("failure", "error")) {
                    NodeList fl = tc.getElementsByTagName(tag);
                    if (fl.getLength() > 0) {
                        Element fe = (Element) fl.item(0);
                        String msg = fe.getAttribute("message");
                        if (msg == null || msg.isBlank()) msg = fe.getTextContent();
                        String name = tc.getAttribute("classname") + "." + tc.getAttribute("name");
                        failures.add(new Failure(name, msg == null ? "" : msg.strip().lines().findFirst().orElse("").substring(0, Math.min(500, msg.strip().lines().findFirst().orElse("").length()))));
                    }
                }
            }
            return new Counts(total, failed, skipped, failures);
        } catch (Exception e) {
            return new Counts(0, 0, 0, List.of(new Failure(file.getFileName().toString(), "unparseable report: " + e.getMessage())));
        }
    }

    private static int intAttr(Element e, String name) {
        String v = e.getAttribute(name);
        if (v == null || v.isBlank()) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}

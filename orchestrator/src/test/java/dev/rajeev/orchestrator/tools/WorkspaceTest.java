package dev.rajeev.orchestrator.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.orchestrator.core.OrchestrationException;
import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.core.Types.PatchFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

    static Path tmp() throws IOException {
        return Files.createTempDirectory("ws-");
    }

    static CodePatch patch(PatchFile... files) {
        return new CodePatch("t", null, List.of(files), null, null);
    }

    @Test
    void refusesPathsThatEscapeTheSandbox() throws IOException {
        Workspace w = new Workspace(tmp());
        assertThrows(OrchestrationException.class, () -> w.write("../outside.txt", "x"));
        assertThrows(OrchestrationException.class, () -> w.read("/etc/passwd"));
        assertThrows(OrchestrationException.class, () -> w.resolve("a/../../b"));
    }

    @Test
    void appliesPatchesListsFilesAndComputesDiffStats() throws IOException {
        Workspace w = new Workspace(tmp());
        w.write("src/a.txt", "line1\nline2\n");
        Workspace.DiffStats stats = w.diffStats(patch(new PatchFile("src/a.txt", "write", "line1\nline3\n"), new PatchFile("src/new.txt", "write", "x\ny\n")));
        assertEquals(2, stats.files());
        assertEquals(3, stats.linesAdded());
        assertEquals(1, stats.linesRemoved());
        assertTrue(stats.perFile().get(1).isNew());
        Workspace.Applied a = w.apply(patch(new PatchFile("src/new.txt", "write", "x"), new PatchFile("src/a.txt", "delete", null)));
        assertEquals(List.of("src/new.txt"), a.written());
        assertEquals(List.of("src/a.txt"), a.deleted());
        assertEquals(List.of("src/new.txt"), w.list());
    }

    @Test
    void snapshotAndRestoreRoundTrip() throws IOException {
        Workspace w = new Workspace(tmp());
        w.write("a.txt", "one");
        Path snaps = tmp();
        w.snapshot(snaps, "s1");
        w.write("a.txt", "two");
        w.write("b.txt", "extra");
        w.restore(snaps.resolve("s1"));
        assertEquals("one", w.read("a.txt"));
        assertFalse(w.exists("b.txt"));
        assertThrows(OrchestrationException.class, () -> w.restore(snaps.resolve("missing")));
    }

    @Test
    void forkAndMergeDetectConflictsAtFileLevel() throws IOException {
        Workspace main = new Workspace(tmp());
        main.write("src/a.txt", "a");
        main.write("src/b.txt", "b");
        Map<String, String> base = main.allHashes();
        Workspace branch1 = main.fork(tmp());
        Workspace branch2 = main.fork(tmp());
        CodePatch p1 = patch(new PatchFile("src/a.txt", "write", "a1"));
        CodePatch p2 = patch(new PatchFile("src/b.txt", "write", "b2"));
        CodePatch p3 = patch(new PatchFile("src/a.txt", "write", "a3"));
        branch1.apply(p1);
        branch2.apply(p2);
        assertEquals(List.of("src/a.txt"), main.merge(p1, base).merged());
        assertEquals(List.of("src/b.txt"), main.merge(p2, base).merged()); // disjoint: fine
        assertEquals(List.of("src/a.txt"), main.merge(p3, base).conflicts()); // a.txt changed since fork
        assertEquals("a1", main.read("src/a.txt"));
        assertTrue(main.merge(patch(new PatchFile("src/c.txt", "write", "c")), base).conflicts().isEmpty()); // new file on both sides
    }
}

package dev.rajeev.orchestrator.tools;

import dev.rajeev.orchestrator.core.Json;
import dev.rajeev.orchestrator.core.OrchestrationException;
import dev.rajeev.orchestrator.core.Types.CodePatch;
import dev.rajeev.orchestrator.core.Types.PatchFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Sandboxed workspace. Every agent file operation goes through here, and every path is resolved and
 * checked to stay inside the sandbox root: an agent that emits "../../.env" as a patch path gets an
 * exception, not a write.
 *
 * Mutating stages run in a {@link #fork worktree} (a copy); their verified patch is {@link #merge merged}
 * back into the main tree only if nothing they touched changed since the fork (ADR-0004).
 */
public final class Workspace {

    private static final Set<String> SKIP = Set.of("node_modules", "target", "build", ".git", ".idea", ".m2");

    private final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Workspace seedFrom(Path source, Path target) {
        Workspace ws = new Workspace(target);
        copyTree(source.toAbsolutePath().normalize(), ws.root);
        return ws;
    }

    public Path root() { return root; }

    public Path resolve(String relPath) {
        Path abs = root.resolve(relPath).normalize();
        if (!abs.startsWith(root) || abs.equals(root) && !relPath.isEmpty() && !relPath.equals(".")) {
            if (!abs.startsWith(root)) throw new OrchestrationException("path '" + relPath + "' escapes the sandbox", OrchestrationException.Kind.POLICY, false);
        }
        return abs;
    }

    public boolean exists(String relPath) {
        return Files.exists(resolve(relPath));
    }

    public String read(String relPath) {
        try {
            return Files.readString(resolve(relPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(String relPath, String content) {
        try {
            Path abs = resolve(relPath);
            Files.createDirectories(abs.getParent());
            Files.writeString(abs, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String relPath) {
        try {
            Files.deleteIfExists(resolve(relPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Files under the root, relative with '/' separators, sorted; skips build output and dependency folders. */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return out.stream().sorted().toList();
    }

    private void walk(Path dir, List<String> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : children.toList()) {
                if (SKIP.contains(p.getFileName().toString())) continue;
                if (Files.isDirectory(p)) walk(p, out);
                else out.add(root.relativize(p).toString().replace('\\', '/'));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Applied(List<String> written, List<String> deleted) {}

    public Applied apply(CodePatch patch) {
        List<String> written = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (PatchFile f : patch.files()) {
            if (f.isDelete()) {
                delete(f.path());
                deleted.add(f.path());
            } else {
                if (f.content() == null) throw new OrchestrationException("patch file '" + f.path() + "' has no content", OrchestrationException.Kind.AGENT, true);
                write(f.path(), f.content());
                written.add(f.path());
            }
        }
        return new Applied(written, deleted);
    }

    public record FileDiff(String path, int added, int removed, boolean isNew) {}

    public record DiffStats(int files, int linesAdded, int linesRemoved, List<FileDiff> perFile) {}

    /** Line-level diff summary for change budgets — cheap and dependency-free. */
    public DiffStats diffStats(CodePatch patch) {
        List<FileDiff> per = new ArrayList<>();
        for (PatchFile f : patch.files()) {
            List<String> before = exists(f.path()) ? lines(read(f.path())) : List.of();
            List<String> after = f.isDelete() ? List.of() : lines(f.content() == null ? "" : f.content());
            Set<String> beforeSet = new HashSet<>(before);
            Set<String> afterSet = new HashSet<>(after);
            int added = (int) after.stream().filter(l -> !beforeSet.contains(l)).count();
            int removed = (int) before.stream().filter(l -> !afterSet.contains(l)).count();
            per.add(new FileDiff(f.path(), added, removed, before.isEmpty()));
        }
        return new DiffStats(per.size(), per.stream().mapToInt(FileDiff::added).sum(), per.stream().mapToInt(FileDiff::removed).sum(), per);
    }

    private static List<String> lines(String text) {
        List<String> l = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        return l;
    }

    /** Isolated worktree for a mutating stage: a copy of the current tree. */
    public Workspace fork(Path targetDir) {
        return seedFrom(root, targetDir);
    }

    /** Content hash of a file (null when absent) — used for merge conflict detection. */
    public String fileHash(String relPath) {
        if (!exists(relPath)) return null;
        try {
            return Json.sha256(Files.readAllBytes(resolve(relPath)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, String> allHashes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String f : list()) out.put(f, fileHash(f));
        return out;
    }

    public record MergeResult(List<String> merged, List<String> conflicts) {}

    /**
     * Merge a verified patch from a worktree into this (main) tree. {@code baseHashes} are the main
     * tree's file hashes when the worktree was forked; if any touched file changed in main since then,
     * another branch got there first and the merge is refused — the caller retries on a fresh fork.
     */
    public MergeResult merge(CodePatch patch, Map<String, String> baseHashes) {
        List<String> conflicts = new ArrayList<>();
        for (PatchFile f : patch.files()) {
            String base = baseHashes == null ? null : baseHashes.get(f.path());
            String now = fileHash(f.path());
            if (base == null ? now != null : !base.equals(now)) conflicts.add(f.path());
        }
        if (!conflicts.isEmpty()) return new MergeResult(List.of(), conflicts);
        Applied a = apply(patch);
        List<String> merged = new ArrayList<>(a.written());
        merged.addAll(a.deleted());
        return new MergeResult(merged, List.of());
    }

    public Path snapshot(Path snapshotsDir, String id) {
        Path dest = snapshotsDir.resolve(id);
        deleteTree(dest);
        copyTree(root, dest);
        return dest;
    }

    public void restore(Path snapshotDir) {
        if (!Files.isDirectory(snapshotDir)) throw new OrchestrationException("snapshot '" + snapshotDir + "' not found", OrchestrationException.Kind.TOOL, false);
        try (Stream<Path> children = Files.list(root)) {
            for (Path p : children.toList()) deleteTree(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        copyTree(snapshotDir, root);
    }

    public static void copyTree(Path source, Path target) {
        try {
            Files.createDirectories(target);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source) && SKIP.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteTree(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

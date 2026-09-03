# ADR-0004: Per-attempt worktrees with conflict-checked merge

**Status:** accepted · **Supersedes:** shared-sandbox execution (first implementation)

## Context
The planner can produce independent tasks, and the scheduler runs them concurrently on virtual threads. The first version applied every task's patch to one shared sandbox. On the first full brownfield run, T3 and T4 executed in parallel: T4's compile saw T3's half-applied (and failing) attempt, and T3's rollback removed T4's new file. Shared mutable state under concurrency — the same bug as two developers editing one working copy.

## Decision
Every attempt of a stage that mutates the workspace runs in its own worktree: a copy of the main sandbox taken at attempt start, with the main tree's file hashes recorded. Exit gates (policy, apply, compile/tests) run inside the worktree. On success the patch is merged into the main sandbox only if every touched file still has its fork-time hash; otherwise the merge is refused and the stage retries on a fresh fork. On failure the worktree is discarded; the main sandbox was never touched.

## Consequences
- Parallel implementation is safe; the main tree is always in a verified state; rollback of an attempt is a directory delete.
- Conflict detection is file-level. Two tasks touching the same file cannot both land; the later one re-forks. Line-level three-way merge (git worktrees) is the next step.
- Worktrees cost a copy per attempt (small here; prune at scale). `target/` and `build/` are excluded from the copy.

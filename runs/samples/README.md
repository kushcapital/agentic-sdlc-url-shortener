# Sample runs

Committed output of one run of each bundled scenario, so the orchestration can be reviewed without executing anything:

| Run | Read first | Then |
|---|---|---|
| `greenfield/` | `report.md` | `sandbox/` (the v0 Spring Boot service the agents produced — `mvn test` works in it), `events.jsonl` |
| `brownfield/` | `report.md` | `artifacts/ARCHITECTURE.v1.json` (impact analysis), `events.jsonl` (policy block, compile failure, rollback, protected-file approval, fork/merge) |
| `ambiguous/`  | `report.md` | `artifacts/REQUIREMENTS_SPEC.v1.json` vs `.v2.json`, `events.jsonl` (clarification, re-plan, collapse/re-expand, rejection) |

Each run directory contains:

- `report.md` / `report.html` — human-readable report with metrics, DAG, checkpoints, policy findings, lineage
- `events.jsonl` — append-only audit log, one JSON event per line (`seq`, `ts`, `actor`, `type`, `stageId`, `attempt`, `payload`)
- `state.json` — final run state (stages, artifacts with hashes and `inputHashes` lineage, approvals, metrics)
- `artifacts/` — every artifact version produced by an agent (`<kind>.v<N>.json`)
- `metrics.json` — reliability metrics (success rate, retries, rollbacks, MTTR, latency)
- `sandbox/` — the workspace after the run (the code the pipeline produced)
- `scenario.json` — the scenario definition the run was created from

`snapshots/`, `worktrees/` and `sandbox/target/` are not committed (rollback material and build output only).

These samples were produced with the `javac` toolchain (`--toolchain javac`), which is why stage durations are seconds rather than minutes. Regenerate with `./scripts/run-scenarios.sh` (writes to `runs/<scenario>-<timestamp>/`).

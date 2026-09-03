# Agentic SDLC orchestration — URL shortener (Java 21 / Spring Boot 3.4)

A working prototype of an **agentic software-engineering system**: an orchestration engine that takes a requirement through requirements → architecture → planning → implementation → testing → review → documentation → release readiness under explicit governance — a dependency graph with entry/exit gates, parallel branches with joins, human approval checkpoints, deterministic policy guardrails, bounded retries, worktree rollback, safe-stop and budgets, an append-only audit log with decision lineage, reliability metrics (success rate, retries, rollbacks, MTTR, latency), and dynamic re-planning when upstream outputs change — demonstrated on a production-style **URL shortener service** across three scenarios: greenfield, brownfield and ambiguous.

Both modules are Java 21 / Spring Boot 3.4.4 / Maven. Everything runs offline: when `ANTHROPIC_API_KEY` is present the agents reason with a live model; otherwise they replay authored, reviewable fixtures through the same engine — every gate, policy rule, compile, test run, retry, rollback and approval is real either way.

```bash
mvn verify                                                          # builds both modules, 122 tests
./scripts/orchestrate.sh run --scenario brownfield --auto-approve   # one end-to-end agentic run
```

## Deliverables map

| Assignment deliverable | Where |
|---|---|
| Working prototype (runnable end-to-end) | `url-shortener/` (service), `orchestrator/` (engine + CLI), `scenarios/` — `./scripts/run-scenarios.sh` |
| Architecture overview | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — components, orchestration model, control flow, key decisions, diagrams |
| Three scenarios (decomposition, orchestration, validation) | [`docs/SCENARIOS.md`](docs/SCENARIOS.md) + committed run output in [`runs/samples/`](runs/samples/README.md) |
| Setup instructions | this file, § Setup |
| Testing approach, limitations, trade-offs | [`docs/TESTING_AND_LIMITATIONS.md`](docs/TESTING_AND_LIMITATIONS.md) |
| Final engineering summary (plan, rationale, artifacts, risks, assumptions, limitations) | [`docs/ENGINEERING_SUMMARY.md`](docs/ENGINEERING_SUMMARY.md) |
| Architecture decision records | [`docs/adr/`](docs/adr/) |
| API contract (contract-first) | [`url-shortener/src/main/resources/static/openapi.yaml`](url-shortener/src/main/resources/static/openapi.yaml), served at `/openapi.yaml` |
| CI | [`.github/workflows/ci.yml`](.github/workflows/ci.yml), [`bitbucket-pipelines.yml`](bitbucket-pipelines.yml) |
| Submission checklist (what to run first, how to hand over) | [`docs/SUBMISSION.md`](docs/SUBMISSION.md) |

## Setup

Requirements: **JDK 21** and **Maven 3.9+**. No Docker, no database server — H2 runs embedded; PostgreSQL is a configuration change (`spring.datasource.url`).

```bash
git clone <this repo> && cd agentic-sdlc-url-shortener
mvn verify          # url-shortener: 94 tests (unit, contract, real-HTTP integration); orchestrator: 28 unit tests
```

Optional live-model mode: export `ANTHROPIC_API_KEY` (and optionally `ANTHROPIC_MODEL`, default `claude-sonnet-4-5`). Without a key the engine reports `llm: anthropic>scripted` and every fallback is a `provider.fallback` event in the audit log.

## Run it

### 1. The URL shortener service

```bash
mvn -pl url-shortener spring-boot:run          # http://localhost:8080, H2 file ./data/shortener.mv.db
curl -s -X POST localhost:8080/api/links -H 'content-type: application/json' \
     -d '{"url":"https://example.com/docs","customAlias":"docs-2026"}'
curl -si localhost:8080/docs-2026 | head -3    # 302 → https://example.com/docs, Cache-Control: no-store
curl -s localhost:8080/api/links/docs-2026/stats
curl -s localhost:8080/actuator/health/readiness ; curl -s localhost:8080/actuator/metrics/analytics.queue.flushed
```

Endpoints: `POST /api/links` (idempotent with `Idempotency-Key`), `GET /{code}` (302, no-store), `GET /api/links/{code}`, `DELETE /api/links/{code}` (soft delete → 410), `GET /api/links/{code}/stats`, actuator liveness/readiness/metrics, `/openapi.yaml`. Details in [`url-shortener/README.md`](url-shortener/README.md).

### 2. The orchestrator

```bash
./scripts/orchestrate.sh run --scenario greenfield --auto-approve     # empty sandbox → tested v0 Spring Boot service
./scripts/orchestrate.sh run --scenario brownfield --auto-approve     # add link expiry to the v1 service
./scripts/orchestrate.sh run --scenario ambiguous  --auto-approve     # "links feel too easy to guess"
./scripts/run-scenarios.sh                                            # all three
```

Each run writes `runs/<runId>/` with `report.md`, `report.html`, `events.jsonl` (audit log), `state.json`, `artifacts/`, `metrics.json` and the resulting `sandbox/` (a complete Maven project you can `mvn test`). Committed examples: [`runs/samples/`](runs/samples/README.md).

The orchestrator verifies every implementation step by compiling and testing the sandbox. By default it uses **Maven** (`mvn -o -q test-compile` / `mvn -o -q test`); after `mvn verify` has populated your local repository a brownfield run takes about five minutes. For a much faster inner loop, prepare the **javac** toolchain once (about a minute per run afterwards):

```bash
./scripts/setup-fast-toolchain.sh && source .orchestrator-env
```

`--auto-approve` only matters for checkpoints the scenario does not script (every such decision is stamped `auto-approver` in the audit log). To be the approver yourself:

```bash
./scripts/orchestrate.sh run --scenario brownfield            # pauses at the first unscripted checkpoint
./scripts/orchestrate.sh status <runId>
./scripts/orchestrate.sh approve <runId> design-review --decision approve --note "LGTM"
./scripts/orchestrate.sh approve <runId> clarify-requirements --answer AMB-1=B    # answer clarification questions
./scripts/orchestrate.sh resume <runId>
./scripts/orchestrate.sh stop <runId>                          # safe-stop from another shell (Ctrl-C works too)
./scripts/orchestrate.sh rollback <runId>                      # restore the sandbox to the initial snapshot
./scripts/orchestrate.sh report <runId>                        # regenerate report.md / report.html
./scripts/orchestrate.sh list
```

Flags: `--llm auto|anthropic|scripted` (default `auto`), `--toolchain maven|javac`, `--run-id <id>`, `--concurrency <n>`, `--quiet`. The same commands work as `java -jar orchestrator/target/orchestrator.jar …` from the repository root.

### 3. Tests

```bash
mvn verify                                                     # 122 fast tests
mvn -pl orchestrator test -Dorchestrator.scenarios=true        # + 6 end-to-end governance tests: runs every scenario through the real engine
                                                               #   (~5 min with the javac toolchain, longer with Maven as the sandbox toolchain)
```

## Repository layout

```
url-shortener/         Spring Boot service: web/ (controllers, advice, filters), domain/ (LinkService, UrlPolicy, codes),
                       repository/ (port + in-memory + JDBC for H2/PostgreSQL, versioned migration), analytics/ (bounded
                       write-behind queue), ops/ (readiness indicator); src/test: unit, contract, real-HTTP integration
orchestrator/          engine: core/ (WorkflowGraph, Orchestrator, Gates, PolicyEngine, Approvals, Controls, RunStore),
                       agents/ (8 specialists + artifact contracts), llm/ (Anthropic, scripted, fallback chain),
                       tools/ (sandboxed Workspace with worktrees, Maven + javac toolchains), report/, scenario/, cli/
scenarios/<name>/      scenario.json (requirement, scripted stakeholder decisions, injected revisions), authoring/ (agent
                       outputs as reviewable files), fixtures/ (generated: ./scripts/orchestrate.sh fixtures)
runs/samples/          committed output of one run per scenario
docs/                  architecture, scenarios, testing & limitations, engineering summary, ADRs
scripts/               orchestrate.sh, run-scenarios.sh, setup-fast-toolchain.sh, verify-without-maven.sh
```

## How to read a run in five minutes

1. `runs/samples/brownfield/report.md` — metrics strip, final DAG, stage timeline with lineage hashes, checkpoints, policy findings, failures/retries/rollbacks, then the artifacts in order.
2. `runs/samples/brownfield/events.jsonl` — grep for `policy.verdict`, `workspace.rollback`, `approval.`, `scheduler.parallel`, `workspace.merged`.
3. `scenarios/brownfield/authoring/` — the agent outputs as real Java files, including the deliberately faulty first attempts.
4. `orchestrator/src/main/java/dev/rajeev/orchestrator/core/Orchestrator.java` — the engine loop; `Gates.java` and `PolicyEngine.java` — the governance.

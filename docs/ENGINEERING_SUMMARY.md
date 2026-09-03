# Final engineering summary

## 1. Plan and rationale

**Goal.** Show an agentic execution model that turns a requirement into a reviewable engineering outcome — with the orchestration layer, not the agents, as the thing being evaluated. The assignment names workflow orchestration as the critical differentiator, so the plan put the engine first and treated the URL shortener as a realistic, non-trivial subject with enough surface (persistence, analytics, reliability, security policy) to make brownfield and ambiguous work meaningful.

**Sequence followed.**

1. Build the URL shortener to production standard: Spring Boot 3.4 / Java 21, hexagonal layering, JDBC persistence with a versioned migration, bounded write-behind analytics, rate limiting, idempotency, request correlation, actuator probes, contract-first OpenAPI, 94 tests including real-server integration tests. Without a real codebase, "codebase reasoning" and "brownfield" are theatre.
2. Build the engine as generic machinery: graph, scheduler on virtual threads, gates, policy, approvals, controls, event-sourced store, metrics. No knowledge of URL shorteners anywhere in `core/`.
3. Define the SDLC workflow as data (`SdlcWorkflow`) and eight agents with record contracts.
4. Put a provider seam in front of the model so the whole thing runs offline and in CI, with a live provider that drops in when a key exists.
5. Author the three scenarios as real Java files, including the mistakes — and make them the end-to-end suite.
6. Write the documentation from the artifacts the runs produced, not the other way round.

**What changed along the way (recorded in the ADRs).** The first version of the engine ran parallel implementation tasks on one shared sandbox. The brownfield scenario broke it on the first full run: T4's compile saw T3's half-applied attempt, and T3's rollback removed T4's file. The fix was per-attempt worktrees with conflict-checked merge — a design change forced by evidence, not foresight. Likewise the `tests-accompany-source` rule was too strict for TDD flows and gained a verified-green exemption; re-planning initially failed to collapse a dynamic expansion whose nodes were still pending; and the "guessability" hardening exposed that the alias regex was reused in the controllers' path templates, which would have 404'd legacy aliases — the split into creation and resolution rules is now ADR-0004 of the sandboxed service. Each of these has a test now.

**Why Java 21 / Spring Boot.** It is the target organisation's house stack and the one the author can defend line by line. The engine uses Java 21 features where they earn their keep (records and sealed types for artifacts and results, virtual threads for parallel stages, switch expressions with exhaustiveness — which the brownfield scenario turns into a demonstration). Spring Boot provides the service and the orchestrator's runnable jar; the engine core is framework-free so it can be unit-tested without a context.

## 2. Artifacts produced

| Artifact | Location |
|---|---|
| URL shortener service (source, tests, OpenAPI) | `url-shortener/` |
| Orchestration engine, agents, providers, tools, CLI, reports (source, tests) | `orchestrator/` |
| Three scenarios with authored agent outputs and generated fixtures | `scenarios/` |
| One committed run per scenario (reports, audit logs, state, artifacts, resulting sandbox) | `runs/samples/` |
| Architecture overview with diagrams | `docs/ARCHITECTURE.md` |
| Scenario write-ups (decomposition, orchestration, validation) + interactive walkthrough | `docs/SCENARIOS.md` |
| Testing approach, limitations, trade-offs | `docs/TESTING_AND_LIMITATIONS.md` |
| Architecture decision records | `docs/adr/0001…0005` |
| CI pipelines (GitHub Actions, Bitbucket Pipelines) | `.github/workflows/ci.yml`, `bitbucket-pipelines.yml` |
| Setup and run instructions | `README.md` |

Numbers from the committed sample runs (javac toolchain):

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Stages | 12 | 11 | 10 |
| Attempts / failure-driven retries | 12 / 0 | 13 / 2 | 15 / 2 |
| Rollbacks (discarded attempts) | 0 | 2 | 1 |
| Policy findings / blocks | 1 / 0 | 2 / 1 | 1 / 0 |
| Human decisions (rejections) | 3 (0) | 3 (0) | 5 (1) |
| Re-plans | 0 | 0 | 1 (attempts: requirements 3, architecture 2, plan 2; spec v1 → v2) |
| Parallel batches | T3a∥T3b; test∥review∥docs | T3∥T4; test∥review∥docs | T2∥T3; test∥review∥docs |
| Final suite in the merged sandbox | 11/11 | 106/106 | 100/100 |
| MTTR | — | ≈ 15 s | ≈ 9 s |

## 3. Risks, trade-offs and validation

### Risks identified and how each is controlled

| Risk | Control | Evidence |
|---|---|---|
| An agent writes outside the intended tree (`.env`, CI config, secrets) | Sandbox path guard (throws) + `path-allowlist` / `no-secrets` rules (BLOCK) | `WorkspaceTest`, `PolicyEngineTest`; brownfield T2 attempt 1 blocked on `.env.local` |
| An agent introduces a dependency | `dependency-change-requires-approval` parses `pom.xml` (human, high risk) | Greenfield T1 checkpoint |
| Security-sensitive class changed | `protected-files-require-approval` (`UrlPolicy`, `ApiExceptionHandler`) | Brownfield T3 attempt 2 checkpoint |
| Untested source change | `tests-accompany-source` (escalate; waived when verified green against an existing suite) | Ambiguous T2 attempt 1 rejected by the stakeholder |
| A patch that is wrong | `verify-patch` compiles and/or tests in an isolated worktree before merge; the `test` stage runs the whole merged suite | Brownfield T3 attempt 1 caught by javac; every scenario's `test` stage |
| Tests that do not test anything (TDD theatre) | `tests-red` verification: new tests must fail before implementation | Greenfield T2, brownfield T1, ambiguous T1 |
| Parallel work corrupting shared state | Per-attempt worktrees, merge with conflict detection | `WorkspaceTest` fork/merge; brownfield T3∥T4 |
| Runaway autonomy (loops, cost) | Per-stage retry budgets, run-level wall-clock/tool/LLM budgets, STOP file, shutdown hook, failure policies | `ControlsTest`; e2e safe-stop test |
| Half-applied change after a failure | Failed attempts discard their worktree; `ROLLBACK_AND_STOP` restores the initial snapshot | e2e rejection test ends with the sandbox restored |
| Human approves one thing, engine ships another | Pending output persisted with the gate index; resume uses that exact output; decisions consumed once | e2e pause/resume test |
| Stale downstream work after an upstream change | Hash-based invalidation, collapse/re-expand of dynamic nodes | Ambiguous scenario: two `graph.expanded` events |
| Non-reproducible behaviour | Deterministic gates/policy, fixture provider, event-sourced audit log | `./scripts/run-scenarios.sh` reproduces all three runs |
| Wrong interpretation of an ambiguous ask | Blocking clarification checkpoint before design | Ambiguous scenario |
| Build description drift (pom vs. what was verified) | Modules compiled and tested against the exact Spring Boot 3.4.4 dependency set; poms written against the parent's managed versions; fallback verification scripted | `scripts/verify-without-maven.sh`; § 1.5 of the testing document |

### Trade-offs

Deterministic governance over "LLM as judge"; worktree isolation over serialisation; fixtures-by-default over live-only; file-level over line-level merge; files over a database for run state; JDBC over JPA; real-server integration tests over `MockMvc`; two toolchains behind one interface. Full table in `docs/TESTING_AND_LIMITATIONS.md` § 3.

### Validation performed

- Both modules compiled with `javac -parameters -Xlint:all` against Spring Boot 3.4.4 / Spring 6.2.5 / Tomcat 10.1 / Jackson 2.18 / H2 2.3 / Jakarta Validation 3.0 / JUnit 5.10; all 128 tests (94 + 28 + 6 e2e) green.
- The service smoke-tested as a real process over HTTP with a file-backed H2 database (create, idempotent replay, redirect, stats, errors, delete/410, rate limit, actuator).
- Every governance claim in this summary is asserted by an e2e test reading the audit log, not by inspection.
- The greenfield sandbox produced by the pipeline is a complete Maven project that compiles and passes its 11 tests on its own.

## 4. Assumptions

1. The reviewer has JDK 21 and Maven with access to Maven Central; nothing else is required (H2 embedded; PostgreSQL optional).
2. "Agentic" means agents execute multi-step work under defined boundaries and humans own approvals — not that agents must be live LLM calls at review time. Both modes are supported; the offline mode is the default so the prototype is verifiable.
3. A human approver interacts through the CLI in this prototype; in a real deployment the same `Approver` interface would front a Jira transition or a chat message.
4. The URL shortener is single-tenant and unauthenticated; `ownerId` is informational.
5. The three scenarios are representative rather than exhaustive: one clean build, one enhancement with realistic failures, one under-specified ask with a mid-run change.

## 5. Limitations (short form; full list in `docs/TESTING_AND_LIMITATIONS.md` § 2)

- Fixtures are authored; live-model runs will differ in prose and possibly in plan shape.
- Single-process scheduler; no distributed locking.
- File-level merge conflicts; no three-way merge.
- Pattern-based secret/danger detection and pom parsing.
- Maven as the sandbox toolchain is slow; the javac toolchain is the fast path.
- Service: single-node H2 by default, lossy analytics under sustained store failure by design, no auth, in-memory rate limiter.
- `mvn verify` was not executable in the build environment (no Maven Central); see § 1.5 of the testing document for what was verified and how.

## 6. Principle, restated

Agents execute; the engine enforces; humans decide. Every decision — by an agent, a rule, or a person — is an event with an actor, a stage, an attempt and a hash, and every artifact knows what it was derived from. That is what makes the outcome reviewable rather than merely produced.

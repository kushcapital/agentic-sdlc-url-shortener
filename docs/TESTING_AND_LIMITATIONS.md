# Testing approach, limitations and trade-offs

## 1. Testing approach

Three layers, each answering a different question. All tests are JUnit 5; there is no Mockito — collaborators are small hand-written fakes so every test reads as plain Java.

### 1.1 The service (`url-shortener`, 94 tests)

| Layer | What | Why this shape |
|---|---|---|
| Unit — domain | `RandomCodeGeneratorTest` (alphabet, length, modulo-bias rejection with a fake byte source, bounded collision retry), `UrlPolicyTest` (`@ParameterizedTest` tables: accept list, every rejection class incl. private ranges/metadata/self-reference, IPv4/IPv6 classification without DNS), `ClickNormalizerTest` | Pure functions; exhaustive tables because these are the security boundary |
| Unit — reliability | `AnalyticsQueueTest`: batch-on-fill, flush-on-timer, load shedding at capacity, retry with backoff then success, drop after exhausting retries, graceful close | Each property the class claims is a test; a fake sink counts calls and fails on demand |
| Contract | `LinkRepositoryContractTest` (abstract) runs against **both** adapters: `InMemoryLinkRepositoryTest`, `JdbcLinkRepositoryTest` (H2 in PostgreSQL mode, `JdbcDataSource` + `DataSourceTransactionManager`, no Spring context); `SchemaMigratorTest` | Guarantees the adapters are interchangeable; adding a PostgreSQL/Testcontainers subclass is one class |
| Unit — application | `LinkServiceTest` with an injected code generator, a ticking fake `Clock` and the in-memory repository: create / custom alias / reserved / taken / policy / idempotency / exhaustion / validation, resolve / gone / not-found, stats after delete | Business rules without HTTP |
| Integration — HTTP | `ApiIntegrationTest`, `RateLimitIntegrationTest`: `TestServer` boots the **real application** (real Tomcat on a random port, real H2, real filters and advice) and drives it with `java.net.http.HttpClient`; every endpoint and status code, headers (`X-Request-Id` echo, `Cache-Control`), idempotent replay, soft delete → 410, rate limiting (429 on API, never on redirects), health probes, metrics, the OpenAPI document | Black-box by design: this suite is the exit gate the orchestrator runs, so it must exercise exactly what a client sees |
| Unit — web/ops | `ApiExceptionHandlerTest` (every `ErrorCode` has a status), `StoreHealthIndicatorTest` (UP/DOWN with a broken repository) | |

Why real-server tests instead of `MockMvc`: the orchestrator's `verify-patch` gate and the `test` stage run this suite as *the* acceptance signal; a test that goes through the real servlet container, filters and JSON mapping is a stronger signal than a mocked dispatcher. `MockMvc` slices can be added where speed matters; the wiring in `TestServer` is 40 lines.

### 1.2 The orchestrator (`orchestrator`, 28 unit + 6 end-to-end tests)

| Layer | What |
|---|---|
| Unit | `WorkflowGraphTest` (validation, topo order, downstream, ready set, expansion/rewiring, collapse, the shipped workflow), `PolicyEngineTest` (every rule, verdict precedence, risk aggregation, configurability, pom dependency parsing, rule exceptions become blocks), `WorkspaceTest` (path guard, apply, diff stats, snapshot/restore, **fork/merge with conflict detection**), `ProvidersTest` (`ScriptedProvider` lookup order and simulated failures; `AnthropicProvider` with an injected fake transport — forced tool call, schema, model, retryable vs terminal errors, missing tool call; `ProviderChain` fallback reporting), `ControlsTest` (backoff, safe-stop triggers and budgets, `RunStore` event/snapshot/versioning/lineage/reload, metrics/MTTR, all three approvers), `JUnitXmlTest` (surefire/console report parsing incl. nested suites and errors; root-package detection) |
| End-to-end (`ScenariosTest`, opt-in) | The three scenarios run through the real engine with the fixture provider and the real toolchain, asserting the governance behaviour from the event log: parallel batches, TDD red confirmation, policy block, compile failure + rollback, protected-file approval, worktree fork/merge, compiler feedback reaching the retry, clarification answers, artifact versions after re-plan, collapse/re-expand, rejection feedback, final suite sizes (11 / 106 / 100), lineage of the release checklist |
| End-to-end — human controls | Pause with no approver → persisted pending gate → reopen "in a new process" → decide → resume from the same gate without re-running the agent → decision consumed exactly once → a CLI rejection feeds back and, with no further patch available, ends in `ROLLBACK_AND_STOP` with the sandbox restored; STOP file halts before any stage and the run resumes to completion; auto-approve stamps every decision |

Enable the end-to-end tests with `mvn -pl orchestrator test -Dorchestrator.scenarios=true`. They compile and test sandboxes many times, so they are opt-in; with the javac toolchain (`scripts/setup-fast-toolchain.sh`) the six tests take about five minutes.

### 1.3 The scenarios as tests

The scenarios *are* executable specifications of the governance model. Their fixtures contain deliberately faulty attempts — a stray `.env.local`, a missing `switch` case, a configuration change without a test — precisely so the block/retry/rollback/reject paths are exercised on every run, not only in unit tests. The fixtures are authored as real Java source under `scenarios/*/authoring/` and assembled by `orchestrate fixtures`, so they compile and run through the same toolchain as the product code.

### 1.4 What is verified against a live model

Nothing in this repository was run against the Anthropic API (no key in the build environment). `AnthropicProvider` is unit-tested with a fake transport for request shape (forced tool call with the artifact's JSON schema), response extraction and error classification. Live runs use the same engine, gates and policy; the only untested surface is prompt quality, which is why every model-backed agent has a validated contract, a retry-with-validation-feedback path and a fixture fallback.

### 1.5 How this submission was verified

The build environment used to produce this repository had no access to Maven Central, so it could not run `mvn`. Every module was compiled with `javac -parameters` against the exact Spring Boot 3.4.4 dependency set (extracted from a Spring Boot 3.4.4 fat jar), H2 2.3.232, Jakarta Validation 3.0.2 and JUnit 5.10, and every test was executed with the JUnit Platform console launcher — see `scripts/verify-without-maven.sh`. The `pom.xml` files were written against the managed versions of `spring-boot-starter-parent:3.4.4` and validated as XML, but **`mvn verify` itself was first executed on the reviewer's side**; if a Maven-specific issue appears (a plugin default, a surefire include rule), it is in the build description, not in the code or tests, and the fallback verification path is documented and scripted. Two consequences worth knowing: Bean Validation (`@Valid`) had no provider in that environment, so the explicit validation in `LinkService` — which produces the same `400 VALIDATION` outcome — is what the tests exercised; and the Maven toolchain adapter for the orchestrator is unexercised there (its javac twin is what every scenario ran on).

## 2. Limitations (known, deliberate)

### Orchestrator
1. **Fixtures are authored, not recorded.** The scripted outputs were written by hand to be realistic (including their mistakes). A live run will differ in wording and possibly in plan shape; the governance is what is reproducible, not the prose.
2. **Merge is file-level, not line-level.** Two parallel tasks touching the same file conflict and the later one retries on a fresh fork. Real git worktrees with three-way merge would be the next step (ADR-0004).
3. **Single process, single host.** Runs persist to disk and resume across processes, but there is no distributed scheduler or lock; two `resume` commands on the same run at once would race. A database-backed `RunStore` with optimistic locking is the production shape.
4. **Escalation resumes coarsely.** An escalated stage is either skipped (approve) or fails the run (reject); there is no "re-run with these extra instructions" path from the CLI other than a rejection note on a policy checkpoint.
5. **Policy rules are pattern-based.** Secret detection is regex; "dangerous code" is a short list; pom parsing is regex over `<dependency>` blocks. Enough to stop the common agent mistakes; not a substitute for a real SAST/secret scanner in CI.
6. **Budgets are counts and wall-clock, not cost.** Token budgets would be trivial (`llmInputTokens` is already tracked) but are not enforced.
7. **The `docs` stage cannot fail the run** (`SKIP`). Deliberate: documentation must exist for release and the checklist records it, but a doc-writer outage should not block a hotfix.
8. **Maven as the sandbox toolchain is slow** (each verification is a full `mvn test` with Spring Boot contexts). The javac toolchain exists for exactly that reason.

### Service
9. **H2 is single-node by default.** PostgreSQL is a `spring.datasource.url` change (the SQL is the common subset and the driver is on the classpath); multi-instance deployments also need a shared rate-limit store (Redis/Bucket4j).
10. **Analytics are write-behind and lossy under sustained store failure** (by design: shed analytics before shedding redirects). Production would put Kafka between the redirect path and the aggregation.
11. **No authentication or ownership enforcement.** `ownerId` is recorded, not verified.
12. **URL policy does not resolve DNS.** A hostname that resolves to a private address at request time would pass; this only matters if a server-side component ever fetches targets (none does today). Documented in `UrlPolicy`.
13. **Migrations are hand-rolled inside the repository** (`SchemaMigrator`). Explicit `schema_version` and idempotent steps; Flyway should replace them before the schema grows.
14. **Rate limiting is a fixed window per IP in process memory.** Correct for one node; documented alternative for a fleet.

## 3. Trade-offs made and why

| Trade-off | Chosen | Cost accepted |
|---|---|---|
| Deterministic governance vs. "LLM as judge" | Deterministic gates and policy; the model only produces artifacts | Less "clever" — a rule can be too strict (`tests-accompany-source` needed a TDD exemption) but it is auditable and testable |
| Worktree isolation vs. serialised mutation | Worktrees + conflict-checked merge | Extra copies per attempt (cheap; prune at scale); file-level conflict granularity |
| Fixtures vs. live-only | Both, fixtures default | Authoring effort; fixtures must be maintained when the service changes (they are real source and run through the real toolchain, so drift is caught by the e2e suite) |
| JDBC vs. JPA | `JdbcTemplate` | No entity mapping; explicit SQL for a two-table schema and a hot batch-insert path |
| Exhaustive `switch` vs. status on the enum | `switch` in the advice | The compile-time check is the point (the brownfield scenario depends on it) |
| Real-server integration tests vs. `MockMvc` | Real server via `TestServer` | Slower per test (~1 s context boot per class); stronger signal |
| Random codes vs. counters | Random base62, collision check | One existence check per create |
| 302 vs. 301 | 302 + no-store | More redirect traffic, correct analytics |
| Read-time expiry vs. sweeper | Read-time | Expired rows persist (required for stats anyway) |
| Event log + snapshot vs. database | Files | Simple, portable, diffable; not concurrent across processes |
| Maven vs. javac toolchain | Both behind one interface | Two code paths to maintain; each is ~100 lines |

## 4. How to extend the test surface

- Add a repository adapter → subclass `LinkRepositoryContractTest`.
- Add a policy rule → add a case in `PolicyEngineTest` and, if it should be visible in a scenario, a fixture attempt that trips it.
- Add a gate → register in `Gates`, reference it from `SdlcWorkflow`, and assert its `gate.evaluated` event in `ScenariosTest`.
- Add a scenario → `scenarios/<name>/`, then an e2e test that asserts its expectations from the event log.

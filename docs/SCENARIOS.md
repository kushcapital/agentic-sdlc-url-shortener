# The three scenarios

Each scenario is a directory under `scenarios/<name>/`:

- `scenario.json` — the raw requirement, what to seed the sandbox with, the stakeholder's scripted decisions at checkpoints (including rejections and clarification answers), any mid-run requirement revision, and the expectations the e2e tests assert.
- `authoring/` — every agent output as reviewable files: JSON artifacts for the analyst/architect/planner/reviewer/release manager, and one directory per code patch with `meta.json` + `files/`. Attempt-specific outputs (`implement_T2.attempt1/`) script deliberate first-try failures.
- `fixtures/` — generated from `authoring/` by `./scripts/orchestrate.sh fixtures`; what the `ScriptedProvider` replays.

The engine does not know it is replaying: gates, policy, tests, worktrees, merges, approvals, retries and rollbacks all execute for real. With `ANTHROPIC_API_KEY` set, the same scenarios run against a live model and the fixtures are only the fallback.

Committed output of one run each is in `runs/samples/<name>/` (start with `report.md`).

---

## Scenario 1 — Greenfield: build a URL shortener from an empty workspace

**Requirement (as given):** *"Build a URL shortener service. Users submit a long URL and get back a short link; visiting the short link redirects to the original. We want to see how many times each link was clicked. It should be safe to expose on the internet and easy to run locally."*

### Requirement understanding
The analyst normalises this into six functional requirements, four non-functional ones, six Given/When/Then acceptance criteria, four explicit assumptions and two non-blocking ambiguities with a recommended reading ("how many clicks" = count + last click for v0; "safe to expose" = input policy and random codes, rate limiting deferred and recorded as a risk). Out of scope is written down (custom aliases, deletion/expiry, durable storage).

### Decomposition
Architecture: hexagonal — Spring MVC adapter, `LinkStore` port with an in-memory adapter, pure `Codes` class — with four recorded decisions (random base62 not counters; 302 + no-store; storage behind a port; explicit scheme allowlist). The planner produces five tasks:

| Task | Depends on | Verify | Why |
|---|---|---|---|
| T1 scaffold (`pom.xml` on spring-boot-starter-parent) | — | none | nothing compiles without it |
| T2 acceptance tests + `App` stub (boots, no endpoints) | T1 | **tests-red** | executable target first |
| T3a `Codes` + tests | T2 | typecheck | pure class |
| T3b `LinkStore` port + in-memory adapter + tests | T2 | typecheck | independent of T3a |
| T4 `LinkController` (create, redirect, stats, health) | T3a, T3b | **tests-green** | turns the suite green |

### Orchestration (what the engine did)
- `design-review` checkpoint before any code (stakeholder approved).
- T1's `pom.xml` triggered **`dependency-change-requires-approval`** (supply chain is a human decision) — the engine parsed the pom and listed `+org.springframework.boot:spring-boot-starter-web` and `+…:spring-boot-starter-test`; approved with a note.
- T2's exit gate confirmed **TDD red** (5 of 6 acceptance tests fail with 404s against the stub) — a green suite here would have failed the gate.
- **T3a ∥ T3b** ran concurrently in separate worktrees and merged independently (no shared files); the scheduler emitted `scheduler.parallel` and `scheduler.join`.
- T4's `tests-accompany-source` rule was waived because the stage is verified `tests-green` against the T2 acceptance suite (TDD: the tests came first).
- **test ∥ review ∥ docs** ran concurrently and joined at `release`; `release-approval` (high risk) was approved with a go.

### Validation
- Full suite in the merged sandbox: 11/11 green (`artifacts/test-report.json`).
- Review found a medium security item (no rate limiting on create) and two low ones, all recorded as v1 work in the release checklist rather than blocking a v0 preview.
- Run metrics: 12 stages, 3 human decisions, 0 retries, 0 policy blocks. The produced service is in `runs/samples/greenfield/sandbox/` — a complete Maven project: `mvn spring-boot:run` from there.

---

## Scenario 2 — Brownfield: add link expiry to the existing service

**Requirement:** *"Marketing runs time-boxed campaigns and wants campaign links that stop working after the campaign ends. Add optional expiry to short links: callers can set an expiry when creating a link; expired links must stop redirecting and tell the client clearly that the link expired (not that it doesn't exist); click statistics must remain available after expiry. Existing links, clients and stored data must keep working unchanged."*

The sandbox is seeded with the real `url-shortener` module (v1: 94 tests) — a complete Maven project.

### Requirement understanding
Seven functional requirements (optional `expiresAt`, present on every response, 410 `EXPIRED` distinct from `GONE`, stats survive expiry, no change for links without expiry, in-place H2/PostgreSQL migration, `openapi.yaml` updated), four NFRs (no redirect latency, no background job, rollback without schema rollback, both repository adapters under the same contract), six acceptance criteria, and two ambiguities resolved by reasoning (410+distinct code because the requirement says "clearly"; absolute `expiresAt` over relative TTL because of retries and clock skew). Risks include the one that actually bit: *`ApiExceptionHandler.statusFor` is an exhaustive switch expression over `ErrorCode`; a new code without a case breaks compilation.*

### Codebase reasoning
The architect reads the repository map and the domain/web/repository sources and produces an **impacted-module table** — fourteen classes, each with `create/modify/none` and the reason — plus data-flow lines and four decisions (read-time enforcement in `LinkService.resolve()` so both read paths inherit it and `stats()` bypasses it by design; additive nullable column with an idempotent migration guarded by `schema_version` and `INFORMATION_SCHEMA`; future-only validation in the service against the injected `Clock`; `EXPIRED` → 410). It flags `ApiExceptionHandler` as a protected file whose only change is one switch case, and writes the rollback strategy (code rollback, column stays, v1 ignores it).

### Decomposition
| Task | Depends on | Verify |
|---|---|---|
| T1 acceptance tests for expiry (AC-1..AC-5) | — | **tests-red** |
| T2 domain + persistence (`Link.expiresAt`, `expiredAt(now)`, `SchemaMigrator` v2, JDBC mapping, contract/migrator/service tests) | T1 | typecheck (compile) |
| T3 `ErrorCode.EXPIRED` + enforcement in `LinkService` + status mapping | T2 | **tests-green** |
| T4 upgrade tests against a v1-shaped database | T2 | typecheck (compile) |

T3 and T4 are independent once T2 exists → parallel.

### Orchestration (what the engine did)
1. `design-review` approved.
2. T1 red confirmed (4 failing acceptance tests; all 94 existing tests still pass).
3. **T2 attempt 1 was blocked by policy**: the patch also wrote `.env.local` (a common coding-agent habit). `path-allowlist` → `block` → attempt discarded (worktree rollback) → feedback `"policy blocked the patch: … .env.local"` → **attempt 2** without the file passed policy, applied, typechecked, merged.
4. **T3 ∥ T4** forked separate worktrees from the merged main sandbox.
   - **T3 attempt 1 failed `verify-patch` at compile time**: it added `EXPIRED` to `ErrorCode` but not to the switch in `ApiExceptionHandler.statusFor` — javac refused it (*"the switch expression does not cover all possible input values"*). Worktree discarded, the compiler error became feedback, backoff, attempt 2.
   - **T3 attempt 2 touched `ApiExceptionHandler.java`** → `protected-files-require-approval` → `policy:implement:T3` checkpoint. The stakeholder approved with a note describing the reviewed diff (`case GONE, EXPIRED -> HttpStatus.GONE`). Tests green in the worktree (104/104), merged.
   - T4 compiled and merged in parallel, unaffected by T3's failing attempt (that isolation is exactly why worktrees exist — the first version of this engine failed here).
5. **test ∥ review ∥ docs**: the tester compiled and ran the whole merged suite (**106/106**); the reviewer approved with three low/info findings and a compliance note on the protected-file change; docs wrote `CHANGELOG.md`, `README.md`, the updated `openapi.yaml` and `docs/adr/0003-link-expiry.md` into the sandbox.
6. `release-approval` (high) approved: go, with a four-step rollback plan.

### Validation
- Metrics from the sample run: 11 stages, 13 attempts, **2 retries, 2 rollbacks, 1 policy block, 3 human approvals, MTTR ≈ 15 s**, e2e ≈ 80 s with the javac toolchain.
- Lineage: `release-checklist.inputHashes` names the exact test report, review and architecture versions it was derived from.
- The e2e test `brownfieldPolicyBlockCompileFailureRollbackProtectedFileApprovalIsolatedWorktreesGreenMerge` asserts all of the above from the event log.

---

## Scenario 3 — Ambiguous: "our short links feel too easy to guess"

**Requirement:** *"Several users have told support that our short links 'feel too easy to guess'. Fix it."*

Seeded with the v1 service. This scenario is about *not* building the wrong thing.

### Requirement understanding
Attempt 1 refuses to pretend. It states the problem as "cannot be fixed until the intended meaning is confirmed" and produces a **blocking ambiguity** with three interpretations and their impact:

- **A** generated codes are short → raise entropy (configuration change)
- **B** custom aliases are predictable (`sale1`) → alias rules, must not break issued aliases
- **C** users expect private links → a new feature, not hardening

Recommendation A, plus a non-blocking one (never rotate issued codes). The `clarifications-resolved` gate raises the **`clarify-requirements` checkpoint with the questions and options**; the stakeholder answers `AMB-1 = A` with a note. The answers are stored as a `CLARIFICATIONS` artifact and the analyst's attempt 2 produces a resolved spec (8-char codes, legacy codes keep resolving).

### Orchestration (what the engine did)
1. Architecture v1 (single knob: `shortener.code-length` 7 → 8) approved at `design-review`; plan v1 (two tasks) expanded into `implement:T1`, `implement:T2`.
2. **Mid-run requirement revision** (scenario injection after `plan`): product read the tickets and widened the scope — *also enforce a minimum alias length of 6; existing short aliases must keep working.* The engine replaced the requirement artifact, **invalidated** `requirements`, `architecture`, `plan`, **collapsed** the task expansion, and re-ran: requirements attempt 3 (both hardenings, plus the new risk that the shared regex would 404 legacy aliases), architecture v2 (**split `LinkRules.CUSTOM_ALIAS_REGEX` (creation, 6-32) from a new `LinkRules.CODE_PATH` constant used by the controllers' path templates (resolution, 4-32)**), `design-review` requested and approved again, plan v2 with three tasks → graph **re-expanded** into a different shape (`T2 ∥ T3`).
3. T1 red confirmed (3 failing acceptance tests).
4. **T2 ∥ T3** in separate worktrees.
   - **T2 attempt 1** changed `ShortenerProperties` and `application.yml` with no test → `tests-accompany-source` → `policy:implement:T2` → the stakeholder **rejected**: *"A changed default with no test pinning it will silently regress. Add a test…"*. The rejection note became feedback; **attempt 2** added `ShortenerPropertiesTest`, passed policy without any finding, compiled, merged.
   - T3 (rule split + controller path templates + unit test) compiled and merged.
5. **test ∥ review ∥ docs**: full merged suite **100/100** including the legacy-resolution test that inserts 4- and 7-char codes through the repository bean; review approved with a follow-up (dictionary-word aliases); docs wrote the CHANGELOG (calling out the breaking alias rule) and ADR-0004.
6. `release-approval` approved.

### Validation
- Metrics from the sample run: 10 stages, 15 attempts, 2 failure-driven retries, 1 rollback, **1 re-plan** (attempts: requirements 3, architecture 2, plan 2 — the spec, architecture and plan artifacts each exist as v1 and v2, and the lineage column shows downstream stages consuming the v2 hashes), 5 human decisions including 1 rejection, e2e ≈ 50 s with the javac toolchain.
- The e2e test asserts the clarification answers, the artifact versions, two `graph.expanded` events with a `graph.collapsed` between them, the rejection feedback reaching attempt 2, and the final requirement text.

---

## Interactive walkthrough: driving checkpoints by hand

The scenarios script the stakeholder so they are reproducible. To be the stakeholder:

```bash
# 1. Start without --auto-approve. Scripted decisions still apply; anything unscripted pauses.
./scripts/orchestrate.sh run --scenario brownfield --run-id demo
#    → with the shipped scenario.json everything is scripted; delete "design-review" from "approvals"
#      in scenarios/brownfield/scenario.json to see it pause there

# 2. See where it stopped and what it wants
./scripts/orchestrate.sh status demo

# 3. Decide. The stage resumes from the exact gate with the exact output you approved; nothing is regenerated.
./scripts/orchestrate.sh approve demo design-review --decision approve --note "impact table matches the code"
./scripts/orchestrate.sh resume demo

# Rejections feed back into the agent's next attempt:
./scripts/orchestrate.sh approve demo policy:implement:T3 --decision reject --note "explain the handler change first"
./scripts/orchestrate.sh resume demo

# Clarification questions take answers:
./scripts/orchestrate.sh approve <runId> clarify-requirements --answer AMB-1=B --note "tickets are about aliases"

# Safe-stop from another terminal (or Ctrl-C); resume later:
./scripts/orchestrate.sh stop demo
./scripts/orchestrate.sh resume demo

# Roll the sandbox back to the initial snapshot:
./scripts/orchestrate.sh rollback demo
```

The e2e test `pausesAtACheckpointPersistsAndResumesFromTheSameGateAfterACliStyleDecision` performs this sequence programmatically, including a rejection that exhausts the fixture provider's options and ends in `ROLLBACK_AND_STOP` — the sandbox is restored to the initial snapshot and the run ends as FAILED, never half-applied.

## Authoring a new scenario

1. Create `scenarios/<name>/scenario.json` (see `Scenario.parse` in `orchestrator/…/scenario/Scenario.java`).
2. Either export `ANTHROPIC_API_KEY` and run it live, or author `authoring/` outputs and run `./scripts/orchestrate.sh fixtures <name>`.
3. Add the expected governance behaviour to `orchestrator/src/test/java/dev/rajeev/orchestrator/e2e/ScenariosTest.java`.

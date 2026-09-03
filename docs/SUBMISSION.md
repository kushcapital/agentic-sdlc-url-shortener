# Submitting this assignment

This is the author's checklist for handing the repository over. It is kept in the repository because "how the reviewer runs it" is part of the deliverable.

## 1. Before sending — run the full build once on your own machine

The build environment that produced this repository could not reach Maven Central (see `docs/adr/0001-java-spring-stack.md`), so the Maven build was verified by a Maven-free path. Run the real thing before the reviewer does:

```bash
# JDK 21 + Maven 3.9 required.  Check:  java -version ; mvn -version
cd agentic-sdlc-url-shortener
mvn -B verify                                            # expected: url-shortener 94 tests, orchestrator 28 tests, BUILD SUCCESS
ORCHESTRATOR_REPO_ROOT="$PWD" java -jar orchestrator/target/orchestrator.jar fixtures && git status --short scenarios/   # expected: no diff
./scripts/setup-fast-toolchain.sh && source .orchestrator-env
./scripts/run-scenarios.sh                               # expected: three runs, all "succeeded"
mvn -B -pl orchestrator test -Dorchestrator.scenarios=true    # expected: 6 e2e tests green (~5 min)
```

If `mvn verify` fails, the failure will be in the build description (a plugin default, a surefire include pattern, a version the parent does not manage), not in the code: every class and test was compiled and executed against the Spring Boot 3.4.4 classes with `scripts/verify-without-maven.sh`. Fix the pom, re-run, commit — then send.

Optional but worth it: a live-model run, so you can speak to it in the interview.

```bash
export ANTHROPIC_API_KEY=...            # and optionally ANTHROPIC_MODEL
./scripts/orchestrate.sh run --scenario brownfield --llm anthropic     # pauses at design-review; approve/resume as in README
```

## 2. What to submit

Two options; both are fine. The private GitHub repository is the stronger one because the reviewer sees CI green and a commit history, and it matches the "Bitbucket/Git" line in the role.

### Option A — private Git repository (recommended)

The repository ships with its initial commit on `main`. If you unpacked the zip instead of cloning, re-create it first (`git init -b main && git add -A && git commit -m "Agentic SDLC orchestration prototype — URL shortener (Java 21 / Spring Boot 3.4)"`).

```bash
cd agentic-sdlc-url-shortener
git log --oneline                                        # one commit; add a second one if § 1 needed a pom fix
gh repo create agentic-sdlc-url-shortener --private --source . --push   # or create it in the GitHub / Bitbucket UI and `git remote add origin … && git push -u origin main`
```

Then invite the reviewer(s) as collaborators (GitHub: Settings → Collaborators; Bitbucket: Repository settings → User and group access) and send the link. The Actions workflow runs `mvn verify` on the first push; wait for the green tick before sending the email.

What is intentionally **not** in the repository (see `.gitignore`): `target/`, `build/`, `data/` (the H2 file), `.orchestrator-env`, and every run except `runs/samples/` — and within the samples, `snapshots/`, `worktrees/` and `sandbox/target/`.

### Option B — zip archive

```bash
git archive --format=zip --prefix=agentic-sdlc-url-shortener/ -o agentic-sdlc-url-shortener.zip HEAD
```

`git archive` respects `.gitignore` so the archive is exactly what a clone would contain (~2 MB). Do not zip the working directory directly — it would include `target/`, `build/` and local runs.

If the assignment portal limits attachments, attach the zip plus `docs/ENGINEERING_SUMMARY.md` and `runs/samples/brownfield/report.html` as separate files so the reviewer can read the summary and a real run report without extracting anything.

## 3. The submission note

Short, factual, points at the map. A template:

> Subject: Take-home assignment — Agentic Software Engineering System (URL shortener) — Rajeev Kushwaha
>
> Hi <name>,
>
> Please find my submission at <repository link / attached zip>.
>
> It is a Java 21 / Spring Boot 3.4 implementation of an agentic SDLC orchestration engine (explicit dependency graph, entry/exit gates, parallel branches with joins, human approval checkpoints, deterministic policy guardrails, bounded retries, worktree rollback, safe-stop and budgets, an append-only audit log with decision lineage, reliability metrics, and dynamic re-planning) demonstrated on a production-style URL shortener across the three required scenarios — greenfield, brownfield and ambiguous.
>
> Where to start: `README.md` has the deliverables map and setup (`mvn verify`, then `./scripts/run-scenarios.sh`). `docs/ENGINEERING_SUMMARY.md` is the final engineering summary; `runs/samples/*/report.md` are committed run reports so the orchestration can be reviewed without executing anything. Everything runs offline by default (authored, reviewable fixtures through the real engine, real compiles and real test runs); with `ANTHROPIC_API_KEY` set the same engine uses a live model.
>
> One note for transparency: the environment I built this in had no access to Maven Central, so the modules were compiled and tested against the exact Spring Boot 3.4.4 dependency set via a scripted fallback (`scripts/verify-without-maven.sh`, documented in `docs/TESTING_AND_LIMITATIONS.md` § 1.5) and `mvn verify` was run on a separate machine before sending. [Delete this paragraph once you have run `mvn verify` yourself and CI is green — then it is simply true that the Maven build passes.]
>
> Happy to walk through any part of it.
>
> Regards,
> Rajeev

## 4. What the reviewer will do (so you can predict the questions)

1. `mvn verify` — must be green on the first try. This is why § 1 exists.
2. Open `README.md`, then `docs/ENGINEERING_SUMMARY.md`. They will check that the deliverables map covers every bullet in the assignment.
3. Open `runs/samples/brownfield/report.md` and skim: the DAG, the policy block on `.env.local`, the compile failure and rollback, the protected-file approval, the parallel batch and merge. They may grep `events.jsonl`.
4. Run one scenario themselves. The first run with the Maven toolchain takes several minutes because every implementation task is verified with `mvn test`; the README says so and offers the javac toolchain. If they run with `--auto-approve` they will see `auto-approver` stamped on the decisions; if not, the run pauses and they exercise `approve` / `resume` — which is the "controlled autonomy" demonstration.
5. Read `Orchestrator.java`, `Gates.java`, `PolicyEngine.java`, then `SdlcWorkflow.java`, then one agent. The design question they are answering is "is the governance in the engine or in the prompts?" — ADR-0005 is the answer.
6. Likely follow-ups: why worktrees (ADR-0004, with the bug that forced it); why fixtures by default (verifiability); what breaks at scale (single-process scheduler, file-level merge, in-memory rate limiter — all listed in the limitations); how this maps to Bamboo/Bitbucket/Jira (the `Approver` interface fronts a Jira transition; the CI files show the pipeline shape); how the analytics queue becomes Kafka (ADR-0003).

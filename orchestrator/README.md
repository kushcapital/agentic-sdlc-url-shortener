# orchestrator

The agentic SDLC engine: a Spring Boot command-line application (`WebApplicationType.NONE`) around a framework-free core.

```bash
mvn -DskipTests package                                   # target/orchestrator.jar
ORCHESTRATOR_REPO_ROOT=.. java -jar target/orchestrator.jar run --scenario brownfield --auto-approve
# or, from the repository root: ./scripts/orchestrate.sh run --scenario brownfield --auto-approve
mvn test                                                  # 28 unit tests
mvn test -Dorchestrator.scenarios=true                    # + 6 end-to-end governance tests (runs every scenario)
```

## Commands

```
run --scenario greenfield|brownfield|ambiguous [--auto-approve] [--llm auto|anthropic|scripted]
    [--run-id id] [--toolchain maven|javac] [--concurrency n] [--quiet]
resume <runId>                        continue a paused / stopped run from the exact gate it paused at
approve <runId> <gateId> [--decision approve|reject] [--note "..."] [--answer id=option]
status <runId>                        where the run is and what it is waiting for
stop <runId>                          safe-stop from another shell (writes the STOP file)
rollback <runId>                      restore the sandbox to the initial snapshot
report <runId>                        regenerate report.md / report.html
fixtures [scenario]                   rebuild scenarios/<name>/fixtures from authoring/
list
```

Environment: `ORCHESTRATOR_REPO_ROOT` (defaults to the nearest ancestor containing `scenarios/`), `ORCHESTRATOR_RUNS_DIR` (default `<root>/runs`), `ORCHESTRATOR_TOOLCHAIN`, `ORCHESTRATOR_CLASSPATH` + `ORCHESTRATOR_JUNIT_CONSOLE` (javac toolchain; written by `scripts/setup-fast-toolchain.sh`), `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` (live provider).

## Packages

```
core/       WorkflowGraph (DAG, validation, topo order, dynamic expand/collapse), Orchestrator (scheduler on
            virtual threads, gates, retries, failure policies, re-planning, resume), Gates, PolicyEngine,
            Approvals (pausing / scripted / auto), Controls (backoff, SafeStop, budgets, metrics), RunStore
            (events.jsonl + state.json + versioned artifacts with inputHashes lineage), Types, Json
agents/     Agent + AgentContext, Agents (8 specialists), Prompting, Artifacts (record contracts)
llm/        LlmProvider, AnthropicProvider (java.net.http, forced tool call with a JSON schema),
            ScriptedProvider (fixtures/<stage>[.attemptN].json), ProviderChain (fallback with audit event)
tools/      Workspace (sandbox path guard, snapshots, per-attempt worktrees, conflict-checked merge),
            Toolchain + MavenToolchain + JavacToolchain, CommandRunner, JUnitXml
workflows/  SdlcWorkflow — the shipped graph as data
report/     MarkdownReport, HtmlReport
scenario/   Scenario (scenario.json), RunFactory, FixtureBuilder
cli/        OrchestratorCli
resources/  schemas/*.json (artifact JSON schemas used for the forced tool call), application.yml
```

Nothing under `core/` knows what a URL shortener is; the workflow, agents and scenarios are the only places domain knowledge lives.

# ADR-0005: Governance is code, not prompts

**Status:** accepted

## Context
An agentic pipeline can enforce rules either by asking a model to check ("is this patch safe?") or by evaluating deterministic predicates. The assignment asks for policy guardrails, approval checkpoints, bounded retries, rollback and audit-grade traceability.

## Decision
- Gates are named, deterministic functions over the run context (`core/Gates.java`).
- Policy rules are deterministic and evaluate the patch itself (`core/PolicyEngine.java`); verdicts are `ALLOW`, `REQUIRE_APPROVAL`, `BLOCK`. A rule that throws is a block.
- Approvals come only from the CLI, the scenario script, or an explicitly labelled auto mode; agents cannot approve.
- The tester is a tool: test results are facts (surefire / console-launcher XML), not opinions.
- Model outputs are forced through a tool call with a JSON schema (`resources/schemas/*.json`) and deserialised into records; an invalid output is a retry with the validation error as feedback.
- Every decision is an event with an actor, a stage, an attempt and a hash; every artifact records its input hashes.

## Consequences
- Reproducible offline, unit-testable, auditable; the same behaviour with a live model or fixtures.
- Rules can be too blunt (`tests-accompany-source` needed a TDD exemption); each rule is configurable per scenario and its findings are visible in the report.
- Model quality affects the artifacts, never the guardrails.

# ADR-0004: Separate the alias creation rule from the code resolution rule

**Status:** accepted

## Context
One regex (`LinkRules.CUSTOM_ALIAS_REGEX`, 4-32 chars) validated custom aliases on creation, and the same expression was hard-coded in the controllers' `{code:...}` path templates. Raising the alias minimum to 6 in that single place would have made every legacy 4/5-character alias return 404.

## Decision
- `LinkRules.CUSTOM_ALIAS_REGEX` (6-32) applies only when a client creates an alias.
- `LinkRules.CODE_PATH` (4-32) is the path-template constant for every route that resolves a code. It must stay at least as wide as anything ever issued.

## Consequences
- Hardening creation rules never breaks issued links.
- Two patterns to keep in sync mentally; both are documented at their definition and covered by tests.

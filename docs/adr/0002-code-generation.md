# ADR-0002: Random base62 codes with a bounded collision retry

**Status:** accepted

## Context
Short codes can be derived from an auto-increment counter (base62-encoded) or generated randomly.

## Decision
7 random base62 characters (raised to 8 in the ambiguous scenario), produced from `SecureRandom` with rejection sampling (bytes ≥ 248 discarded) so every character is equally likely; an existence check with at most 5 attempts (`CodeExhaustedException` → `503 CODE_EXHAUSTED`); the database primary key is the real uniqueness guarantee. `CodeGenerator` is an interface so tests inject a queued generator.

## Consequences
- Codes are not enumerable — a counter would let anyone walk `/1, /2, /3…` and harvest every link.
- One existence check per create; negligible at the fill ratios involved (62^7 ≈ 3.5 × 10^12).
- Custom aliases share the namespace and are validated separately (creation pattern, reserved words). Creation rules and resolution rules are deliberately distinct (`LinkRules.CODE_PATH` vs. the alias pattern) so tightening one never breaks legacy links — the lesson the ambiguous scenario's first attempt teaches.

# ADR-0003: Link expiry is enforced at read time with an additive schema change

**Status:** accepted · **Date:** 2026-09-02

## Context
Campaign links need a hard end date. The service already soft-deletes links and keeps analytics after deletion; the expiry feature must be backward compatible for clients and stored data, and must not add operational moving parts.

## Decision
1. `Link.expiresAt: string | null`. `LinkService.resolve()` throws `EXPIRED` when `now >= expiresAt`, using the injected clock. Both read paths (redirect, metadata) inherit the check; `stats()` deliberately does not.
2. The database gains `links.expires_at TIMESTAMP NULL` via an idempotent in-code migration guarded by `schema_version` (1 → 2), using an INFORMATION_SCHEMA check that works on H2 and PostgreSQL.
3. `EXPIRED` maps to HTTP 410, alongside `GONE`, with a distinct error code so clients can tell the two apart.
4. "Future only" is validated in `LinkService` against the injected `Clock` and surfaces as `400 VALIDATION`.

## Alternatives considered
- Background sweeper that soft-deletes expired rows: extra infrastructure, and it would conflate "expired" with "deleted".
- Separate expiry table: more joins for a single nullable column.
- 404 for expired links: violates the requirement for a clear signal.

## Consequences
- Rollback to v1 requires no schema rollback; links with an expiry would temporarily stop expiring under v1.
- Migrations remain hand-rolled in the repository; extract a runner before the schema grows further.
- Clock skew across instances can make expiry appear a few seconds inconsistent; acceptable for campaign links.

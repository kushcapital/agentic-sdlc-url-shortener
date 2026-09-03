# Changelog

## 1.1.0 — link expiry

### Added
- `expiresAt` (optional, ISO-8601, must be in the future) on `POST /api/links`.
- `expiresAt` on every link response (`null` when the link never expires).
- `410 EXPIRED` from `GET /:code` and `GET /api/links/:code` once a link has expired. Stats stay readable.
- SQLite schema v2: `links.expires_at` (nullable). Migration runs automatically and is idempotent.

### Compatibility
- Existing clients are unaffected: the field is optional and previously created links never expire.
- Rolling back to 1.0.0 needs no schema rollback; v1 ignores the new column.

## 1.0.0
- Initial release: create/redirect/metadata/delete/stats, batched analytics, rate limiting, idempotent create, SSRF-safe URL policy, health/readiness/metrics, OpenAPI.

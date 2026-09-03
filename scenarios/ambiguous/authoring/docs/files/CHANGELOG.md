# Changelog

## 1.0.1 — guessability hardening

### Changed
- Generated short codes are now 8 base62 characters (was 7; `shortener.code-length`). Clients must not assume a code length.
- **Breaking for alias creation:** `customAlias` must be 6-32 characters (was 4-32). Codes already issued, including 4/5-character aliases, keep resolving.

### Why
Support tickets reported links that "feel too easy to guess" — both short generated codes and short custom aliases. Raising the keyspace to 62^8 and the alias minimum to 6 addresses both without touching anything already shared.

## 1.0.0
- Initial release.

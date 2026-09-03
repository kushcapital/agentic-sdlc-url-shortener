# ADR-0001: Random base62 codes and 302 redirects

**Status:** accepted

## Decisions
- Short codes are 7 random base62 characters (rejection-sampled) with a bounded collision retry, not an encoded counter: sequential codes let anyone enumerate every link.
- Redirects are `302` with `Cache-Control: no-store`: a `301` would be cached by browsers and hide repeat clicks from analytics.

## Consequences
- One existence check per create; negligible at this scale.
- Slightly more redirect traffic reaches the service; required for click counting.

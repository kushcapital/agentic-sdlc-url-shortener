# ADR-0003: Batched, bounded write-behind analytics; 302 + no-store redirects

**Status:** accepted

## Context
The redirect path is the product. Click analytics must not add latency to it, and a broken analytics store must not take redirects down with it. Browsers cache 301 responses, which hides repeat clicks.

## Decision
- Redirects are `302` with `Cache-Control: no-store`.
- Clicks are enqueued in O(1) into an in-process `AnalyticsQueue` (`ArrayBlockingQueue`) and flushed on a timer or when the batch fills, in one transaction via `AnalyticsSink.recordClicks`. The queue is bounded (load shedding when full), retries a failed flush with exponential backoff up to 3 times, then drops the batch and counts it. `close()` flushes on shutdown (Spring lifecycle).
- Only coarse data is kept (referrer host, user-agent family, timestamp) — no IPs.
- Counters are exposed as Micrometer gauges (`analytics.queue.flushed|dropped|retries|depth`).

## Consequences
- Analytics can be lost under sustained store failure — by design we shed analytics before shedding redirects; alert on `analytics.queue.dropped`.
- The queue has the shape of a Kafka producer (linger/batch); replacing the JDBC sink with a topic producer changes nothing above the `AnalyticsSink` interface.

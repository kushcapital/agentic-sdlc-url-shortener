# url-shortener

Spring Boot 3.4 / Java 21 service. Contract-first: [`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml) is the API, served at `/openapi.yaml`.

```bash
mvn spring-boot:run                       # http://localhost:8080, H2 file at ./data/shortener.mv.db
mvn test                                  # 94 tests: unit, repository contract (in-memory + H2), real-HTTP integration
```

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/links` | `{ "url", "customAlias"?, "ownerId"? }` → `201` + `Location`. Idempotent with an `Idempotency-Key` header: a repeated key returns the original link with `200` instead of creating another (the key is bound to the code, not fingerprinted against the body — a known simplification). |
| `GET` | `/{code}` | `302` to the target, `Cache-Control: no-store`; enqueues a click. `404` unknown, `410` deleted. Never rate-limited. |
| `GET` | `/api/links/{code}` | Link metadata. |
| `DELETE` | `/api/links/{code}` | Soft delete → subsequent redirects are `410 GONE`. |
| `GET` | `/api/links/{code}/stats` | Total clicks, clicks per day, top referrer hosts (flushes the analytics queue first so reads are consistent). |
| `GET` | `/actuator/health/liveness`, `/actuator/health/readiness` | Readiness includes the `store` indicator (a real round-trip to the repository). |
| `GET` | `/actuator/metrics/{name}` | e.g. `analytics.queue.flushed`, `analytics.queue.dropped`, `analytics.queue.retries`, `analytics.queue.depth`. |

Errors are one shape: `{ "error": "<CODE>", "message": "...", "requestId": "..." }`. Every `ErrorCode` maps to a status in `ApiExceptionHandler` via an exhaustive `switch` — add an enum constant and the compiler tells you where to map it (the brownfield scenario relies on exactly that). Every response carries `X-Request-Id` (echoed if the client sent one), which is also the MDC key in the log pattern.

## Layout

```
config/       ShortenerProperties (validated @ConfigurationProperties record), beans
domain/       Link, LinkService (create / resolve / stats / delete, idempotency), UrlPolicy (SSRF-safe URL
              acceptance: scheme, credentials, private/link-local/metadata ranges, self-reference — no DNS),
              RandomCodeGenerator (base62, unbiased, bounded collision retry), LinkRules, ErrorCode
repository/   LinkRepository port; InMemoryLinkRepository; JdbcLinkRepository (H2 in PostgreSQL mode or
              PostgreSQL) + SchemaMigrator (versioned, idempotent)
analytics/    AnalyticsQueue — bounded write-behind click batching with retry/backoff and load shedding
web/          LinkController, RedirectController, ApiExceptionHandler, RequestIdFilter, RateLimitFilter
ops/          StoreHealthIndicator (readiness)
```

## Configuration (`application.yml` → `shortener.*`)

| Property | Default | |
|---|---|---|
| `base-url` | `http://localhost:8080` | Used to build `shortUrl` in responses |
| `code-length` | `7` | Generated code length (the ambiguous scenario raises it to 8) |
| `max-url-length` | `2048` | |
| `rate-limit.max` / `rate-limit.window-ms` | `100` / `60000` | Fixed window per client IP on `/api/**` only |
| `analytics.flush-interval-ms` / `max-batch` / `max-queue` / `max-retries` | `500` / `200` / `10000` / `3` | Write-behind queue tuning |

Switch to PostgreSQL with `SPRING_DATASOURCE_URL=jdbc:postgresql://…` (+ username/password); the SQL is the H2/PostgreSQL common subset and the driver is on the classpath.

## Tests

`src/test/java/.../web/TestServer` boots the real application on a random port (real Tomcat, filters, advice, H2) and drives it with `java.net.http.HttpClient` — that is what the orchestrator's gates run, so the suite is black-box on purpose. Repository adapters share `LinkRepositoryContractTest`; adding a Testcontainers PostgreSQL subclass is one class.

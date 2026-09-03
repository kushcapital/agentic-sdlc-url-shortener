# URL shortener (v0)

Create short links, redirect with click counting, read stats. Spring Boot 3 / Java 21.

```bash
mvn test
mvn spring-boot:run          # http://localhost:8080 ; APP_BASE_URL to change the short-link host
```

## API
| Method | Path | Purpose |
|---|---|---|
| POST | /api/links `{url}` | Create a short link (201) |
| GET | /{code} | 302 redirect, counts the click |
| GET | /api/links/{code}/stats | `{code, clicks, lastClickAt}` |
| GET | /health | Liveness |

Only absolute `http`/`https` URLs are accepted.

## v0 limitations
- In-memory storage: links are lost on restart. `LinkStore` is the seam for a JDBC adapter.
- No rate limiting or authentication. Do not expose publicly without them.
- Stats are a count + last click time; breakdowns come with durable storage.

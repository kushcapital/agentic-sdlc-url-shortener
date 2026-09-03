# URL shortener service

Create short links, redirect with click analytics, and operate it safely.

## Link expiry (since 1.1.0)

Set `expiresAt` when creating a link to make it stop working at a fixed instant:

```bash
curl -X POST http://localhost:8080/api/links \
  -H 'content-type: application/json' \
  -d '{"url":"https://example.com/spring-sale","expiresAt":"2026-12-31T23:59:59Z"}'
```

- `expiresAt` must be an ISO-8601 instant in the future, otherwise `400 VALIDATION`.
- After that instant, `GET /:code` and `GET /api/links/:code` answer `410` with `error: "EXPIRED"` (distinct from `GONE`, which means deleted).
- `GET /api/links/:code/stats` keeps working after expiry so campaign numbers survive the campaign.
- Expiry is evaluated on read against the server clock. There is no background job and no purge.

## Running

```bash
mvn spring-boot:run        # SERVER_PORT, SHORTENER_BASE_URL, SPRING_DATASOURCE_URL via env
mvn test
```

Contract: `src/main/resources/static/openapi.yaml` (served at `/openapi.yaml`).

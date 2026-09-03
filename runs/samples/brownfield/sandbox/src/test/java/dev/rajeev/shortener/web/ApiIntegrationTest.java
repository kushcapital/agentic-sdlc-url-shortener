package dev.rajeev.shortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApiIntegrationTest {

    static TestServer server;

    @BeforeAll
    static void start() {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void postCreatesALinkAndReturns201() {
        TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com/a\"}");
        assertEquals(201, r.status());
        Map<String, Object> body = server.json(r);
        String code = (String) body.get("code");
        assertTrue(code.matches("^[0-9A-Za-z]{7}$"), code);
        assertEquals("http://short.test/" + code, body.get("shortUrl"));
        assertEquals("https://example.com/a", body.get("targetUrl"));
        assertEquals(false, body.get("custom"));
        assertNotNull(r.header("X-Request-Id"));
    }

    @Test
    void echoesACallerSuppliedRequestIdForCorrelation() {
        TestServer.Resp r = server.get("/actuator/health/liveness", "X-Request-Id", "corr-123");
        assertEquals("corr-123", r.header("X-Request-Id"));
    }

    @Test
    void redirectsWith302AndNoStore() {
        String code = server.create("https://example.com/r");
        TestServer.Resp r = server.get("/" + code, "User-Agent", "curl/8.0");
        assertEquals(302, r.status());
        assertEquals("https://example.com/r", r.header("Location"));
        assertEquals("no-store", r.header("Cache-Control"));
    }

    @Test
    void statsReflectClicks() {
        String code = server.create("https://example.com/s");
        for (int i = 0; i < 3; i++) server.get("/" + code, "Referer", "https://t.co/x", "User-Agent", "curl/8");
        TestServer.Resp r = server.get("/api/links/" + code + "/stats?topN=2");
        assertEquals(200, r.status());
        Map<String, Object> stats = server.json(r);
        assertEquals(3, stats.get("totalClicks"));
        assertEquals(List.of(Map.of("referrer", "t.co", "clicks", 3)), stats.get("topReferrers"));
        assertEquals(List.of(Map.of("userAgent", "curl", "clicks", 3)), stats.get("topUserAgents"));
        assertEquals(1, ((List<?>) stats.get("clicksByDay")).size());
        assertNotNull(stats.get("lastClickAt"));
    }

    @Test
    void supportsCustomAliasesAndRejectsCollisionsWith409() {
        TestServer.Resp a = server.post("/api/links", "{\"url\":\"https://example.com\",\"customAlias\":\"launch-2026\"}");
        assertEquals(201, a.status());
        assertEquals("launch-2026", server.json(a).get("code"));
        TestServer.Resp b = server.post("/api/links", "{\"url\":\"https://example.org\",\"customAlias\":\"launch-2026\"}");
        assertEquals(409, b.status());
        assertEquals("ALIAS_TAKEN", server.json(b).get("error"));
    }

    @Test
    void rejectsReservedAliasesWith422() {
        TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com\",\"customAlias\":\"health\"}");
        assertEquals(422, r.status());
        assertEquals("ALIAS_RESERVED", server.json(r).get("error"));
    }

    @ParameterizedTest
    @CsvSource({
        "javascript:alert(1), 422, URL_NOT_ALLOWED",
        "http://localhost:3000/x, 422, URL_NOT_ALLOWED",
        "http://169.254.169.254/, 422, URL_NOT_ALLOWED",
        "https://short.test/abc, 422, URL_NOT_ALLOWED",
        "not-a-url, 400, INVALID_URL"
    })
    void rejectsDisallowedUrls(String url, int status, String error) {
        TestServer.Resp r = server.post("/api/links", "{\"url\":\"" + url + "\"}");
        assertEquals(status, r.status(), url);
        assertEquals(error, server.json(r).get("error"));
    }

    @Test
    void returns400ValidationForAMalformedBody() {
        assertEquals("VALIDATION", server.json(server.post("/api/links", "{\"nope\":1}")).get("error"));
        TestServer.Resp garbage = server.post("/api/links", "{not json");
        assertEquals(400, garbage.status());
        assertEquals("VALIDATION", server.json(garbage).get("error"));
        TestServer.Resp shortAlias = server.post("/api/links", "{\"url\":\"https://example.com\",\"customAlias\":\"ab\"}");
        assertEquals(400, shortAlias.status());
    }

    @Test
    void honoursIdempotencyKey() {
        String body = "{\"url\":\"https://example.com/i\"}";
        TestServer.Resp a = server.post("/api/links", body, "Idempotency-Key", "order-42");
        TestServer.Resp b = server.post("/api/links", body, "Idempotency-Key", "order-42");
        assertEquals(201, a.status());
        assertEquals(200, b.status());
        assertEquals(server.json(a).get("code"), server.json(b).get("code"));
    }

    @Test
    void deleteSoftDeletesMetadataAndRedirectReturn410StatsStayReadable() {
        String code = server.create("https://example.com/d");
        server.get("/" + code);
        assertEquals(204, server.delete("/api/links/" + code).status());
        assertEquals(410, server.get("/" + code).status());
        assertEquals("GONE", server.json(server.get("/api/links/" + code)).get("error"));
        assertEquals(410, server.delete("/api/links/" + code).status());
        TestServer.Resp stats = server.get("/api/links/" + code + "/stats");
        assertEquals(200, stats.status());
        assertEquals(1, server.json(stats).get("totalClicks"));
    }

    @Test
    void returns404ForUnknownCodes() {
        assertEquals(404, server.get("/zzzzzzz").status());
        assertEquals(404, server.get("/api/links/zzzzzzz").status());
        assertEquals(404, server.get("/api/links/zzzzzzz/stats").status());
        assertEquals("NOT_FOUND", server.json(server.get("/api/links/zzzzzzz")).get("error"));
    }

    @Test
    void exposesHealthProbesMetricsAndTheOpenApiContract() {
        assertEquals("UP", server.json(server.get("/actuator/health/liveness")).get("status"));
        assertEquals("UP", server.json(server.get("/actuator/health/readiness")).get("status"));
        assertEquals(200, server.get("/actuator/metrics/analytics.queue.flushed").status());
        TestServer.Resp spec = server.get("/openapi.yaml");
        assertEquals(200, spec.status());
        assertTrue(spec.body().contains("/api/links/{code}/stats"));
    }

    @Test
    void neverExposesRawInternalsOnUnknownRoutes() {
        TestServer.Resp r = server.get("/api/does-not-exist");
        assertEquals(404, r.status());
        assertNull(server.json(r).get("trace"));
    }
}

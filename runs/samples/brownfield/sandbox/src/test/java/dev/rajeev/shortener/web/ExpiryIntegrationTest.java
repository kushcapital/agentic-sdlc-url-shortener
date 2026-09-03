package dev.rajeev.shortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for link expiry, written first — they must fail until the feature lands.
 * Mirrors the Given/When/Then criteria in the requirements spec. Black-box through HTTP only, so the
 * file compiles against the current domain model.
 */
class ExpiryIntegrationTest {

    static TestServer server;

    @BeforeAll
    static void start() {
        server = new TestServer();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String future(long seconds) {
        return Instant.now().plusSeconds(seconds).toString();
    }

    @Test
    void ac1_acceptsAFutureExpiresAtAndEchoesItBack() {
        TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com/campaign\",\"expiresAt\":\"" + future(3600) + "\"}");
        assertEquals(201, r.status(), r.body());
        assertNotNull(server.json(r).get("expiresAt"));
    }

    @Test
    void ac2_rejectsAPastExpiresAtWith400Validation() {
        TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com/late\",\"expiresAt\":\"" + Instant.now().minusSeconds(60) + "\"}");
        assertEquals(400, r.status(), r.body());
        assertEquals("VALIDATION", server.json(r).get("error"));
    }

    @Test
    void ac3_linksWithoutExpiryKeepWorkingExactlyAsBefore() {
        TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com/evergreen\"}");
        assertEquals(201, r.status());
        Map<String, Object> body = server.json(r);
        assertEquals(true, body.containsKey("expiresAt"), "response must carry expiresAt (null when absent)");
        assertNull(body.get("expiresAt"));
        assertEquals(302, server.get("/" + body.get("code")).status());
    }

    @Test
    void ac4_anExpiredLinkStopsRedirectingWith410ExpiredAndMetadataSaysSo() throws InterruptedException {
        TestServer.Resp created = server.post("/api/links", "{\"url\":\"https://example.com/flash\",\"expiresAt\":\"" + future(1) + "\"}");
        assertEquals(201, created.status(), created.body());
        String code = (String) server.json(created).get("code");
        Thread.sleep(1200);
        TestServer.Resp redirect = server.get("/" + code);
        assertEquals(410, redirect.status());
        assertEquals("EXPIRED", server.json(redirect).get("error"));
        TestServer.Resp meta = server.get("/api/links/" + code);
        assertEquals(410, meta.status());
        assertEquals("EXPIRED", server.json(meta).get("error"));
    }

    @Test
    void ac5_statsRemainReadableAfterExpiry() throws InterruptedException {
        TestServer.Resp created = server.post("/api/links", "{\"url\":\"https://example.com/stats-after\",\"expiresAt\":\"" + future(1) + "\"}");
        assertEquals(201, created.status(), created.body());
        String code = (String) server.json(created).get("code");
        server.get("/" + code);
        Thread.sleep(1200);
        TestServer.Resp stats = server.get("/api/links/" + code + "/stats");
        assertEquals(200, stats.status());
        assertEquals(1, server.json(stats).get("totalClicks"));
    }
}

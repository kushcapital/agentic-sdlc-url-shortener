package dev.rajeev.shortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.shortener.domain.Link;
import dev.rajeev.shortener.repository.LinkRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for the "links are too easy to guess" fix (written first):
 * generated codes are 8 characters (62^8 ≈ 2.2e14 keyspace), custom aliases must be at least 6
 * characters, and codes that already exist (legacy 7-char generated, 4-char aliases) keep resolving.
 */
class GuessabilityIntegrationTest {

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
    void ac1_generatedCodesAreEightBase62Characters() {
        for (int i = 0; i < 5; i++) {
            TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com/e\"}");
            assertEquals(201, r.status());
            String code = (String) server.json(r).get("code");
            assertTrue(code.matches("^[0-9A-Za-z]{8}$"), code);
        }
    }

    @Test
    void ac2_customAliasesShorterThanSixCharactersAreRejectedWith400() {
        for (String alias : new String[] {"sale", "sale1", "ab-12"}) {
            TestServer.Resp r = server.post("/api/links", "{\"url\":\"https://example.com/a\",\"customAlias\":\"" + alias + "\"}");
            assertEquals(400, r.status(), alias);
        }
        assertEquals(201, server.post("/api/links", "{\"url\":\"https://example.com/a\",\"customAlias\":\"spring-sale\"}").status());
    }

    @Test
    void ac3_existingShortCodesKeepResolving() {
        LinkRepository repo = server.ctx.getBean(LinkRepository.class);
        Instant now = Instant.now();
        repo.insert(new Link("abc1234", "https://example.com/legacy7", now, null, false, null));
        repo.insert(new Link("shop", "https://example.com/legacy4", now, null, true, null));
        assertEquals("https://example.com/legacy7", server.get("/abc1234").header("Location"));
        assertEquals("https://example.com/legacy4", server.get("/shop").header("Location"));
        assertEquals(200, server.get("/api/links/shop/stats").status());
    }
}

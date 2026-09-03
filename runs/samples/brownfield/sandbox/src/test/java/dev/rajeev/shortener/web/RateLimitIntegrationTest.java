package dev.rajeev.shortener.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RateLimitIntegrationTest {

    @Test
    void returns429OnTheApiAfterTheWindowBudgetIsSpentButNeverOnRedirects() {
        try (TestServer server = new TestServer("--shortener.rate-limit.max=2", "--shortener.rate-limit.window-ms=60000")) {
            String code = server.create("https://example.com");
            server.get("/api/links/" + code);
            TestServer.Resp third = server.get("/api/links/" + code);
            assertEquals(429, third.status());
            assertEquals("RATE_LIMITED", server.json(third).get("error"));
            for (int i = 0; i < 5; i++) assertEquals(302, server.get("/" + code).status());
        }
    }
}

package dev.rajeev.shortener.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rajeev.shortener.web.TestServer;
import org.junit.jupiter.api.Test;

class ShortenerPropertiesTest {

    @Test
    void defaultsGeneratedCodeLengthToEightCharacters() {
        try (TestServer server = new TestServer()) {
            assertEquals(8, server.ctx.getBean(ShortenerProperties.class).codeLength());
        }
    }

    @Test
    void stillAllowsAnExplicitOverrideWithinBounds() {
        try (TestServer server = new TestServer("--shortener.code-length=10")) {
            assertEquals(10, server.ctx.getBean(ShortenerProperties.class).codeLength());
        }
        assertThrows(IllegalArgumentException.class, () -> new ShortenerProperties("http://x", 3, 2048, new ShortenerProperties.RateLimit(1, 1000), new ShortenerProperties.Analytics(500, 200, 10000, 3)));
    }
}

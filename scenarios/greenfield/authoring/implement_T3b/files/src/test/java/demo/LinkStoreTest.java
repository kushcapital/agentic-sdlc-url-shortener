package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LinkStoreTest {

    @Test
    void insertsFindsAndRejectsDuplicates() {
        LinkStore s = new LinkStore.InMemory();
        s.insert(new LinkStore.Link("abc1234", "https://example.com/", Instant.EPOCH));
        assertEquals("https://example.com/", s.find("abc1234").orElseThrow().targetUrl());
        assertTrue(s.exists("abc1234"));
        assertFalse(s.exists("nope"));
        assertTrue(s.find("nope").isEmpty());
        assertThrows(IllegalStateException.class, () -> s.insert(new LinkStore.Link("abc1234", "https://x.com/", Instant.EPOCH)));
    }

    @Test
    void countsClicksAndRemembersTheLastOne() {
        LinkStore s = new LinkStore.InMemory();
        s.insert(new LinkStore.Link("clk0001", "https://example.com/", Instant.EPOCH));
        assertEquals(0, s.stats("clk0001").orElseThrow().clicks());
        assertNull(s.stats("clk0001").orElseThrow().lastClickAt());
        Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-03T00:00:00Z");
        s.recordClick("clk0001", t1);
        s.recordClick("clk0001", t2);
        assertEquals(new LinkStore.Stats("clk0001", 2, t2), s.stats("clk0001").orElseThrow());
        s.recordClick("missing", t2); // no-op
        assertTrue(s.stats("missing").isEmpty());
    }
}

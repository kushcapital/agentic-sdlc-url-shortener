package dev.rajeev.shortener.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.shortener.analytics.ClickEvent;
import dev.rajeev.shortener.domain.Link;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract every LinkRepository adapter must satisfy. Adding Postgres later means adding one
 * subclass, nothing else.
 */
abstract class LinkRepositoryContractTest {

    protected LinkRepository repo;

    protected abstract LinkRepository createRepository();

    protected void cleanup() {}

    @BeforeEach
    void setUp() {
        repo = createRepository();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    static Link link(String code) {
        return new Link(code, "https://example.com/", Instant.parse("2026-01-01T00:00:00Z"), null, false, null);
    }

    @Test
    void insertsAndFindsALink() {
        Link l = new Link("abc1234", "https://example.com/", Instant.parse("2026-01-01T00:00:00Z"), null, true, "u1");
        repo.insert(l);
        assertEquals(l, repo.findByCode("abc1234").orElseThrow());
        assertTrue(repo.exists("abc1234"));
        assertFalse(repo.exists("nope"));
        assertTrue(repo.findByCode("nope").isEmpty());
    }

    @Test
    void rejectsDuplicateCodesAtomically() {
        repo.insert(link("dup1234"));
        assertThrows(AliasTakenException.class, () -> repo.insert(link("dup1234")));
    }

    @Test
    void softDeletesOnceAndKeepsTheCodeReserved() {
        repo.insert(link("del1234"));
        Instant at = Instant.parse("2026-02-01T00:00:00Z");
        assertTrue(repo.softDelete("del1234", at));
        assertFalse(repo.softDelete("del1234", Instant.parse("2026-02-02T00:00:00Z")));
        assertEquals(at, repo.findByCode("del1234").orElseThrow().deletedAt());
        assertThrows(AliasTakenException.class, () -> repo.insert(link("del1234")));
        assertFalse(repo.softDelete("missing", at));
    }

    @Test
    void remembersIdempotencyKeys() {
        repo.insert(link("idem123"));
        assertTrue(repo.findByIdempotencyKey("k1").isEmpty());
        repo.rememberIdempotencyKey("k1", "idem123", Instant.now());
        repo.rememberIdempotencyKey("k1", "idem123", Instant.now()); // repeat is a no-op
        assertEquals("idem123", repo.findByIdempotencyKey("k1").orElseThrow().code());
    }

    @Test
    void aggregatesClickStats() {
        repo.insert(link("stat123"));
        Instant today = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant yesterday = today.minus(1, ChronoUnit.DAYS);
        Instant ancient = Instant.parse("2020-01-01T00:00:00Z");
        repo.recordClicks(List.of(
                new ClickEvent("stat123", today, "a.com", "Chrome"),
                new ClickEvent("stat123", today, "a.com", "curl"),
                new ClickEvent("stat123", yesterday, "b.com", "Chrome"),
                new ClickEvent("stat123", ancient, "old.com", "Bot"),
                new ClickEvent("other12", today, "z.com", "Chrome")));
        LinkStats s = repo.stats("stat123", 30, 1);
        assertEquals("stat123", s.code());
        assertEquals(4, s.totalClicks()); // total counts everything ever
        assertEquals(3, s.clicksByDay().stream().mapToLong(LinkStats.DayCount::clicks).sum()); // window excludes the ancient click
        assertEquals(List.of(new LinkStats.ReferrerCount("a.com", 2)), s.topReferrers());
        assertEquals(List.of(new LinkStats.UserAgentCount("Chrome", 2)), s.topUserAgents());
        assertEquals(today, s.lastClickAt());
    }

    @Test
    void returnsEmptyStatsForAnUnclickedCode() {
        LinkStats s = repo.stats("never12", 30, 5);
        assertEquals(0, s.totalClicks());
        assertTrue(s.clicksByDay().isEmpty());
        assertTrue(s.topReferrers().isEmpty());
        assertNull(s.lastClickAt());
    }

    @Test
    void emptyBatchIsANoOpAndPingWorks() {
        repo.recordClicks(List.of());
        repo.ping();
    }
}

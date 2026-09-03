package dev.rajeev.shortener.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.rajeev.shortener.analytics.AnalyticsQueue;
import dev.rajeev.shortener.repository.InMemoryLinkRepository;
import dev.rajeev.shortener.repository.LinkStats;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LinkServiceTest {

    /** Test clock that advances one second per read, so ordering is deterministic. */
    static class TickingClock extends Clock {
        private Instant now = Instant.now().minusSeconds(60);

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { now = now.plusSeconds(1); return now; }
    }

    static class QueuedCodes implements CodeGenerator {
        final Deque<String> codes = new ArrayDeque<>(List.of("gen0001", "gen0002", "gen0003"));

        @Override
        public String generate(Predicate<String> exists) {
            while (!codes.isEmpty()) {
                String c = codes.pop();
                if (!exists.test(c)) return c;
            }
            throw new IllegalStateException("out of test codes");
        }
    }

    private final InMemoryLinkRepository repo = new InMemoryLinkRepository();
    private final AnalyticsQueue analytics = new AnalyticsQueue(repo, 10_000, 10, 100, 3);
    private final LinkService service = new LinkService(repo, new QueuedCodes(), analytics, new UrlPolicy(2048, List.of("localhost:8080"), false), new TickingClock());

    @AfterEach
    void tearDown() {
        analytics.close();
    }

    private static ErrorCode codeOf(Runnable r) {
        DomainException e = assertThrows(DomainException.class, r::run);
        return e.code();
    }

    @Test
    void createsALinkWithAGeneratedCode() {
        var result = service.create(new CreateLinkRequest("https://example.com", null, null), null);
        assertTrue(result.created());
        assertEquals("gen0001", result.link().code());
        assertEquals("https://example.com/", result.link().targetUrl());
        assertFalse(result.link().custom());
        assertNull(result.link().deletedAt());
    }

    @Test
    void usesACustomAliasWhenGiven() {
        var result = service.create(new CreateLinkRequest("https://example.com", "my-link", "owner-1"), null);
        assertEquals("my-link", result.link().code());
        assertTrue(result.link().custom());
        assertEquals("owner-1", result.link().ownerId());
    }

    @Test
    void rejectsTakenReservedAndInvalidAliases() {
        service.create(new CreateLinkRequest("https://example.com", "taken1", null), null);
        assertEquals(ErrorCode.ALIAS_TAKEN, codeOf(() -> service.create(new CreateLinkRequest("https://other.com", "taken1", null), null)));
        assertEquals(ErrorCode.ALIAS_RESERVED, codeOf(() -> service.create(new CreateLinkRequest("https://example.com", "health", null), null)));
        assertEquals(ErrorCode.VALIDATION, codeOf(() -> service.create(new CreateLinkRequest("https://example.com", "a b", null), null)));
        assertEquals(ErrorCode.VALIDATION, codeOf(() -> service.create(new CreateLinkRequest("", null, null), null)));
        assertEquals(ErrorCode.VALIDATION, codeOf(() -> service.create(new CreateLinkRequest("https://example.com", null, "x".repeat(200)), null)));
    }

    @Test
    void rejectsDisallowedUrlsWithThePolicyReason() {
        assertEquals(ErrorCode.URL_NOT_ALLOWED, codeOf(() -> service.create(new CreateLinkRequest("javascript:alert(1)", null, null), null)));
        assertEquals(ErrorCode.INVALID_URL, codeOf(() -> service.create(new CreateLinkRequest("nope", null, null), null)));
        assertEquals(ErrorCode.URL_NOT_ALLOWED, codeOf(() -> service.create(new CreateLinkRequest("http://localhost:8080/x", null, null), null)));
    }

    @Test
    void isIdempotentOnIdempotencyKey() {
        var a = service.create(new CreateLinkRequest("https://example.com", null, null), "key-1");
        var b = service.create(new CreateLinkRequest("https://example.com", null, null), "key-1");
        assertTrue(a.created());
        assertFalse(b.created());
        assertEquals(a.link().code(), b.link().code());
    }

    @Test
    void mapsCodeExhaustionToCodeExhausted() {
        LinkService exhausted = new LinkService(repo, exists -> { throw new CodeExhaustedException(5); }, analytics, new UrlPolicy(2048, List.of(), false), Clock.systemUTC());
        assertEquals(ErrorCode.CODE_EXHAUSTED, codeOf(() -> exhausted.create(new CreateLinkRequest("https://example.com", null, null), null)));
    }

    @Test
    void resolvesActiveLinksAndTracksClicksAsynchronously() {
        Link link = service.create(new CreateLinkRequest("https://example.com", null, null), null).link();
        Link resolved = service.resolveAndTrack(link.code(), "https://news.ycombinator.com/", "curl/8");
        assertEquals("https://example.com/", resolved.targetUrl());
        assertEquals(1, analytics.metrics().enqueued());
        LinkStats stats = service.stats(link.code(), 30, 5);
        assertEquals(1, stats.totalClicks());
        assertEquals(List.of(new LinkStats.ReferrerCount("news.ycombinator.com", 1)), stats.topReferrers());
        assertEquals(List.of(new LinkStats.UserAgentCount("curl", 1)), stats.topUserAgents());
    }

    @Test
    void returnsNotFoundForUnknownAndGoneForDeleted() {
        assertEquals(ErrorCode.NOT_FOUND, codeOf(() -> service.resolve("missing")));
        Link link = service.create(new CreateLinkRequest("https://example.com", null, null), null).link();
        service.remove(link.code());
        assertEquals(ErrorCode.GONE, codeOf(() -> service.resolve(link.code())));
        assertEquals(ErrorCode.GONE, codeOf(() -> service.remove(link.code())));
        assertEquals(ErrorCode.NOT_FOUND, codeOf(() -> service.remove("missing")));
    }

    @Test
    void keepsStatsReadableAfterDeletion() {
        Link link = service.create(new CreateLinkRequest("https://example.com", null, null), null).link();
        service.resolveAndTrack(link.code(), null, null);
        service.remove(link.code());
        assertEquals(1, service.stats(link.code(), 30, 5).totalClicks());
        assertEquals(ErrorCode.NOT_FOUND, codeOf(() -> service.stats("missing", 30, 5)));
    }
}

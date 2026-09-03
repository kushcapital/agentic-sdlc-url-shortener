package dev.rajeev.shortener.repository;

import dev.rajeev.shortener.analytics.ClickEvent;
import dev.rajeev.shortener.domain.Link;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

public class InMemoryLinkRepository implements LinkRepository {

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final Map<String, List<ClickEvent>> clicks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryLinkRepository() {
        this(Clock.systemUTC());
    }

    public InMemoryLinkRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void insert(Link link) {
        if (links.containsKey(link.code())) throw new AliasTakenException(link.code());
        links.put(link.code(), link);
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public boolean exists(String code) {
        return links.containsKey(code);
    }

    @Override
    public synchronized boolean softDelete(String code, Instant at) {
        Link link = links.get(code);
        if (link == null || link.deleted()) return false;
        links.put(code, link.withDeletedAt(at));
        return true;
    }

    @Override
    public Optional<Link> findByIdempotencyKey(String key) {
        String code = idempotency.get(key);
        return code == null ? Optional.empty() : findByCode(code);
    }

    @Override
    public void rememberIdempotencyKey(String key, String code, Instant at) {
        idempotency.putIfAbsent(key, code);
    }

    @Override
    public void recordClicks(List<ClickEvent> events) {
        for (ClickEvent e : events) {
            clicks.computeIfAbsent(e.code(), k -> new ArrayList<>()).add(e);
        }
    }

    @Override
    public LinkStats stats(String code, int days, int topN) {
        List<ClickEvent> events = new ArrayList<>(clicks.getOrDefault(code, List.of()));
        Instant cutoff = clock.instant().minus(days, ChronoUnit.DAYS);
        Map<String, Long> byDay = new HashMap<>();
        Map<String, Long> byRef = new HashMap<>();
        Map<String, Long> byUa = new HashMap<>();
        Instant last = null;
        for (ClickEvent e : events) {
            if (last == null || e.occurredAt().isAfter(last)) last = e.occurredAt();
            if (e.occurredAt().isBefore(cutoff)) continue;
            byDay.merge(e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString(), 1L, Long::sum);
            byRef.merge(e.referrer(), 1L, Long::sum);
            byUa.merge(e.userAgent(), 1L, Long::sum);
        }
        return new LinkStats(
                code,
                events.size(),
                byDay.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(en -> new LinkStats.DayCount(en.getKey(), en.getValue())).toList(),
                top(byRef, topN).stream().map(en -> new LinkStats.ReferrerCount(en.getKey(), en.getValue())).toList(),
                top(byUa, topN).stream().map(en -> new LinkStats.UserAgentCount(en.getKey(), en.getValue())).toList(),
                last);
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Long> m, int n) {
        ToLongFunction<Map.Entry<String, Long>> count = Map.Entry::getValue;
        return m.entrySet().stream()
                .sorted(Comparator.comparingLong(count).reversed().thenComparing(Map.Entry::getKey))
                .limit(n)
                .toList();
    }

    @Override
    public void ping() {
        /* always healthy */
    }
}

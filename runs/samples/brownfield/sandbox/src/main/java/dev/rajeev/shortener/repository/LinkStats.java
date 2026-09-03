package dev.rajeev.shortener.repository;

import java.time.Instant;
import java.util.List;

public record LinkStats(
        String code,
        long totalClicks,
        List<DayCount> clicksByDay,
        List<ReferrerCount> topReferrers,
        List<UserAgentCount> topUserAgents,
        Instant lastClickAt) {

    public record DayCount(String day, long clicks) {}

    public record ReferrerCount(String referrer, long clicks) {}

    public record UserAgentCount(String userAgent, long clicks) {}
}

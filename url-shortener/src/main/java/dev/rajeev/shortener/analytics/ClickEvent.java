package dev.rajeev.shortener.analytics;

import java.time.Instant;

/**
 * One redirect hit. Deliberately coarse and PII-free: referrer host and user-agent family only,
 * never a raw IP, so the analytics store stays out of privacy scope.
 */
public record ClickEvent(String code, Instant occurredAt, String referrer, String userAgent) {}

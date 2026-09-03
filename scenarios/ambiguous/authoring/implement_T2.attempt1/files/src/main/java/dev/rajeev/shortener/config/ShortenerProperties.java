package dev.rajeev.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration bound from {@code shortener.*}. Defaults are the values used in tests;
 * a misconfigured deployment fails at startup (binding), not on the first request.
 */
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        // 8 chars = 62^8 ≈ 2.2e14 keys. Raised from 7 after users reported links felt guessable; legacy 7-char codes still resolve.
        @DefaultValue("8") int codeLength,
        @DefaultValue("2048") int maxUrlLength,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Analytics analytics) {

    public record RateLimit(@DefaultValue("100") int max, @DefaultValue("60000") long windowMs) {}

    public record Analytics(
            @DefaultValue("500") long flushIntervalMs,
            @DefaultValue("200") int maxBatch,
            @DefaultValue("10000") int maxQueue,
            @DefaultValue("3") int maxRetries) {}

    public ShortenerProperties {
        if (codeLength < 4 || codeLength > 16) throw new IllegalArgumentException("shortener.code-length must be between 4 and 16");
        if (maxUrlLength < 64) throw new IllegalArgumentException("shortener.max-url-length must be >= 64");
    }
}

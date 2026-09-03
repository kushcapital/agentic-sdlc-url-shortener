package dev.rajeev.shortener;

import dev.rajeev.shortener.config.ShortenerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * URL shortener service: create short links, redirect with click analytics, and operate it safely.
 *
 * Layering (hexagonal): web -> domain (LinkService) -> repository port with two adapters.
 * Everything security- or reliability-relevant has a home of its own:
 *   domain/UrlPolicy          target URL policy (scheme allowlist, private-network guard)
 *   analytics/AnalyticsQueue  bounded, batched write-behind so redirects never wait on the store
 *   web/RateLimitFilter       token bucket on the API surface, never on redirects
 *   web/RequestIdFilter       X-Request-Id correlation + MDC
 */
@SpringBootApplication
@EnableConfigurationProperties(ShortenerProperties.class)
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}

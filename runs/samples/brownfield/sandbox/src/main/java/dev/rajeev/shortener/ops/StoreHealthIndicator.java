package dev.rajeev.shortener.ops;

import dev.rajeev.shortener.repository.LinkRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness reflects the store: {@code /actuator/health/readiness} answers 503 when the repository
 * cannot be pinged, so a load balancer stops routing to this instance without killing it
 * (liveness stays UP). Wired into the readiness group in application.yml.
 */
@Component("store")
public class StoreHealthIndicator implements HealthIndicator {

    private final LinkRepository repository;

    public StoreHealthIndicator(LinkRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            repository.ping();
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", "store unreachable").build();
        }
    }
}

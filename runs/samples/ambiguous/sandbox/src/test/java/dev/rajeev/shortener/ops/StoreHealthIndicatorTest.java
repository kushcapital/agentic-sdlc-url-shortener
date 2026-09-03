package dev.rajeev.shortener.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.rajeev.shortener.repository.InMemoryLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class StoreHealthIndicatorTest {

    @Test
    void reportsUpWhenTheStoreAnswersAndDownWhenItDoesNot() {
        assertEquals(Status.UP, new StoreHealthIndicator(new InMemoryLinkRepository()).health().getStatus());
        InMemoryLinkRepository broken = new InMemoryLinkRepository() {
            @Override
            public void ping() {
                throw new IllegalStateException("db down");
            }
        };
        assertEquals(Status.DOWN, new StoreHealthIndicator(broken).health().getStatus());
    }
}

package dev.rajeev.shortener.repository;

import dev.rajeev.shortener.analytics.AnalyticsSink;
import dev.rajeev.shortener.domain.Link;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port. Two adapters ship: {@link InMemoryLinkRepository} (tests, zero-setup demos) and
 * {@link JdbcLinkRepository} (H2 file/Postgres via JdbcTemplate). Both pass the same contract test,
 * which is what makes a Postgres/Yugabyte/Mongo adapter a contained change.
 */
public interface LinkRepository extends AnalyticsSink {

    /** @throws AliasTakenException if the code already exists (even soft-deleted). */
    void insert(Link link);

    /** Returns the link regardless of deleted state; callers decide how to treat {@code deletedAt}. */
    Optional<Link> findByCode(String code);

    boolean exists(String code);

    boolean softDelete(String code, Instant at);

    Optional<Link> findByIdempotencyKey(String key);

    void rememberIdempotencyKey(String key, String code, Instant at);

    LinkStats stats(String code, int days, int topN);

    /** Cheap liveness probe used by readiness. */
    void ping();
}

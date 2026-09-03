package dev.rajeev.shortener.repository;

import dev.rajeev.shortener.analytics.ClickEvent;
import dev.rajeev.shortener.domain.Link;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcTemplate adapter for H2 (dev/test) and PostgreSQL (prod) — same SQL. No ORM: a two-table
 * schema with a hot write path is clearer as explicit SQL, and the batch insert in one transaction is
 * the difference between hundreds and tens of thousands of click inserts per second.
 *
 * Uniqueness of {@code code} is enforced by the primary key: the application-level {@code exists()}
 * check is an optimisation; two concurrent inserts of the same alias still resolve correctly.
 */
public class JdbcLinkRepository implements LinkRepository {

    private static final RowMapper<Link> LINK_ROW = (rs, i) -> new Link(
            rs.getString("code"),
            rs.getString("target_url"),
            instant(rs, "created_at"),
            instant(rs, "deleted_at"),
            instant(rs, "expires_at"),
            rs.getBoolean("custom"),
            rs.getString("owner_id"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JdbcLinkRepository(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        new SchemaMigrator(jdbc).migrate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    @Override
    public void insert(Link link) {
        try {
            jdbc.update("INSERT INTO links(code, target_url, created_at, deleted_at, expires_at, custom, owner_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    link.code(), link.targetUrl(), ts(link.createdAt()), ts(link.deletedAt()), ts(link.expiresAt()), link.custom(), link.ownerId());
        } catch (DuplicateKeyException e) {
            throw new AliasTakenException(link.code());
        }
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return jdbc.query("SELECT code, target_url, created_at, deleted_at, expires_at, custom, owner_id FROM links WHERE code = ?", LINK_ROW, code)
                .stream().findFirst();
    }

    @Override
    public boolean exists(String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM links WHERE code = ?", Integer.class, code);
        return n != null && n > 0;
    }

    @Override
    public boolean softDelete(String code, Instant at) {
        return jdbc.update("UPDATE links SET deleted_at = ? WHERE code = ? AND deleted_at IS NULL", ts(at), code) > 0;
    }

    @Override
    public Optional<Link> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT code FROM idempotency_keys WHERE idem_key = ?", (rs, i) -> rs.getString(1), key)
                .stream().findFirst().flatMap(this::findByCode);
    }

    @Override
    public void rememberIdempotencyKey(String key, String code, Instant at) {
        try {
            jdbc.update("INSERT INTO idempotency_keys(idem_key, code, created_at) VALUES (?, ?, ?)", key, code, ts(at));
        } catch (DuplicateKeyException ignored) {
            // a concurrent request with the same key won; both callers observe the same link
        }
    }

    @Override
    public void recordClicks(List<ClickEvent> events) {
        if (events.isEmpty()) return;
        tx.executeWithoutResult(status -> jdbc.batchUpdate(
                "INSERT INTO clicks(code, occurred_at, referrer, user_agent) VALUES (?, ?, ?, ?)",
                events,
                events.size(),
                (ps, e) -> {
                    ps.setString(1, e.code());
                    ps.setTimestamp(2, ts(e.occurredAt()));
                    ps.setString(3, e.referrer());
                    ps.setString(4, e.userAgent());
                }));
    }

    @Override
    public LinkStats stats(String code, int days, int topN) {
        Timestamp cutoff = ts(clock.instant().minus(days, ChronoUnit.DAYS));
        record Totals(long n, Instant last) {}
        Totals totals = jdbc.queryForObject("SELECT COUNT(*) AS n, MAX(occurred_at) AS last FROM clicks WHERE code = ?",
                (rs, i) -> new Totals(rs.getLong("n"), instant(rs, "last")), code);
        List<LinkStats.DayCount> byDay = jdbc.query(
                "SELECT CAST(occurred_at AS DATE) AS click_day, COUNT(*) AS clicks FROM clicks WHERE code = ? AND occurred_at >= ? GROUP BY CAST(occurred_at AS DATE) ORDER BY click_day",
                (rs, i) -> new LinkStats.DayCount(rs.getDate("click_day").toLocalDate().toString(), rs.getLong("clicks")), code, cutoff);
        List<LinkStats.ReferrerCount> byRef = jdbc.query(
                "SELECT referrer, COUNT(*) AS clicks FROM clicks WHERE code = ? AND occurred_at >= ? GROUP BY referrer ORDER BY clicks DESC, referrer ASC LIMIT ?",
                (rs, i) -> new LinkStats.ReferrerCount(rs.getString("referrer"), rs.getLong("clicks")), code, cutoff, topN);
        List<LinkStats.UserAgentCount> byUa = jdbc.query(
                "SELECT user_agent, COUNT(*) AS clicks FROM clicks WHERE code = ? AND occurred_at >= ? GROUP BY user_agent ORDER BY clicks DESC, user_agent ASC LIMIT ?",
                (rs, i) -> new LinkStats.UserAgentCount(rs.getString("user_agent"), rs.getLong("clicks")), code, cutoff, topN);
        return new LinkStats(code, totals == null ? 0 : totals.n(), byDay, byRef, byUa, totals == null ? null : totals.last());
    }

    @Override
    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}

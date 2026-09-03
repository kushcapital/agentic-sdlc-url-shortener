package demo;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage port + in-memory adapter. The interface is the seam for JDBC/Postgres later; v0 ships
 * in-memory so the service is runnable with zero setup.
 */
public interface LinkStore {

    record Link(String code, String targetUrl, Instant createdAt) {}

    record Stats(String code, long clicks, Instant lastClickAt) {}

    void insert(Link link);

    Optional<Link> find(String code);

    boolean exists(String code);

    void recordClick(String code, Instant at);

    Optional<Stats> stats(String code);

    final class InMemory implements LinkStore {
        private record Counter(long clicks, Instant last) {}

        private final Map<String, Link> links = new ConcurrentHashMap<>();
        private final Map<String, Counter> clicks = new ConcurrentHashMap<>();

        @Override
        public synchronized void insert(Link link) {
            if (links.containsKey(link.code())) throw new IllegalStateException("code '" + link.code() + "' already exists");
            links.put(link.code(), link);
            clicks.put(link.code(), new Counter(0, null));
        }

        @Override
        public Optional<Link> find(String code) {
            return Optional.ofNullable(links.get(code));
        }

        @Override
        public boolean exists(String code) {
            return links.containsKey(code);
        }

        @Override
        public void recordClick(String code, Instant at) {
            clicks.computeIfPresent(code, (k, c) -> new Counter(c.clicks() + 1, at));
        }

        @Override
        public Optional<Stats> stats(String code) {
            Counter c = clicks.get(code);
            return c == null ? Optional.empty() : Optional.of(new Stats(code, c.clicks(), c.last()));
        }
    }
}

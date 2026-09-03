package dev.rajeev.shortener.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process, bounded, batched write-behind queue for click events (ADR-0003).
 *
 * The redirect path is the hot path: a 302 must never wait on an analytics INSERT. Events are
 * enqueued in O(1) and flushed on a timer or when the batch fills, in one transaction.
 *
 * Reliability properties:
 * <ul>
 *   <li>Bounded: {@code maxQueue} caps memory; when full, {@link #enqueue} returns false and the
 *       event is dropped — we shed analytics load before we shed redirects.</li>
 *   <li>Retry with backoff: a failed flush retries up to {@code maxRetries}; after that the batch is
 *       dropped and counted, so a broken store degrades into lost analytics, not a stuck service.</li>
 *   <li>Graceful shutdown: {@link #close} flushes what it can.</li>
 * </ul>
 * This is the shape of a Kafka producer (linger.ms / batch.size / buffer.memory); swapping the sink
 * for a topic changes nothing above the {@link AnalyticsSink} interface.
 */
public class AnalyticsQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsQueue.class);

    public record Metrics(long enqueued, long flushed, long dropped, long retries, int pending) {}

    private final AnalyticsSink sink;
    private final long flushIntervalMs;
    private final int maxBatch;
    private final int maxRetries;
    private final ArrayBlockingQueue<ClickEvent> buffer;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock flushLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> timer;

    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong flushed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    public AnalyticsQueue(AnalyticsSink sink, long flushIntervalMs, int maxBatch, int maxQueue, int maxRetries) {
        this.sink = sink;
        this.flushIntervalMs = flushIntervalMs;
        this.maxBatch = maxBatch;
        this.maxRetries = maxRetries;
        this.buffer = new ArrayBlockingQueue<>(maxQueue);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "analytics-flush");
            t.setDaemon(true);
            return t;
        });
    }

    /** @return false when the queue is closed or full (event dropped). */
    public boolean enqueue(ClickEvent event) {
        if (closed.get()) return false;
        if (!buffer.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        enqueued.incrementAndGet();
        if (buffer.size() >= maxBatch) {
            scheduler.execute(this::flush);
        } else if (timer == null || timer.isDone()) {
            timer = scheduler.schedule(this::flush, flushIntervalMs, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    /** Flushes everything currently buffered. Safe to call concurrently; calls coalesce on the lock. */
    public void flush() {
        flushLock.lock();
        try {
            while (!buffer.isEmpty()) {
                List<ClickEvent> batch = new ArrayList<>(maxBatch);
                buffer.drainTo(batch, maxBatch);
                deliver(batch);
            }
        } finally {
            flushLock.unlock();
        }
    }

    private void deliver(List<ClickEvent> batch) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                sink.recordClicks(batch);
                flushed.addAndGet(batch.size());
                return;
            } catch (RuntimeException e) {
                log.warn("analytics flush failed (attempt {}/{}): {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    retries.incrementAndGet();
                    sleep(Math.min(1000L, 25L * (1L << attempt)));
                }
            }
        }
        dropped.addAndGet(batch.size());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Metrics metrics() {
        return new Metrics(enqueued.get(), flushed.get(), dropped.get(), retries.get(), buffer.size());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdown();
            flush();
        }
    }
}

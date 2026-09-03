package dev.rajeev.shortener.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnalyticsQueueTest {

    /** Fake sink that fails the first {@code failures} calls. */
    static class SinkSpy implements AnalyticsSink {
        final List<List<ClickEvent>> batches = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final int failures;

        SinkSpy(int failures) {
            this.failures = failures;
        }

        @Override
        public void recordClicks(List<ClickEvent> events) {
            if (calls.incrementAndGet() <= failures) throw new IllegalStateException("store down");
            batches.add(new ArrayList<>(events));
        }
    }

    private static ClickEvent ev(int i) {
        return new ClickEvent("abc", Instant.ofEpochMilli(i), "(direct)", "curl");
    }

    @Test
    void batchesEventsAndFlushesWhenTheBatchFills() throws Exception {
        SinkSpy sink = new SinkSpy(0);
        try (AnalyticsQueue q = new AnalyticsQueue(sink, 10_000, 3, 100, 3)) {
            for (int i = 0; i < 7; i++) assertTrue(q.enqueue(ev(i)));
            q.flush();
            int total = sink.batches.stream().mapToInt(List::size).sum();
            assertEquals(7, total);
            assertTrue(sink.batches.stream().allMatch(b -> b.size() <= 3));
            AnalyticsQueue.Metrics m = q.metrics();
            assertEquals(7, m.enqueued());
            assertEquals(7, m.flushed());
            assertEquals(0, m.dropped());
            assertEquals(0, m.pending());
        }
    }

    @Test
    void flushesOnTheTimer() throws Exception {
        SinkSpy sink = new SinkSpy(0);
        try (AnalyticsQueue q = new AnalyticsQueue(sink, 50, 100, 100, 3)) {
            q.enqueue(ev(1));
            assertEquals(0, sink.batches.size());
            long deadline = System.currentTimeMillis() + 2000;
            while (sink.batches.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertEquals(1, sink.batches.size());
        }
    }

    @Test
    void shedsLoadWhenTheQueueIsFullInsteadOfGrowingUnbounded() {
        AnalyticsQueue q = new AnalyticsQueue(new SinkSpy(0), 10_000, 1000, 2, 3);
        assertTrue(q.enqueue(ev(1)));
        assertTrue(q.enqueue(ev(2)));
        assertFalse(q.enqueue(ev(3)));
        assertEquals(1, q.metrics().dropped());
        q.close();
    }

    @Test
    void retriesAFailedFlushWithBackoffAndThenSucceeds() {
        SinkSpy sink = new SinkSpy(2);
        try (AnalyticsQueue q = new AnalyticsQueue(sink, 10_000, 10, 100, 3)) {
            q.enqueue(ev(1));
            q.flush();
            assertEquals(1, sink.batches.size());
            AnalyticsQueue.Metrics m = q.metrics();
            assertEquals(1, m.flushed());
            assertEquals(2, m.retries());
            assertEquals(0, m.dropped());
        }
    }

    @Test
    void dropsTheBatchAfterExhaustingRetriesSoTheServiceNeverWedges() {
        SinkSpy sink = new SinkSpy(99);
        try (AnalyticsQueue q = new AnalyticsQueue(sink, 10_000, 10, 100, 2)) {
            q.enqueue(ev(1));
            q.flush();
            AnalyticsQueue.Metrics m = q.metrics();
            assertEquals(0, m.flushed());
            assertEquals(1, m.dropped());
            assertEquals(1, m.retries());
        }
    }

    @Test
    void closeFlushesRemainingEventsAndRejectsNewOnes() {
        SinkSpy sink = new SinkSpy(0);
        AnalyticsQueue q = new AnalyticsQueue(sink, 10_000, 10, 100, 3);
        q.enqueue(ev(1));
        q.close();
        assertEquals(1, sink.batches.size());
        assertFalse(q.enqueue(ev(2)));
    }
}

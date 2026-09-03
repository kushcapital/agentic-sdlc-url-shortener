package dev.rajeev.shortener.analytics;

import java.util.List;

/** Where flushed click batches go. The repository implements it; Kafka would be another implementation. */
public interface AnalyticsSink {
    void recordClicks(List<ClickEvent> events);
}

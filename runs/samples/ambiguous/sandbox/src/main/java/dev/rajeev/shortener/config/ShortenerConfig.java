package dev.rajeev.shortener.config;

import dev.rajeev.shortener.analytics.AnalyticsQueue;
import dev.rajeev.shortener.domain.CodeGenerator;
import dev.rajeev.shortener.domain.LinkService;
import dev.rajeev.shortener.domain.RandomCodeGenerator;
import dev.rajeev.shortener.domain.UrlPolicy;
import dev.rajeev.shortener.repository.LinkRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root. Everything the service needs is a bean here, so tests can build the
 * same graph with fakes (in-memory repository, fixed clock) without Spring.
 */
@Configuration
public class ShortenerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CodeGenerator codeGenerator(ShortenerProperties props) {
        return new RandomCodeGenerator(props.codeLength());
    }

    @Bean
    UrlPolicy urlPolicy(ShortenerProperties props) {
        String selfHost = URI.create(props.baseUrl()).getAuthority();
        return new UrlPolicy(props.maxUrlLength(), List.of(selfHost), false);
    }

    @Bean(destroyMethod = "close")
    AnalyticsQueue analyticsQueue(LinkRepository repository, ShortenerProperties props, MeterRegistry registry) {
        var a = props.analytics();
        AnalyticsQueue queue = new AnalyticsQueue(repository, a.flushIntervalMs(), a.maxBatch(), a.maxQueue(), a.maxRetries());
        registry.gauge("analytics.queue.pending", queue, q -> q.metrics().pending());
        registry.gauge("analytics.queue.dropped", queue, q -> q.metrics().dropped());
        registry.gauge("analytics.queue.flushed", queue, q -> q.metrics().flushed());
        registry.gauge("analytics.queue.retries", queue, q -> q.metrics().retries());
        return queue;
    }

    @Bean
    LinkService linkService(LinkRepository repository, CodeGenerator codeGenerator, AnalyticsQueue queue, UrlPolicy urlPolicy, Clock clock) {
        return new LinkService(repository, codeGenerator, queue, urlPolicy, clock);
    }
}

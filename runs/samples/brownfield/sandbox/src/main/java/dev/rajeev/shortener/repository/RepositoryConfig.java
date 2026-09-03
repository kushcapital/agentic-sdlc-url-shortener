package dev.rajeev.shortener.repository;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class RepositoryConfig {

    @Bean
    LinkRepository linkRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager, Clock clock) {
        return new JdbcLinkRepository(jdbc, new TransactionTemplate(txManager), clock);
    }
}

package dev.rajeev.shortener.repository;

import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same contract against H2 in PostgreSQL compatibility mode — the SQL is the common subset. */
class JdbcLinkRepositoryTest extends LinkRepositoryContractTest {

    private org.h2.jdbcx.JdbcDataSource dataSource;

    @Override
    protected LinkRepository createRepository() {
        dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new JdbcLinkRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), Clock.systemUTC());
    }

    @Override
    protected void cleanup() {
        new JdbcTemplate(dataSource).execute("SHUTDOWN");
    }
}

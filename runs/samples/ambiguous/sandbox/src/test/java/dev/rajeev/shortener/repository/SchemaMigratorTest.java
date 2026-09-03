package dev.rajeev.shortener.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigratorTest {

    private static JdbcTemplate fresh() {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(ds);
    }

    @Test
    void createsTheSchemaOnceAndIsIdempotent() {
        JdbcTemplate jdbc = fresh();
        assertEquals(SchemaMigrator.CURRENT_VERSION, new SchemaMigrator(jdbc).migrate());
        assertEquals(SchemaMigrator.CURRENT_VERSION, new SchemaMigrator(jdbc).migrate());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM links", Integer.class));
    }
}

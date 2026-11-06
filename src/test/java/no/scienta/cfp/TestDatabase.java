package no.scienta.cfp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A throwaway Postgres database per test class. One container is started for the whole test run, and each call to
 * {@link #fresh} creates an empty database in it, so test classes don't see each other's rows.
 */
final class TestDatabase {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestDatabase() {
    }

    /** Creates an empty database and points the app's datasource at it. */
    static void fresh(DynamicPropertyRegistry registry) {
        String name = "cfp_" + COUNTER.incrementAndGet();
        createDatabase(name);
        pointAt(registry, POSTGRES, name);
    }

    /** Points the app at a database through the deploy contract's PG* variables, as on Scaleway. */
    static void pointAt(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres, String database) {
        registry.add("PGHOST", postgres::getHost);
        registry.add("PGPORT", () -> postgres.getMappedPort(5432));
        registry.add("PGDATABASE", () -> database);
        registry.add("PGUSER", postgres::getUsername);
        registry.add("PGPASSWORD", postgres::getPassword);
        registry.add("PGSSLMODE", () -> "disable");
    }

    private static synchronized void createDatabase(String name) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test database " + name, e);
        }
    }
}

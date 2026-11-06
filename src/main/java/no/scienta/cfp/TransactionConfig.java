package no.scienta.cfp;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Sets every transaction's isolation level with SET TRANSACTION, which lasts for that transaction only.
 *
 * On this platform a session-level setting leaks to whoever gets the pooled connection next (AGENTS.md), and
 * {@code @Transactional(isolation = ...)} is session-level: the JDBC driver sends SET SESSION CHARACTERISTICS. One
 * REPEATABLE READ export left other requests in REPEATABLE READ, where a row lock no longer makes a later COUNT see
 * the rows committed while we waited: 22 registrations for a 20-seat workshop, and 500s ("could not serialize
 * access") on concurrent decisions. So: never use the isolation attribute, and state the level per transaction.
 * Writes get READ COMMITTED (what the row locks rely on), read-only transactions REPEATABLE READ (one snapshot).
 */
@Configuration
public class TransactionConfig {

    @Bean
    public JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource) {
            @Override
            protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(con, definition);
                try (Statement stmt = con.createStatement()) {
                    stmt.execute(definition.isReadOnly()
                            ? "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"
                            : "SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
                }
            }
        };
    }
}

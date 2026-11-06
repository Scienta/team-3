package no.scienta.cfp;

import java.util.Collection;

import org.springframework.jdbc.core.PreparedStatementSetter;

/**
 * Binds a list of ids as ONE array parameter, for {@code WHERE x = ANY(?)}. An IN list with one {@code ?} per id hits
 * the driver's bind-parameter limit (65535) as the tables grow, and makes a new SQL text per list length.
 */
final class SqlArrays {

    static PreparedStatementSetter bigints(Collection<Long> ids) {
        return ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray()));
    }

    private SqlArrays() {
    }
}

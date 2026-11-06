package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Workshop registrations, kept in the order they came in (the serial id). A row lock on the workshop's talk (see
 * {@link #lockTalk}) guards the 20-seat limit against a rush of concurrent registrations, since advisory locks aren't
 * guaranteed on this database; the unique index on (workshop_id, lower(email)) backs up the one-per-email rule.
 */
@Repository
public class RegistrationDao {

    public static final int CAPACITY = 20;

    private final JdbcTemplate jdbc;

    private final RowMapper<Map<String, Object>> mapper = (rs, i) -> {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", rs.getLong("id"));
        r.put("workshopId", rs.getLong("workshop_id"));
        r.put("name", rs.getString("name"));
        r.put("email", rs.getString("email"));
        return r;
    };

    public RegistrationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes a row lock on the talk and returns its {format, status}, or null if there is no such talk. Call inside
     * the registration transaction.
     */
    public Map<String, Object> lockTalk(long talkId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT format, status FROM talk WHERE id = ? FOR UPDATE",
                talkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int count(long workshopId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM registration WHERE workshop_id = ?", Integer.class,
                workshopId);
        return n == null ? 0 : n;
    }

    /** Whether this email (in any letter case) is already registered for the workshop. */
    public boolean registered(long workshopId, String email) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM registration WHERE workshop_id = ? AND lower(email) = lower(?)", Integer.class,
                workshopId, email);
        return n != null && n > 0;
    }

    /** Inserts the registration and returns it; throws DuplicateKeyException if the email is already registered. */
    public Map<String, Object> insert(long workshopId, String name, String email) {
        return jdbc.queryForObject(
                "INSERT INTO registration (workshop_id, name, email) VALUES (?, ?, ?) RETURNING id, workshop_id, name, email",
                mapper, workshopId, name, email);
    }

    /** The registrations of one workshop, in the order they registered. */
    public List<Map<String, Object>> findByWorkshop(long workshopId) {
        return jdbc.query("SELECT id, workshop_id, name, email FROM registration WHERE workshop_id = ? ORDER BY id",
                mapper, workshopId);
    }
}

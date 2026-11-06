package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpeakerDao {

    private final JdbcTemplate jdbc;

    public SpeakerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> findById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, email, bio FROM speaker WHERE id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    public boolean exists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM speaker WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    public boolean emailTaken(String email) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM speaker WHERE lower(email) = lower(?)", Integer.class, email);
        return n != null && n > 0;
    }

    public long insert(String name, String email, String bio) {
        return jdbc.queryForObject(
                "INSERT INTO speaker (name, email, bio, created) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, name, email, bio, Utils.today());
    }

    public int update(long id, String name, String email, String bio) {
        return jdbc.update("UPDATE speaker SET name = ?, email = ?, bio = ? WHERE id = ?", name, email, bio, id);
    }

    public int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM speaker", Integer.class);
    }

    // Used by the old admin page to look people up.
    public List<Map<String, Object>> search(String name) {
        return jdbc.queryForList("SELECT id, name, email, bio FROM speaker WHERE name ILIKE '%" + name + "%' ORDER BY name");
    }
}

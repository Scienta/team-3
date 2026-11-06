package no.scienta.cfp;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RatingDao {

    private final JdbcTemplate jdbc;

    public RatingDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or replace the reviewer's rating. One statement, so two clicks at once can't make two rows.
     */
    public void save(long talkId, long reviewerId, int score, String ratedAt) {
        jdbc.update("INSERT INTO rating (talk_id, reviewer_id, score, rated_at) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (talk_id, reviewer_id) DO UPDATE SET score = excluded.score, rated_at = excluded.rated_at",
                talkId, reviewerId, score, ratedAt);
    }

    /**
     * Returns {cnt, total} for one talk.
     */
    public Map<String, Object> stats(long talkId) {
        return jdbc.queryForMap("SELECT COUNT(*) AS cnt, COALESCE(SUM(score), 0) AS total FROM rating WHERE talk_id = ?", talkId);
    }

    // public double average(long talkId) {
    //     return jdbc.queryForObject("SELECT AVG(score) FROM rating WHERE talk_id = ?", Double.class, talkId);
    // }
}

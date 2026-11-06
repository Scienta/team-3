package no.scienta.cfp;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
     * Returns {talkId: [cnt, total, max, min]} for several talks in one query, rather than one per talk (the pool is
     * capped at 5). Talks nobody has rated are left out. max/min let the average leave out one of each (ratings-cr).
     */
    public Map<Long, long[]> statsByTalks(List<Long> talkIds) {
        Map<Long, long[]> byTalk = new HashMap<>();
        if (talkIds.isEmpty()) {
            return byTalk;
        }
        String placeholders = String.join(",", Collections.nCopies(talkIds.size(), "?"));
        jdbc.query("SELECT talk_id, COUNT(*) AS cnt, SUM(score) AS total, MAX(score) AS hi, MIN(score) AS lo "
                        + "FROM rating WHERE talk_id IN (" + placeholders
                        + ") GROUP BY talk_id",
                rs -> {
                    byTalk.put(rs.getLong("talk_id"), new long[] {rs.getLong("cnt"), rs.getLong("total"), rs.getLong("hi"), rs.getLong("lo")});
                },
                talkIds.toArray());
        return byTalk;
    }

    /** How many different reviewers rated this talk (one reviewer who rated twice counts once). */
    public int distinctReviewers(long talkId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(DISTINCT reviewer_id) FROM rating WHERE talk_id = ?", Integer.class, talkId);
        return n == null ? 0 : n;
    }

    // public double average(long talkId) {
    //     return jdbc.queryForObject("SELECT AVG(score) FROM rating WHERE talk_id = ?", Double.class, talkId);
    // }
}

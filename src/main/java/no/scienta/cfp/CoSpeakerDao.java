package no.scienta.cfp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Co-speakers of a talk: other registered speakers added to it, kept in the order added (the serial id).
 * A talk row lock (see {@link #lockTalk}) guards the "at most two" rule against concurrent adds, since advisory
 * locks aren't guaranteed on this database.
 */
@Repository
public class CoSpeakerDao {

    private final JdbcTemplate jdbc;

    public CoSpeakerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes a row lock on the talk and returns its main speaker's id, or null if there is no such talk. Call inside
     * the add/remove transaction.
     */
    public Long lockTalk(long talkId) {
        List<Long> speakerIds = jdbc.queryForList("SELECT speaker_id FROM talk WHERE id = ? FOR UPDATE", Long.class,
                talkId);
        return speakerIds.isEmpty() ? null : speakerIds.get(0);
    }

    /** The co-speakers of one talk, in the order added. */
    public List<Long> findByTalk(long talkId) {
        return jdbc.queryForList("SELECT speaker_id FROM co_speaker WHERE talk_id = ? ORDER BY id", Long.class, talkId);
    }

    /** The co-speakers of several talks at once, grouped by talk id, each list in the order added. */
    public Map<Long, List<Long>> findByTalks(List<Long> talkIds) {
        Map<Long, List<Long>> byTalk = new LinkedHashMap<>();
        if (talkIds.isEmpty()) {
            return byTalk;
        }
        String placeholders = String.join(",", Collections.nCopies(talkIds.size(), "?"));
        jdbc.query("SELECT talk_id, speaker_id FROM co_speaker WHERE talk_id IN (" + placeholders + ") ORDER BY id",
                rs -> {
                    long talkId = rs.getLong("talk_id");
                    byTalk.computeIfAbsent(talkId, k -> new ArrayList<>()).add(rs.getLong("speaker_id"));
                },
                talkIds.toArray());
        return byTalk;
    }

    /** Idempotent: a repeat add changes nothing (unique on talk_id, speaker_id). */
    public void insert(long talkId, long speakerId) {
        jdbc.update("INSERT INTO co_speaker (talk_id, speaker_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                talkId, speakerId);
    }

    public void delete(long talkId, long speakerId) {
        jdbc.update("DELETE FROM co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId);
    }
}

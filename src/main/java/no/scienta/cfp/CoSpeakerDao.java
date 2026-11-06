package no.scienta.cfp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Co-speakers of a talk: other registered speakers added to it, kept in the order added (the serial id).
 * A talk row lock (see {@link TalkDao#lockForUpdate}) guards the "at most two" rule against concurrent adds, since advisory
 * locks aren't guaranteed on this database.
 */
@Repository
public class CoSpeakerDao {

    private final JdbcTemplate jdbc;

    public CoSpeakerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        jdbc.query("SELECT talk_id, speaker_id FROM co_speaker WHERE talk_id = ANY(?) ORDER BY id",
                SqlArrays.bigints(talkIds),
                rs -> {
                    long talkId = rs.getLong("talk_id");
                    byTalk.computeIfAbsent(talkId, k -> new ArrayList<>()).add(rs.getLong("speaker_id"));
                });
        return byTalk;
    }

    /** Idempotent: a repeat add changes nothing (unique on talk_id, speaker_id). */
    public void insert(long talkId, long speakerId) {
        jdbc.update("INSERT INTO co_speaker (talk_id, speaker_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                talkId, speakerId);
    }

    /** Returns how many rows went: 0 if the speaker wasn't a co-speaker of the talk. */
    public int delete(long talkId, long speakerId) {
        return jdbc.update("DELETE FROM co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId);
    }
}

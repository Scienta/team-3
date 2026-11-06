package no.scienta.cfp;

import java.util.Date;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TalkDao {

    private static final String COLUMNS = "id, title, format, duration, level, status, speaker_id, submitted_at";

    private final JdbcTemplate jdbc;

    private final RowMapper<TalkDto> mapper = (rs, rowNum) -> {
        TalkDto t = new TalkDto();
        t.id = rs.getLong("id");
        t.title = rs.getString("title");
        t.format = rs.getString("format");
        t.durationMinutes = rs.getInt("duration");
        t.level = rs.getString("level");
        t.status = rs.getString("status");
        t.speakerId = rs.getLong("speaker_id");
        long millis = rs.getLong("submitted_at");
        t.submittedAt = new Date(millis);
        t.submittedOn = Utils.toSqlDate(millis);
        return t;
    };

    public TalkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TalkDto findById(long id) {
        List<TalkDto> list = jdbc.query("SELECT " + COLUMNS + " FROM talk WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<TalkDto> findBySpeaker(long speakerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM talk WHERE speaker_id = ? ORDER BY id", mapper, speakerId);
    }

    public List<TalkDto> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM talk ORDER BY id", mapper);
    }

    public boolean exists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM talk WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /**
     * Takes a row lock on the talk and returns its current status, or null if there is no such talk.
     * Call inside the decide/withdraw transaction so two committee members can't both act on one state.
     */
    public String lockStatus(long id) {
        List<String> s = jdbc.queryForList("SELECT status FROM talk WHERE id = ? FOR UPDATE", String.class, id);
        return s.isEmpty() ? null : s.get(0);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE talk SET status = ? WHERE id = ?", status, id);
    }

    /** How many registered speakers have at least one talk. */
    public int countSpeakersWithTalks() {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT t.speaker_id) FROM talk t JOIN speaker s ON s.id = t.speaker_id", Integer.class);
    }

    // The talks that count towards a speaker's cap (submission-cap change request): every talk they present, as main
    // speaker or co-speaker, that is still in the running, i.e. not REJECTED or WITHDRAWN.
    private static final String ACTIVE_TALKS_OF_SPEAKER = "SELECT COUNT(*) FROM talk t "
            + "WHERE t.status NOT IN ('REJECTED', 'WITHDRAWN') "
            + "AND (t.speaker_id = ? OR EXISTS (SELECT 1 FROM co_speaker c WHERE c.talk_id = t.id AND c.speaker_id = ?))";

    /**
     * Locks the speaker's row for the rest of the transaction. Every change to a speaker's cap (a submission, or being
     * added as a co-speaker) takes this lock before counting, so concurrent ones can't both see room under the limit.
     */
    public void lockSpeaker(long speakerId) {
        jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", speakerId);
    }

    /** How many talks count towards this speaker's cap; see {@link #ACTIVE_TALKS_OF_SPEAKER}. */
    public int activeTalks(long speakerId) {
        Integer n = jdbc.queryForObject(ACTIVE_TALKS_OF_SPEAKER, Integer.class, speakerId, speakerId);
        return n == null ? 0 : n;
    }

    /**
     * Inserts the talk only if fewer than {@code max} talks count towards the speaker's cap. Returns the new id, or
     * null. The speaker row is locked first (see {@link #lockSpeaker}).
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        lockSpeaker(speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE (" + ACTIVE_TALKS_OF_SPEAKER + ") < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // old version, raced when the mobile app double-posted
    // public long insert(String title, String format, int duration, String level, long speakerId) {
    //     jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id) VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
    //             title, format, duration, level, speakerId);
    //     return jdbc.queryForObject("SELECT currval('talk_id_seq')", Long.class);
    // }
}

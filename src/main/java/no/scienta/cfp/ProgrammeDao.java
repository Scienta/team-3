package no.scienta.cfp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The programme: which talk sits in each room+slot. The slot lock row (see {@link #lockSlot}) serialises
 * everything touching one slot, so the slot-wide rules can't be raced across rooms.
 */
@Repository
public class ProgrammeDao {

    private final JdbcTemplate jdbc;

    public ProgrammeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the slot's row for the rest of the transaction. The slots are seeded in schema.sql. */
    public void lockSlot(String slot) {
        jdbc.queryForList("SELECT slot FROM programme_slot WHERE slot = ? FOR UPDATE", slot);
    }

    /** The talk in this room+slot, or null if empty. */
    public Long talkAt(String room, String slot) {
        List<Long> ids = jdbc.queryForList("SELECT talk_id FROM programme WHERE room = ? AND slot = ?",
                Long.class, room, slot);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Whether this talk is already placed somewhere on the programme. */
    public boolean isScheduled(long talkId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM programme WHERE talk_id = ?", Integer.class, talkId);
        return n != null && n > 0;
    }

    /** Everyone presenting in this slot: the main speakers and co-speakers of its talks. */
    public Set<Long> peopleInSlot(String slot) {
        List<Long> ids = jdbc.queryForList(
                "SELECT t.speaker_id FROM programme p JOIN talk t ON t.id = p.talk_id WHERE p.slot = ? "
                        + "UNION "
                        + "SELECT cs.speaker_id FROM programme p JOIN co_speaker cs ON cs.talk_id = p.talk_id WHERE p.slot = ?",
                Long.class, slot, slot);
        return new HashSet<>(ids);
    }

    /**
     * Every scheduled talk with its current title, format and main speaker, in programme order: earliest slot
     * first, then by room. Slot ids S1..S5 run in start-time order and room ids R1..R3 sort as named, so
     * ordering by the ids is the day's order. The talk is read as it is now, whatever its status.
     */
    public List<ProgrammeRow> findAll() {
        return jdbc.query("SELECT p.slot, p.room, t.id, t.title, t.format, t.speaker_id "
                        + "FROM programme p JOIN talk t ON t.id = p.talk_id ORDER BY p.slot, p.room",
                (rs, n) -> {
                    ProgrammeRow row = new ProgrammeRow();
                    row.slot = rs.getString("slot");
                    row.room = rs.getString("room");
                    row.talkId = rs.getLong("id");
                    row.title = rs.getString("title");
                    row.format = rs.getString("format");
                    row.mainSpeakerId = rs.getLong("speaker_id");
                    return row;
                });
    }

    public void place(String room, String slot, long talkId) {
        jdbc.update("INSERT INTO programme (room, slot, talk_id) VALUES (?, ?, ?)", room, slot, talkId);
    }

    public int remove(String room, String slot) {
        return jdbc.update("DELETE FROM programme WHERE room = ? AND slot = ?", room, slot);
    }
}

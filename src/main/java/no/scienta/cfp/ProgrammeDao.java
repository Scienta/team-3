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

    public void place(String room, String slot, long talkId) {
        jdbc.update("INSERT INTO programme (room, slot, talk_id) VALUES (?, ?, ?)", room, slot, talkId);
    }

    public int remove(String room, String slot) {
        return jdbc.update("DELETE FROM programme WHERE room = ? AND slot = ?", room, slot);
    }
}

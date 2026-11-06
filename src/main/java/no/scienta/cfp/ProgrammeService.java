package no.scienta.cfp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Programme scheduling: the committee places accepted talks into rooms and slots under the rules of the
 * scheduling spec. The rules are checked when a talk is placed; a talk that changes afterwards leaves the
 * programme as it is. A per-slot row lock makes concurrent placements safe (see {@link ProgrammeDao}).
 */
@Service
public class ProgrammeService {

    private final TalkDao talkDao;
    private final CoSpeakerDao coSpeakerDao;
    private final ProgrammeDao programmeDao;
    private final SpeakerDao speakerDao;

    public ProgrammeService(TalkDao talkDao, CoSpeakerDao coSpeakerDao, ProgrammeDao programmeDao, SpeakerDao speakerDao) {
        this.talkDao = talkDao;
        this.coSpeakerDao = coSpeakerDao;
        this.programmeDao = programmeDao;
        this.speakerDao = speakerDao;
    }

    @Transactional
    public ProgrammeEntry place(String room, String slot, Map<String, Object> body) {
        requireRoomAndSlot(room, slot);
        Object raw = body == null ? null : body.get("talkId");
        if (!(raw instanceof Integer || raw instanceof Long)) {   // 1.5, "7" or true is not a talk id
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talkId must be a whole number");
        }
        long talkId = ((Number) raw).longValue();

        programmeDao.lockSlot(slot);                       // serialise everything touching this slot

        TalkDto talk = talkDao.findById(talkId);
        if (talk == null) {
            // an unknown talk in the body is a bad request, as POST /talks treats an unknown speakerId
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talk " + talkId + " does not exist");
        }
        if (!"ACCEPTED".equals(talk.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only accepted talks go on the programme");
        }
        if (talk.durationMinutes > Venue.slot(slot).minutes) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "talk does not fit slot " + slot);
        }

        Long here = programmeDao.talkAt(room, slot);
        if (here != null && here == talkId) {
            return entry(room, slot, talkId);              // already there: idempotent
        }
        if (here != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, room + "/" + slot + " is already taken");
        }
        if (programmeDao.isScheduled(talkId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "talk is already on the programme; take it out first");
        }

        Set<Long> people = peopleOf(talkId, talk.speakerId);
        Set<Long> busy = programmeDao.peopleInSlot(slot);
        people.retainAll(busy);
        if (!people.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a speaker is already in another room in slot " + slot);
        }

        try {
            programmeDao.place(room, slot, talkId);
        } catch (DuplicateKeyException e) {             // the same talk placed in another slot at the same moment
            throw new ResponseStatusException(HttpStatus.CONFLICT, "talk is already on the programme; take it out first");
        }
        return entry(room, slot, talkId);
    }

    public ProgrammeEntry get(String room, String slot) {
        requireRoomAndSlot(room, slot);
        Long talkId = programmeDao.talkAt(room, slot);
        if (talkId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing scheduled in " + room + "/" + slot);
        }
        return entry(room, slot, talkId);
    }

    @Transactional
    public void remove(String room, String slot) {
        requireRoomAndSlot(room, slot);
        programmeDao.lockSlot(slot);
        programmeDao.remove(room, slot);                   // idempotent: no-op if already empty
    }

    /**
     * The programme export: one row per scheduled talk, in programme order, with the talk and its speakers as
     * they are now. Speaker names are fetched in one batch (pool is capped at 5, no N+1). A single read-only
     * transaction, REPEATABLE READ via {@link TransactionConfig} (never the isolation attribute: it leaks), makes
     * the three queries see the same snapshot, so a row can't pair a talk with co-speakers from a later moment.
     */
    @Transactional(readOnly = true)
    public List<ProgrammeRow> export() {
        List<ProgrammeRow> rows = programmeDao.findAll();
        List<Long> talkIds = new ArrayList<>();
        Set<Long> speakerIds = new LinkedHashSet<>();
        for (ProgrammeRow row : rows) {
            talkIds.add(row.talkId);
            speakerIds.add(row.mainSpeakerId);
        }
        Map<Long, List<Long>> coSpeakers = coSpeakerDao.findByTalks(talkIds);
        coSpeakers.values().forEach(speakerIds::addAll);
        Map<Long, String> names = speakerDao.findNames(speakerIds);
        for (ProgrammeRow row : rows) {
            Venue.SlotTime t = Venue.slot(row.slot);
            row.start = t.start;
            row.end = t.end;
            addName(row, names.get(row.mainSpeakerId));
            for (Long co : coSpeakers.getOrDefault(row.talkId, List.of())) {
                addName(row, names.get(co));
            }
        }
        return rows;
    }

    private static void addName(ProgrammeRow row, String name) {
        if (name != null) {                                // speakers can't be deleted; guard anyway
            row.speakers.add(name);
        }
    }

    private Set<Long> peopleOf(long talkId, long mainSpeakerId) {
        Set<Long> people = new HashSet<>();
        people.add(mainSpeakerId);
        List<Long> cos = coSpeakerDao.findByTalk(talkId);
        people.addAll(cos);
        return people;
    }

    private static void requireRoomAndSlot(String room, String slot) {
        // the room and slot are the resource in the path: one that isn't on the venue (R0, r1, slot2) is a 404
        if (!Venue.isRoom(room)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown room " + room);
        }
        if (!Venue.isSlot(slot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown slot " + slot);
        }
    }

    private static ProgrammeEntry entry(String room, String slot, long talkId) {
        Venue.SlotTime t = Venue.slot(slot);
        return new ProgrammeEntry(room, slot, t.start, t.end, talkId);
    }
}

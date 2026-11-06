package no.scienta.cfp;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public ProgrammeService(TalkDao talkDao, CoSpeakerDao coSpeakerDao, ProgrammeDao programmeDao) {
        this.talkDao = talkDao;
        this.coSpeakerDao = coSpeakerDao;
        this.programmeDao = programmeDao;
    }

    @Transactional
    public ProgrammeEntry place(String room, String slot, Map<String, Object> body) {
        requireRoomAndSlot(room, slot);
        Object raw = body == null ? null : body.get("talkId");
        if (!(raw instanceof Number)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talkId is required");
        }
        long talkId = ((Number) raw).longValue();

        programmeDao.lockSlot(slot);                       // serialise everything touching this slot

        TalkDto talk = talkDao.findById(talkId);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
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

        programmeDao.place(room, slot, talkId);
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

    private Set<Long> peopleOf(long talkId, long mainSpeakerId) {
        Set<Long> people = new HashSet<>();
        people.add(mainSpeakerId);
        List<Long> cos = coSpeakerDao.findByTalk(talkId);
        people.addAll(cos);
        return people;
    }

    private static void requireRoomAndSlot(String room, String slot) {
        if (!Venue.isRoom(room)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown room " + room);
        }
        if (!Venue.isSlot(slot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown slot " + slot);
        }
    }

    private static ProgrammeEntry entry(String room, String slot, long talkId) {
        Venue.SlotTime t = Venue.slot(slot);
        return new ProgrammeEntry(room, slot, t.start, t.end, talkId);
    }
}

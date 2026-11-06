package no.scienta.cfp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Speakers, talks and ratings.
 *
 * TODO split this up (CFP-140)
 */
@Service
public class ConferenceService {

    private static final Logger log = LoggerFactory.getLogger(ConferenceService.class);

    private static final Map<String, Integer> DURATIONS = new HashMap<>();

    static {
        DURATIONS.put("LIGHTNING_10", 10);
        DURATIONS.put("LIGHTNING_20", 20);
        DURATIONS.put("PRESENTATION", 45);
        DURATIONS.put("WORKSHOP", 120);
        // DURATIONS.put("KEYNOTE", 60);  // keynotes are invited, not submitted (2021)
    }

    private final SpeakerDao speakerDao;
    private final TalkDao talkDao;
    private final RatingDao ratingDao;
    private final CoSpeakerDao coSpeakerDao;
    private final RegistrationDao registrationDao;
    private final NotificationService notifications;

    public ConferenceService(SpeakerDao speakerDao, TalkDao talkDao, RatingDao ratingDao,
                             CoSpeakerDao coSpeakerDao, RegistrationDao registrationDao,
                             NotificationService notifications) {
        this.speakerDao = speakerDao;
        this.talkDao = talkDao;
        this.ratingDao = ratingDao;
        this.coSpeakerDao = coSpeakerDao;
        this.registrationDao = registrationDao;
        this.notifications = notifications;
    }

    // ---------------------------------------------------------------- speakers

    @Transactional
    public Map<String, Object> registerSpeaker(SpeakerForm form) {
        if (!Utils.isValidEmail(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (speakerDao.emailTaken(form.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        long id;
        try {
            id = speakerDao.insert(form.getName(), form.getEmail(), form.getBio());
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        log.info("registered speaker {}", id);
        notifications.speakerRegistered(form.getEmail(), form.getName());
        Map<String, Object> speaker = new LinkedHashMap<>();
        speaker.put("id", id);
        speaker.put("name", form.getName());
        speaker.put("email", form.getEmail());
        speaker.put("bio", form.getBio());
        return speaker;
    }

    public Map<String, Object> getSpeaker(long id) {
        Map<String, Object> speaker = speakerDao.findById(id);
        if (speaker == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        return speaker;
    }

    @Transactional
    public Map<String, Object> updateSpeaker(long id, String name, String email, String bio) {
        Map<String, Object> existing = getSpeaker(id);
        if (Utils.isEmpty(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!Utils.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (bio != null && bio.length() > Utils.MAX_BIO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bio too long");
        }
        if (!email.equalsIgnoreCase((String) existing.get("email")) && speakerDao.emailTaken(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        try {
            speakerDao.update(id, name, email, bio);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        return getSpeaker(id);
    }

    public List<Map<String, Object>> searchSpeakers(String q) {
        return speakerDao.search(q);
    }

    // ---------------------------------------------------------------- talks

    @Transactional
    public TalkDto submitTalk(TalkForm form) {
        if (form.title == null || form.title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }
        if (form.format == null || !DURATIONS.containsKey(form.format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be one of " + DURATIONS.keySet());
        }
        if (form.level == null || !Arrays.asList("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(form.level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown level");
        }
        if (form.speakerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId is required");
        }
        if (!speakerDao.exists(form.speakerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speaker " + form.speakerId + " does not exist");
        }

        int duration = DURATIONS.get(form.format);

        Long id = talkDao.insertIfBelow(Utils.MAX_TALKS, form.title, form.format, duration, form.level,
                form.speakerId, System.currentTimeMillis());
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a speaker can submit at most " + Utils.MAX_TALKS + " talks");
        }
        Map<String, Object> speaker = speakerDao.findById(form.speakerId);
        notifications.talkSubmitted((String) speaker.get("email"), form.title);
        return getTalk(id);
    }

    public TalkDto getTalk(long id) {
        TalkDto talk = talkDao.findById(id);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        addRatings(Collections.singletonList(talk));
        talk.coSpeakerIds = coSpeakerDao.findByTalk(id);
        return talk;
    }

    public List<TalkDto> getTalksForSpeaker(long speakerId) {
        if (speakerDao.findById(speakerId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        List<TalkDto> talks = talkDao.findBySpeaker(speakerId);
        addRatings(talks);
        addCoSpeakers(talks);
        return talks;
    }

    public List<TalkDto> listTalks(String sort, Long speakerId) {
        if (sort != null && !sort.equals("rating")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort must be 'rating'");
        }
        List<TalkDto> talks = speakerId == null ? talkDao.findAll() : talkDao.findBySpeaker(speakerId);
        addRatings(talks);
        addCoSpeakers(talks);
        if ("rating".equals(sort)) {
            List<TalkDto> rated = new ArrayList<>();
            List<TalkDto> unrated = new ArrayList<>();
            for (TalkDto t : talks) {
                if (t.ratingCount > 0) {
                    rated.add(t);
                } else {
                    unrated.add(t);
                }
            }
            rated.sort((a, b) -> {
                int c = Double.compare(b.averageRating, a.averageRating);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(b.ratingCount, a.ratingCount);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.id, b.id);
            });
            rated.addAll(unrated);
            return rated;
        }
        return talks;
    }

    // Fills ratingCount and averageRating for a list of talks in one query, rather than one per talk.
    private void addRatings(List<TalkDto> talks) {
        Map<Long, long[]> byTalk = ratingDao.statsByTalks(ids(talks));
        for (TalkDto t : talks) {
            long[] stats = byTalk.get(t.id);
            int count = stats == null ? 0 : (int) stats[0];
            t.ratingCount = count;
            t.averageRating = count == 0 ? null : calculateAverage(stats[1], count);
        }
    }

    private static List<Long> ids(List<TalkDto> talks) {
        List<Long> ids = new ArrayList<>();
        for (TalkDto t : talks) {
            ids.add(t.id);
        }
        return ids;
    }

    // 1 decimal, half up (4.25 -> 4.3)
    private Double calculateAverage(long total, int count) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue();
        // return Utils.roundRating((double) total / count);
    }

    // Fills coSpeakerIds for a list of talks in one query, rather than one per talk (the pool is capped at 5).
    private void addCoSpeakers(List<TalkDto> talks) {
        Map<Long, List<Long>> byTalk = coSpeakerDao.findByTalks(ids(talks));
        for (TalkDto t : talks) {
            t.coSpeakerIds = byTalk.getOrDefault(t.id, new ArrayList<>());
        }
    }

    /** Speaker names by id for a list of talks, in one query. Talks whose speaker is gone have no entry. */
    public Map<Long, String> speakerNames(List<TalkDto> talks) {
        Set<Long> ids = new LinkedHashSet<>();
        for (TalkDto t : talks) {
            ids.add(t.speakerId);
        }
        return speakerDao.findNames(ids);
    }

    // ---------------------------------------------------------------- co-speakers

    @Transactional
    public TalkDto addCoSpeaker(long talkId, long speakerId) {
        Long mainSpeakerId = coSpeakerDao.lockTalk(talkId);
        if (mainSpeakerId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        List<Long> current = coSpeakerDao.findByTalk(talkId);
        if (!current.contains(speakerId)) {                   // repeat add changes nothing
            if (!speakerDao.exists(speakerId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
            }
            if (mainSpeakerId == speakerId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the main speaker cannot be a co-speaker");
            }
            if (current.size() >= 2) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "a talk can have at most two co-speakers");
            }
            coSpeakerDao.insert(talkId, speakerId);
        }
        return getTalk(talkId);
    }

    @Transactional
    public TalkDto removeCoSpeaker(long talkId, long speakerId) {
        if (coSpeakerDao.lockTalk(talkId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        if (!speakerDao.exists(speakerId)) {                 // same as adding: an unknown speaker is a 404
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
        }
        if (coSpeakerDao.delete(talkId, speakerId) == 0) {   // not a co-speaker (any more): 404
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker is not a co-speaker of this talk");
        }
        return getTalk(talkId);
    }

    // ---------------------------------------------------------------- acceptance

    // The talk-status state machine, written out so every legal move is explicit and testable.
    // DECISIONS: the values the committee's decision endpoint accepts at all (the Decision enum).
    private static final List<String> DECISIONS = Arrays.asList("ACCEPTED", "REJECTED", "WAITLISTED");
    // DECISION_TARGETS: from a given status, which decisions the committee may make. ACCEPTED additionally
    // needs two reviewers (checked in decide). ACCEPTED/REJECTED are final and WITHDRAWN is out for good,
    // so from them no decision is legal -- in particular nothing ever moves a talk back to SUBMITTED.
    private static final Map<String, Set<String>> DECISION_TARGETS = new HashMap<>();
    // WITHDRAW_FROM: the statuses a speaker may withdraw from (anything but already REJECTED or WITHDRAWN).
    private static final Set<String> WITHDRAW_FROM = new HashSet<>(Arrays.asList("SUBMITTED", "ACCEPTED", "WAITLISTED"));

    static {
        DECISION_TARGETS.put("SUBMITTED", new HashSet<>(DECISIONS));
        DECISION_TARGETS.put("WAITLISTED", new HashSet<>(Arrays.asList("ACCEPTED", "REJECTED")));  // not again
        DECISION_TARGETS.put("ACCEPTED", Collections.emptySet());
        DECISION_TARGETS.put("REJECTED", Collections.emptySet());
        DECISION_TARGETS.put("WITHDRAWN", Collections.emptySet());
    }

    @Transactional
    public TalkDto decide(long talkId, Map<String, Object> body) {
        String current = talkDao.lockStatus(talkId);        // row lock: concurrent deciders are serialised
        if (current == null) {                               // an unknown talk is a 404 whatever the body says
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        Object status = body == null ? null : body.get("status");
        if (!(status instanceof String) || !DECISIONS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + DECISIONS);
        }
        String target = (String) status;
        if (!DECISION_TARGETS.getOrDefault(current, Collections.emptySet()).contains(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + current + " talk cannot become " + target);
        }
        if (target.equals("ACCEPTED") && ratingDao.distinctReviewers(talkId) < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a talk needs at least two reviewers to be accepted");
        }
        talkDao.updateStatus(talkId, target);
        return getTalk(talkId);
    }

    @Transactional
    public TalkDto withdraw(long talkId, Map<String, Object> body) {
        String current = talkDao.lockStatus(talkId);        // row lock: serialise against concurrent decisions
        if (current == null) {                               // an unknown talk is a 404 whatever the body says
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        Object sid = body == null ? null : body.get("speakerId");
        if (!(sid instanceof Integer || sid instanceof Long)) {   // 1.5, "7" or true is not a speaker id
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId must be a whole number");
        }
        long speakerId = ((Number) sid).longValue();
        TalkDto talk = talkDao.findById(talkId);
        if (talk.speakerId != speakerId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the speaker can withdraw their own talk");
        }
        if (!WITHDRAW_FROM.contains(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + current + " talk cannot be withdrawn");
        }
        talkDao.updateStatus(talkId, "WITHDRAWN");
        return getTalk(talkId);
    }

    // ---------------------------------------------------------------- workshop registration

    // Checks run 404 (no such talk), then 400 (bad body), then 409 (the rules), like the acceptance endpoints.
    @Transactional
    public Map<String, Object> registerForWorkshop(long talkId, Map<String, Object> body) {
        Map<String, Object> talk = registrationDao.lockTalk(talkId);   // row lock: a rush is serialised per workshop
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        Object name = body == null ? null : body.get("name");
        Object email = body == null ? null : body.get("email");
        if (!(name instanceof String) || Utils.isEmpty((String) name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!(email instanceof String) || !Utils.isValidEmail((String) email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a valid email is required");
        }
        // one rule in the spec: only a workshop the committee accepted takes registrations
        if (!"WORKSHOP".equals(talk.get("format")) || !"ACCEPTED".equals(talk.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only an accepted workshop takes registrations");
        }
        if (registrationDao.registered(talkId, (String) email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered");
        }
        if (registrationDao.count(talkId) >= RegistrationDao.CAPACITY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the workshop is full (" + RegistrationDao.CAPACITY + " attendees)");
        }
        try {
            return registrationDao.insert(talkId, (String) name, (String) email);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered");
        }
    }

    // Registrations stay listed after the workshop is withdrawn; a talk that isn't a workshop just has none.
    public List<Map<String, Object>> workshopRegistrations(long talkId) {
        if (!talkDao.exists(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        return registrationDao.findByWorkshop(talkId);
    }

    // ---------------------------------------------------------------- statistics

    public Map<String, Object> statistics() {
        List<TalkDto> talks = listTalks(null, null);
        Map<String, Integer> byFormat = new LinkedHashMap<>();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        int minutes = 0;
        double ratingSum = 0;
        int rated = 0;
        for (TalkDto t : talks) {
            byFormat.put(t.format, byFormat.getOrDefault(t.format, 0) + 1);
            byLevel.put(t.level, byLevel.getOrDefault(t.level, 0) + 1);
            byStatus.put(t.status, byStatus.getOrDefault(t.status, 0) + 1);
            minutes += t.durationMinutes;
            if (t.averageRating != null) {
                ratingSum += t.averageRating;
                rated++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("speakers", speakerDao.count());
        stats.put("speakersWithTalks", talkDao.countSpeakersWithTalks());
        stats.put("talks", talks.size());
        stats.put("byFormat", byFormat);
        stats.put("byLevel", byLevel);
        stats.put("byStatus", byStatus);
        stats.put("totalHours", minutes / 60.0);
        stats.put("averageRating", rated == 0 ? null : Utils.roundRating(ratingSum / rated));
        stats.put("generated", Utils.now());
        return stats;
    }

    // ---------------------------------------------------------------- ratings

    @Transactional
    public Map<String, Object> rate(long talkId, long reviewerId, Map<String, Object> body) {
        if (reviewerId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reviewerId must be positive");
        }
        Object score = body.get("score");
        if (!(score instanceof Integer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be a whole number");
        }
        int s = (Integer) score;
        if (s < 1 || s > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "score must be 1-5");
        }
        if (!talkDao.exists(talkId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        String now = Utils.now();
        ratingDao.save(talkId, reviewerId, s, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("talkId", talkId);
        result.put("reviewerId", reviewerId);
        result.put("score", s);
        result.put("ratedAtDisplay", Utils.usShort(Utils.parse(now)));
        return result;
    }
}

package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ConferenceServiceTest {

    private SpeakerDao speakerDao;
    private TalkDao talkDao;
    private RatingDao ratingDao;
    private CoSpeakerDao coSpeakerDao;
    private ConferenceService service;

    @BeforeEach
    void setUp() {
        speakerDao = mock(SpeakerDao.class);
        talkDao = mock(TalkDao.class);
        ratingDao = mock(RatingDao.class);
        coSpeakerDao = mock(CoSpeakerDao.class);
        service = new ConferenceService(speakerDao, talkDao, ratingDao, coSpeakerDao, mock(RegistrationDao.class),
                mock(NotificationService.class));
    }

    @Test
    void talkWithoutTitleIsRejected() {
        TalkForm form = form();
        form.title = " ";
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void unknownFormatIsRejected() {
        TalkForm form = form();
        form.format = "KEYNOTE";
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void unknownSpeakerIsRejected() {
        when(speakerDao.exists(7L)).thenReturn(false);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.submitTalk(form()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void emailRegisteredConcurrentlyIsConflictInAnyServerLanguage() {
        SpeakerForm form = new SpeakerForm();
        form.setName("Ada");
        form.setEmail("ada@example.com");
        when(speakerDao.emailTaken("ada@example.com")).thenReturn(false);
        when(speakerDao.insert("Ada", "ada@example.com", null))
                .thenThrow(new DuplicateKeyException("FEIL: duplisert nøkkelverdi bryter unik-skranke"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.registerSpeaker(form));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void duplicateEmailIsConflict() {
        SpeakerForm form = new SpeakerForm();
        form.setName("Ada");
        form.setEmail("ada@example.com");
        when(speakerDao.emailTaken("ada@example.com")).thenReturn(true);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.registerSpeaker(form));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void invalidEmailIsRejectedOnRegistration() {
        SpeakerForm form = new SpeakerForm();
        form.setName("Ada");
        form.setEmail("not-an-email");
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.registerSpeaker(form));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void emailTakenConcurrentlyOnUpdateIsConflict() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("email", "ada@example.com");
        when(speakerDao.findById(1L)).thenReturn(existing);
        when(speakerDao.emailTaken("grace@example.com")).thenReturn(false);
        when(speakerDao.update(1L, "Ada", "grace@example.com", null))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.updateSpeaker(1L, "Ada", "grace@example.com", null));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }

    @Test
    void bioOver500IsRejectedOnUpdate() {
        Map<String, Object> existing = new HashMap<>();
        existing.put("email", "ada@example.com");
        when(speakerDao.findById(1L)).thenReturn(existing);
        String bio = new String(new char[Utils.MAX_BIO + 1]).replace('\0', 'x');
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.updateSpeaker(1L, "Ada", "ada@example.com", bio));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    private TalkForm form() {
        TalkForm form = new TalkForm();
        form.title = "Legacy is a feeling";
        form.format = "PRESENTATION";
        form.level = "INTERMEDIATE";
        form.speakerId = 7L;
        return form;
    }
}

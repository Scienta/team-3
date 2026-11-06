package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Workshop registration: only accepted workshops, at most 20 attendees even in a rush, one registration per email
 * (any case) per workshop, listed in the order they registered. Runs the real app on a random port with a throwaway
 * database, like {@link CoSpeakersTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopRegistrationTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern EMAIL = Pattern.compile("\"email\":\"([^\"]*)\"");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void registersForAnAcceptedWorkshopAndListsInOrder() throws Exception {
        long ws = acceptedWorkshop();

        HttpResponse<String> r1 = register(ws, "Ada", "Ada@Example.com");
        assertEquals(201, r1.statusCode(), r1.body());
        assertTrue(r1.body().contains("\"workshopId\":" + ws), r1.body());
        assertTrue(r1.body().contains("\"name\":\"Ada\""), r1.body());
        assertTrue(r1.body().contains("\"email\":\"Ada@Example.com\""), r1.body());   // as typed
        assertEquals(201, register(ws, "Grace", "grace@example.com").statusCode());
        assertEquals(201, register(ws, "Linus", "linus@example.com").statusCode());

        HttpResponse<String> list = get("/workshops/" + ws + "/registrations");
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(List.of("Ada@Example.com", "grace@example.com", "linus@example.com"), emails(list.body()));
    }

    @Test
    void oneRegistrationPerEmailInAnyCase() throws Exception {
        long ws = acceptedWorkshop();
        assertEquals(201, register(ws, "Ada", "ada@example.com").statusCode());
        assertEquals(409, register(ws, "Ada again", "ADA@Example.COM").statusCode());
        assertEquals(1, emails(get("/workshops/" + ws + "/registrations").body()).size());

        // the same person can register for another workshop
        assertEquals(201, register(acceptedWorkshop(), "Ada", "ada@example.com").statusCode());
    }

    @Test
    void nameAndEmailAreRequired() throws Exception {
        long ws = acceptedWorkshop();
        for (String body : new String[]{"{}", "{\"name\":\"Ada\"}", "{\"email\":\"ada@example.com\"}",
                "{\"name\":\"\",\"email\":\"ada@example.com\"}", "{\"name\":\"Ada\",\"email\":\"\"}",
                "{\"name\":\"Ada\",\"email\":\"not-an-email\"}", "{\"name\":7,\"email\":\"ada@example.com\"}",
                "{\"name\":\"Ada\",\"email\":true}"}) {
            assertEquals(400, post("/workshops/" + ws + "/registrations", body).statusCode(), body);
        }
        assertEquals(400, post("/workshops/" + ws + "/registrations", null).statusCode());
        assertEquals(List.of(), emails(get("/workshops/" + ws + "/registrations").body()));
    }

    @Test
    void onlyAcceptedWorkshopsTakeRegistrations() throws Exception {
        long submitted = newTalk(newSpeaker(), "WORKSHOP");
        assertEquals(409, register(submitted, "Ada", "ada@example.com").statusCode());

        long waitlisted = newTalk(newSpeaker(), "WORKSHOP");
        assertEquals(200, status(waitlisted, "WAITLISTED").statusCode());
        assertEquals(409, register(waitlisted, "Ada", "ada@example.com").statusCode());

        long rejected = newTalk(newSpeaker(), "WORKSHOP");
        assertEquals(200, status(rejected, "REJECTED").statusCode());
        assertEquals(409, register(rejected, "Ada", "ada@example.com").statusCode());

        assertEquals(List.of(), emails(get("/workshops/" + submitted + "/registrations").body()));
    }

    @Test
    void anAcceptedTalkThatIsNotAWorkshopTakesNoRegistrations() throws Exception {
        long talk = newTalk(newSpeaker(), "PRESENTATION");
        rate(talk, 1, 4);
        rate(talk, 2, 4);
        assertEquals(200, status(talk, "ACCEPTED").statusCode());
        assertEquals(409, register(talk, "Ada", "ada@example.com").statusCode());
        HttpResponse<String> list = get("/workshops/" + talk + "/registrations");
        assertEquals(200, list.statusCode(), list.body());
        assertEquals("[]", list.body());
    }

    @Test
    void unknownWorkshopIsNotFound() throws Exception {
        assertEquals(404, register(99999999L, "Ada", "ada@example.com").statusCode());
        assertEquals(404, post("/workshops/99999999/registrations", "{}").statusCode());   // 404 before the body
        assertEquals(404, get("/workshops/99999999/registrations").statusCode());
    }

    @Test
    void withdrawnWorkshopKeepsItsListButTakesNoMore() throws Exception {
        long speaker = newSpeaker();
        long ws = acceptedWorkshop(speaker);
        assertEquals(201, register(ws, "Ada", "ada@example.com").statusCode());
        assertEquals(201, register(ws, "Grace", "grace@example.com").statusCode());

        assertEquals(200, http.send(req("POST", "/talks/" + ws + "/withdraw", "{\"speakerId\":" + speaker + "}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(409, register(ws, "Linus", "linus@example.com").statusCode());
        assertEquals(List.of("ada@example.com", "grace@example.com"),
                emails(get("/workshops/" + ws + "/registrations").body()));
    }

    @Test
    void atMostTwentyAttendees() throws Exception {
        long ws = acceptedWorkshop();
        for (int i = 0; i < 20; i++) {
            assertEquals(201, register(ws, "A" + i, "a" + i + "@example.com").statusCode());
        }
        assertEquals(409, register(ws, "Late", "late@example.com").statusCode());
        assertEquals(20, emails(get("/workshops/" + ws + "/registrations").body()).size());
    }

    @Test
    void aRushNeverExceedsTwentyOrDoubleRegisters() throws Exception {
        long ws = acceptedWorkshop();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sent.add(registerAsync(ws, "A" + i, "a" + i + "@example.com"));
        }
        for (int i = 0; i < 5; i++) {   // the same person, typed differently, at the same moment
            sent.add(registerAsync(ws, "Dup", (i % 2 == 0 ? "dup" : "DUP") + "@example.com"));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            int code = f.get().statusCode();
            assertTrue(code == 201 || code == 409, "unexpected " + code);
            created += code == 201 ? 1 : 0;
        }
        List<String> emails = emails(get("/workshops/" + ws + "/registrations").body());
        assertEquals(20, created);
        assertEquals(20, emails.size());
        assertTrue(emails.stream().map(String::toLowerCase).distinct().count() == emails.size(), emails.toString());
    }

    // ---------------------------------------------------------------- helpers

    private long acceptedWorkshop() throws Exception {
        return acceptedWorkshop(newSpeaker());
    }

    private long acceptedWorkshop(long speakerId) throws Exception {
        long ws = newTalk(speakerId, "WORKSHOP");
        rate(ws, 1, 4);
        rate(ws, 2, 5);
        assertEquals(200, status(ws, "ACCEPTED").statusCode());
        return ws;
    }

    private long newSpeaker() throws Exception {
        String json = "{\"name\":\"Speaker\",\"email\":\"s-" + UUID.randomUUID() + "@example.com\"}";
        HttpResponse<String> res = post("/speakers", json);
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private long newTalk(long speakerId, String format) throws Exception {
        String json = "{\"title\":\"Hands on\",\"format\":\"" + format + "\",\"level\":\"BEGINNER\",\"speakerId\":"
                + speakerId + "}";
        HttpResponse<String> res = post("/talks", json);
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private void rate(long talkId, long reviewerId, int score) throws Exception {
        HttpResponse<String> res = http.send(req("PUT", "/talks/" + talkId + "/ratings/" + reviewerId,
                "{\"score\":" + score + "}"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    private HttpResponse<String> status(long talkId, String target) throws Exception {
        return http.send(req("PUT", "/talks/" + talkId + "/status", "{\"status\":\"" + target + "\"}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(long workshopId, String name, String email) throws Exception {
        return registerAsync(workshopId, name, email).get();
    }

    private CompletableFuture<HttpResponse<String>> registerAsync(long workshopId, String name, String email) {
        return http.sendAsync(req("POST", "/workshops/" + workshopId + "/registrations",
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}"), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(req("POST", path, json), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(req("GET", path, null), HttpResponse.BodyHandlers.ofString());
    }

    private static long firstId(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return Long.parseLong(m.group(1));
    }

    private static List<String> emails(String body) {
        List<String> emails = new ArrayList<>();
        Matcher m = EMAIL.matcher(body);
        while (m.find()) {
            emails.add(m.group(1));
        }
        return emails;
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}

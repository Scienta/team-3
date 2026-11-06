package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme scheduling: accepted talks placed into rooms and slots under the scheduling spec. Runs the
 * real app on a random port with a throwaway database, like {@link AcceptanceTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingTest {

    private final ObjectMapper om = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // One database is shared by the whole test class, so empty the programme before each test.
    @BeforeEach
    void clearProgramme() throws Exception {
        for (String room : Venue.ROOMS) {
            for (String slot : Venue.SLOTS) {
                del(room, slot);
            }
        }
    }

    // R1: only accepted talks go on the programme
    @Test
    void onlyAcceptedTalksCanBeScheduled() throws Exception {
        long speaker = newSpeaker();
        long submitted = newTalk(speaker, "PRESENTATION");
        assertEquals(409, put("R1", "S1", submitted).statusCode());

        accept(submitted);
        HttpResponse<String> ok = put("R1", "S1", submitted);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("R1", field(ok.body(), "room"));
        assertEquals("S1", field(ok.body(), "slot"));
    }

    // R2: a workshop needs the long slot (S4); everything else fits anywhere
    @Test
    void workshopNeedsTheLongSlot() throws Exception {
        long ws = acceptedTalk("WORKSHOP");
        assertEquals(409, put("R1", "S1", ws).statusCode());   // 60-min slot
        assertEquals(409, put("R1", "S2", ws).statusCode());
        assertEquals(200, put("R1", "S4", ws).statusCode());   // the long slot

        long pres = acceptedTalk("PRESENTATION");
        assertEquals(200, put("R2", "S4", pres).statusCode()); // non-workshop fits the long slot too
        long pres2 = acceptedTalk("PRESENTATION");
        assertEquals(200, put("R2", "S1", pres2).statusCode());
    }

    // R3: one talk per room+slot
    @Test
    void oneTalkPerRoomAndSlot() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        long b = acceptedTalk("PRESENTATION");
        assertEquals(200, put("R1", "S1", a).statusCode());
        assertEquals(409, put("R1", "S1", b).statusCode());    // occupied
    }

    // R4: a talk is on the programme only once; to move it, take it out first
    @Test
    void aTalkIsOnTheProgrammeOnlyOnce() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        assertEquals(200, put("R1", "S1", a).statusCode());
        assertEquals(409, put("R2", "S2", a).statusCode());    // already scheduled
        assertEquals(204, del("R1", "S1").statusCode());
        assertEquals(200, put("R2", "S2", a).statusCode());    // moved after removal
    }

    // re-putting the same talk into the same cell is idempotent
    @Test
    void puttingTheSameTalkInTheSameCellIsIdempotent() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        assertEquals(200, put("R1", "S1", a).statusCode());
        HttpResponse<String> again = put("R1", "S1", a);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(String.valueOf(a), field(again.body(), "talkId"));
    }

    // R5: nobody speaks in two rooms in the same slot (main speaker or co-speaker)
    @Test
    void nobodySpeaksInTwoRoomsInTheSameSlot() throws Exception {
        long shared = newSpeaker();
        long a = acceptedTalk("PRESENTATION", shared);                 // main speaker = shared
        long b = newTalk(newSpeaker(), "PRESENTATION");
        addCoSpeaker(b, shared);                                       // shared is a co-speaker of b
        accept(b);

        assertEquals(200, put("R1", "S1", a).statusCode());
        assertEquals(409, put("R2", "S1", b).statusCode());           // clash on `shared` in S1
        assertEquals(200, put("R2", "S2", b).statusCode());           // a different slot is fine
    }

    // R6: rules are checked at scheduling time; a later change leaves the programme as it is
    @Test
    void laterChangesDoNotDisturbTheProgramme() throws Exception {
        long owner = newSpeaker();
        long talk = acceptedTalk("PRESENTATION", owner);
        assertEquals(200, put("R1", "S1", talk).statusCode());
        // withdrawing the talk afterwards does not remove it from the programme
        assertEquals(200, http.send(req("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + owner + "}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, get("R1", "S1").statusCode());
        assertEquals(String.valueOf(talk), field(get("R1", "S1").body(), "talkId"));
    }

    // GET shows the entry with slot times; empty cells and bad coordinates are handled
    @Test
    void getAndValidation() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        assertEquals(404, get("R3", "S5").statusCode());              // empty cell
        put("R3", "S4", a);
        HttpResponse<String> e = get("R3", "S4");
        assertEquals(200, e.statusCode());
        assertEquals("13:30", field(e.body(), "start"));
        assertEquals("15:30", field(e.body(), "end"));

        assertEquals(404, get("RX", "S1").statusCode());             // unknown room
        assertEquals(404, get("R1", "S9").statusCode());             // unknown slot
        assertEquals(404, get("r1", "S1").statusCode());             // rooms are written exactly R1..R3
        assertEquals(404, put("R0", "S2", 1L).statusCode());
        assertEquals(404, put("R2", "slot2", 1L).statusCode());
        assertEquals(400, put("R1", "S1", null).statusCode());       // missing talkId
        for (String body : new String[]{"{\"talkId\":1.5}", "{\"talkId\":\"1\"}", "{\"talkId\":true}",
                "{\"talkId\":null}"}) {
            assertEquals(400, http.send(req("PUT", "/programme/R1/S1", body),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), body);
        }
        assertEquals(400, put("R1", "S1", 99999999L).statusCode());  // unknown talk in the body
    }

    // DELETE is idempotent
    @Test
    void deleteIsIdempotent() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        put("R1", "S1", a);
        assertEquals(204, del("R1", "S1").statusCode());
        assertEquals(404, get("R1", "S1").statusCode());
        assertEquals(204, del("R1", "S1").statusCode());             // already empty
    }

    // R7: concurrent placements that would break the speaker rule can't both win
    @Test
    void concurrentPlacementsStayLegal() throws Exception {
        long shared = newSpeaker();
        long a = acceptedTalk("PRESENTATION", shared);
        long b = acceptedTalk("PRESENTATION", shared);               // same main speaker
        CompletableFuture<HttpResponse<String>> fa =
                http.sendAsync(req("PUT", "/programme/R1/S1", "{\"talkId\":" + a + "}"), HttpResponse.BodyHandlers.ofString());
        CompletableFuture<HttpResponse<String>> fb =
                http.sendAsync(req("PUT", "/programme/R2/S1", "{\"talkId\":" + b + "}"), HttpResponse.BodyHandlers.ofString());
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : List.of(fa, fb)) {
            HttpResponse<String> r = f.join();
            assertTrue(r.statusCode() == 200 || r.statusCode() == 409, r.statusCode() + " " + r.body());
            if (r.statusCode() == 200) {
                ok++;
            }
        }
        assertEquals(1, ok, "exactly one of the clashing placements may win");
    }

    // Regression (Step 8): scheduling and workshop-registration both touch workshops but share no state —
    // placing/removing a workshop on the programme must not disturb its registrations, or vice versa.
    @Test
    void schedulingDoesNotDisturbWorkshopRegistrations() throws Exception {
        long ws = acceptedTalk("WORKSHOP");
        register(ws, "Ada", "ada-" + UUID.randomUUID() + "@example.com");
        register(ws, "Grace", "grace-" + UUID.randomUUID() + "@example.com");
        assertEquals(2, registrationCount(ws));

        assertEquals(200, put("R1", "S4", ws).statusCode());   // schedule it (workshop -> long slot)
        assertEquals(2, registrationCount(ws));                // scheduling left registrations alone
        assertEquals(String.valueOf(ws), field(get("R1", "S4").body(), "talkId"));

        assertEquals(204, del("R1", "S4").statusCode());        // take it off the programme
        assertEquals(2, registrationCount(ws));                // removal left registrations alone
    }

    // ---------------------------------------------------------------- helpers

    private void register(long workshopId, String name, String email) throws Exception {
        HttpResponse<String> res = http.send(req("POST", "/workshops/" + workshopId + "/registrations",
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}"), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
    }

    @SuppressWarnings("unchecked")
    private int registrationCount(long workshopId) throws Exception {
        HttpResponse<String> res = http.send(req("GET", "/workshops/" + workshopId + "/registrations", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return om.readValue(res.body(), List.class).size();
    }

    private long newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(req("POST", "/speakers",
                "{\"name\":\"Speaker\",\"email\":\"s-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return id(res.body());
    }

    private long newTalk(long speakerId, String format) throws Exception {
        HttpResponse<String> res = http.send(req("POST", "/talks",
                "{\"title\":\"T " + UUID.randomUUID() + "\",\"format\":\"" + format + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return id(res.body());
    }

    /** An accepted talk with a fresh main speaker. */
    private long acceptedTalk(String format) throws Exception {
        return acceptedTalk(format, newSpeaker());
    }

    /** An accepted talk owned by the given main speaker. */
    private long acceptedTalk(String format, long speakerId) throws Exception {
        long talk = newTalk(speakerId, format);
        accept(talk);
        return talk;
    }

    private void accept(long talkId) throws Exception {
        rate(talkId, 1, 4);
        rate(talkId, 2, 5);
        HttpResponse<String> res = http.send(req("PUT", "/talks/" + talkId + "/status", "{\"status\":\"ACCEPTED\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    private void rate(long talkId, long reviewerId, int score) throws Exception {
        HttpResponse<String> res = http.send(req("PUT", "/talks/" + talkId + "/ratings/" + reviewerId,
                "{\"score\":" + score + "}"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    private void addCoSpeaker(long talkId, long speakerId) throws Exception {
        HttpResponse<String> res = http.send(req("PUT", "/talks/" + talkId + "/co-speakers/" + speakerId, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    private HttpResponse<String> put(String room, String slot, Long talkId) throws Exception {
        String body = talkId == null ? "{}" : "{\"talkId\":" + talkId + "}";
        return http.send(req("PUT", "/programme/" + room + "/" + slot, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String room, String slot) throws Exception {
        return http.send(req("GET", "/programme/" + room + "/" + slot, null), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> del(String room, String slot) throws Exception {
        return http.send(req("DELETE", "/programme/" + room + "/" + slot, null), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private long id(String body) throws Exception {
        return ((Number) om.readValue(body, Map.class).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private String field(String body, String key) throws Exception {
        Object v = om.readValue(body, Map.class).get(key);
        return v == null ? null : String.valueOf(v instanceof Number ? ((Number) v).longValue() : v);
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}

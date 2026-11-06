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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Submission cap change request: a speaker's 3 talks count every talk they present, as main speaker or co-speaker,
 * except the rejected and withdrawn ones; the cap holds when submissions and co-speaker invitations arrive together.
 * Runs the real app on a random port with a throwaway database, like {@link CoSpeakersTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionCapTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    // R1: co-speaking counts towards the cap of a new submission
    @Test
    void coSpeakingCountsTowardsTheCap() throws Exception {
        long s = newSpeaker();
        coSpeak(newTalk(newSpeaker()), s);
        coSpeak(newTalk(newSpeaker()), s);
        assertEquals(201, submit(s).statusCode());
        assertEquals(409, submit(s).statusCode());
    }

    // R4: a speaker at the cap can't be added as a co-speaker to a talk still in the running
    @Test
    void aSpeakerAtTheCapCantBeAddedAsCoSpeaker() throws Exception {
        long s = newSpeaker();
        for (int i = 0; i < 3; i++) {
            assertEquals(201, submit(s).statusCode());
        }
        long other = newTalk(newSpeaker());
        assertEquals(409, put("/talks/" + other + "/co-speakers/" + s).statusCode());
        assertTrue(get("/talks/" + other).contains("\"coSpeakerIds\":[]"));

        long waitlisted = newTalk(newSpeaker());
        assertEquals(200, status(waitlisted, "WAITLISTED").statusCode());   // waitlisted is still in the running
        assertEquals(409, put("/talks/" + waitlisted + "/co-speakers/" + s).statusCode());
    }

    // R5: being added to a rejected or withdrawn talk is fine at the cap, since it doesn't count
    @Test
    void addingToARejectedOrWithdrawnTalkIsFineAtTheCap() throws Exception {
        long s = newSpeaker();
        for (int i = 0; i < 3; i++) {
            submit(s);
        }
        long rejected = newTalk(newSpeaker());
        assertEquals(200, status(rejected, "REJECTED").statusCode());
        assertEquals(200, put("/talks/" + rejected + "/co-speakers/" + s).statusCode());

        long owner = newSpeaker();
        long withdrawn = newTalk(owner);
        assertEquals(200, withdraw(withdrawn, owner).statusCode());
        assertEquals(200, put("/talks/" + withdrawn + "/co-speakers/" + s).statusCode());
    }

    // R2: rejected and withdrawn talks don't count, for the main speaker or the co-speakers; the place is free at once
    @Test
    void rejectedAndWithdrawnTalksDontCount() throws Exception {
        long s = newSpeaker();
        long t1 = firstId(submit(s).body());
        long t2 = firstId(submit(s).body());
        submit(s);
        assertEquals(409, submit(s).statusCode());

        assertEquals(200, status(t1, "REJECTED").statusCode());
        assertEquals(201, submit(s).statusCode());                 // the rejected talk's place, straight away
        assertEquals(409, submit(s).statusCode());

        assertEquals(200, withdraw(t2, s).statusCode());
        assertEquals(201, submit(s).statusCode());                 // and the withdrawn one's

        // as a co-speaker too: a rejected talk I co-speak on frees my place
        long c = newSpeaker();
        long a = newTalk(newSpeaker());
        coSpeak(a, c);
        submit(c);
        submit(c);
        assertEquals(409, submit(c).statusCode());
        assertEquals(200, status(a, "REJECTED").statusCode());
        assertEquals(201, submit(c).statusCode());
    }

    // R3: taking someone off a talk as co-speaker frees their place
    @Test
    void removingACoSpeakerFreesTheirPlace() throws Exception {
        long s = newSpeaker();
        long a = newTalk(newSpeaker());
        coSpeak(a, s);
        submit(s);
        submit(s);
        assertEquals(409, submit(s).statusCode());
        assertEquals(200, delete("/talks/" + a + "/co-speakers/" + s).statusCode());
        assertEquals(201, submit(s).statusCode());
    }

    // R6: re-adding someone who already co-speaks changes nothing, even at the cap (they're already counted)
    @Test
    void reAddingAnExistingCoSpeakerAtTheCapIsANoOp() throws Exception {
        long s = newSpeaker();
        long a = newTalk(newSpeaker());
        coSpeak(a, s);
        submit(s);
        submit(s);
        HttpResponse<String> again = put("/talks/" + a + "/co-speakers/" + s);
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.body().contains("\"coSpeakerIds\":[" + s + "]"), again.body());
    }

    // R6: an accepted talk is still in the running, so it counts (for the co-speaker too)
    @Test
    void acceptedTalksCount() throws Exception {
        long s = newSpeaker();
        long a = newTalk(newSpeaker());
        coSpeak(a, s);
        rate(a, 1, 4);
        rate(a, 2, 5);
        assertEquals(200, status(a, "ACCEPTED").statusCode());
        submit(s);
        submit(s);
        assertEquals(409, submit(s).statusCode());
    }

    // R2b: a withdrawn talk frees the places of its co-speakers, not only of its main speaker
    @Test
    void aWithdrawnTalkFreesItsCoSpeakersPlaces() throws Exception {
        long s = newSpeaker();
        long owner = newSpeaker();
        long a = newTalk(owner);
        coSpeak(a, s);
        submit(s);
        submit(s);
        assertEquals(409, submit(s).statusCode());
        assertEquals(200, withdraw(a, owner).statusCode());
        assertEquals(201, submit(s).statusCode());
    }

    // R8/R10: a speaker already over the new cap (from before the change) keeps everything, but gets nothing new
    @Test
    void aSpeakerOverTheCapKeepsEverythingButGetsNothingNew() throws Exception {
        long s = newSpeaker();
        for (int i = 0; i < 3; i++) {
            assertEquals(201, submit(s).statusCode());
        }
        List<Long> co = new ArrayList<>();
        for (int i = 0; i < 2; i++) {   // co-speaking on two more, as the old rule allowed (seeded behind the API)
            long t = newTalk(newSpeaker());
            co.add(t);
            jdbc.update("INSERT INTO co_speaker (talk_id, speaker_id) VALUES (?, ?)", t, s);
        }
        // 5 talks in the running: nothing is taken away
        assertEquals(3, ID.matcher(get("/speakers/" + s + "/talks")).results().count());
        for (long t : co) {
            assertTrue(get("/talks/" + t).contains("\"coSpeakerIds\":[" + s + "]"));
        }
        // but nothing new: no submission, no new invitation; re-adding an existing one is still a no-op
        assertEquals(409, submit(s).statusCode());
        assertEquals(409, put("/talks/" + newTalk(newSpeaker()) + "/co-speakers/" + s).statusCode());
        assertEquals(200, put("/talks/" + co.get(0) + "/co-speakers/" + s).statusCode());
        // leaving talks frees places, but there is room only once under 3: 5 -> 4 and 4 -> 3 are both still full
        assertEquals(200, delete("/talks/" + co.get(0) + "/co-speakers/" + s).statusCode());
        assertEquals(409, submit(s).statusCode());
        assertEquals(200, delete("/talks/" + co.get(1) + "/co-speakers/" + s).statusCode());
        assertEquals(409, submit(s).statusCode());
    }

    // R9: a speaker's own talk list still shows only their main-speaker talks
    @Test
    void ownTalkListShowsOnlyMainSpeakerTalks() throws Exception {
        long s = newSpeaker();
        long own = firstId(submit(s).body());
        coSpeak(newTalk(newSpeaker()), s);
        String list = get("/speakers/" + s + "/talks");
        assertTrue(list.contains("\"id\":" + own), list);
        assertEquals(1, ID.matcher(list).results().count(), list);
    }

    // R7: submissions and co-speaker invitations piling in together never take a speaker past 3
    @Test
    void theCapHoldsWhenSubmissionsAndInvitationsArriveTogether() throws Exception {
        for (int round = 0; round < 3; round++) {
            long s = newSpeaker();
            List<Long> others = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                others.add(newTalk(newSpeaker()));
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                sent.add(http.sendAsync(req("POST", "/talks", talkJson(s)), HttpResponse.BodyHandlers.ofString()));
                sent.add(http.sendAsync(req("PUT", "/talks/" + others.get(i) + "/co-speakers/" + s, null),
                        HttpResponse.BodyHandlers.ofString()));
            }
            int won = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.get();
                int code = res.statusCode();
                assertTrue(code == 200 || code == 201 || code == 409, code + " " + res.body());
                won += code == 409 ? 0 : 1;
            }
            int owned = (int) ID.matcher(get("/speakers/" + s + "/talks")).results().count();
            int coSpeaking = 0;
            for (long t : others) {
                coSpeaking += get("/talks/" + t).contains("\"coSpeakerIds\":[" + s + "]") ? 1 : 0;
            }
            assertEquals(3, won, "round " + round);
            assertEquals(3, owned + coSpeaking, "round " + round);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void rate(long talkId, long reviewerId, int score) throws Exception {
        HttpResponse<String> res = http.send(req("PUT", "/talks/" + talkId + "/ratings/" + reviewerId,
                "{\"score\":" + score + "}"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    private void coSpeak(long talkId, long speakerId) throws Exception {
        HttpResponse<String> res = put("/talks/" + talkId + "/co-speakers/" + speakerId);
        assertEquals(200, res.statusCode(), res.body());
    }

    private long newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(req("POST", "/speakers",
                "{\"name\":\"Speaker\",\"email\":\"s-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private long newTalk(long speakerId) throws Exception {
        HttpResponse<String> res = submit(speakerId);
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private HttpResponse<String> submit(long speakerId) throws Exception {
        return http.send(req("POST", "/talks", talkJson(speakerId)), HttpResponse.BodyHandlers.ofString());
    }

    private static String talkJson(long speakerId) {
        return "{\"title\":\"Capped\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}";
    }

    private HttpResponse<String> status(long talkId, String target) throws Exception {
        return http.send(req("PUT", "/talks/" + talkId + "/status", "{\"status\":\"" + target + "\"}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withdraw(long talkId, long speakerId) throws Exception {
        return http.send(req("POST", "/talks/" + talkId + "/withdraw", "{\"speakerId\":" + speakerId + "}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path) throws Exception {
        return http.send(req("PUT", path, null), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(req("DELETE", path, null), HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws Exception {
        return http.send(req("GET", path, null), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static long firstId(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return Long.parseLong(m.group(1));
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}

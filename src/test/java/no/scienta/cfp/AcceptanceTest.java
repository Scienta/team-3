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
 * The acceptance state machine: a talk goes SUBMITTED -> ACCEPTED / REJECTED / WAITLISTED by committee
 * decision, or -> WITHDRAWN by its speaker, under the rules in the acceptance spec. Runs the real app on
 * a random port with a throwaway database, like {@link TalksTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptanceTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z]+)\"");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // R1: committee rejects or waitlists a submitted talk (no ratings needed)
    @Test
    void submittedCanBeRejectedOrWaitlistedWithoutRatings() throws Exception {
        long talk = newTalk(newSpeaker());
        HttpResponse<String> res = status(talk, "WAITLISTED");
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("WAITLISTED", status(res.body()));

        long talk2 = newTalk(newSpeaker());
        assertEquals("REJECTED", status(status(talk2, "REJECTED").body()));
    }

    // R2: a waitlisted talk can later be accepted (with ratings) or rejected
    @Test
    void waitlistedCanLaterBeAcceptedOrRejected() throws Exception {
        long talk = newTalk(newSpeaker());
        status(talk, "WAITLISTED");
        rate(talk, 1, 4);
        rate(talk, 2, 5);
        assertEquals("ACCEPTED", status(status(talk, "ACCEPTED").body()));

        long talk2 = newTalk(newSpeaker());
        status(talk2, "WAITLISTED");
        assertEquals("REJECTED", status(status(talk2, "REJECTED").body()));
    }

    // R3: ACCEPTED and REJECTED are final
    @Test
    void acceptedAndRejectedAreFinal() throws Exception {
        long accepted = newTalk(newSpeaker());
        rate(accepted, 1, 4);
        rate(accepted, 2, 4);
        assertEquals(200, status(accepted, "ACCEPTED").statusCode());
        assertEquals(409, status(accepted, "REJECTED").statusCode());
        assertEquals(409, status(accepted, "WAITLISTED").statusCode());
        assertEquals("ACCEPTED", currentStatus(accepted));

        long rejected = newTalk(newSpeaker());
        assertEquals(200, status(rejected, "REJECTED").statusCode());
        assertEquals(409, status(rejected, "ACCEPTED").statusCode());
        assertEquals("REJECTED", currentStatus(rejected));
    }

    // R4: accepting needs at least two reviewers
    @Test
    void acceptNeedsTwoReviewers() throws Exception {
        long talk = newTalk(newSpeaker());
        assertEquals(409, status(talk, "ACCEPTED").statusCode());   // zero reviewers
        rate(talk, 1, 5);
        assertEquals(409, status(talk, "ACCEPTED").statusCode());   // one reviewer
        rate(talk, 2, 5);
        assertEquals(200, status(talk, "ACCEPTED").statusCode());   // two reviewers
        assertEquals("ACCEPTED", currentStatus(talk));
    }

    // R5: a reviewer who rates twice still counts as one reviewer
    @Test
    void sameReviewerTwiceCountsOnce() throws Exception {
        long talk = newTalk(newSpeaker());
        rate(talk, 1, 3);
        rate(talk, 1, 5);                                           // same reviewer again
        assertEquals(409, status(talk, "ACCEPTED").statusCode());   // still only one distinct reviewer
        rate(talk, 2, 4);
        assertEquals(200, status(talk, "ACCEPTED").statusCode());
    }

    // R6 + R9: a speaker withdraws their own submitted talk; status shows on read
    @Test
    void ownerCanWithdrawAndStatusShows() throws Exception {
        long speaker = newSpeaker();
        long talk = newTalk(speaker);
        HttpResponse<String> res = withdraw(talk, speaker);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("WITHDRAWN", status(res.body()));
        assertEquals("WITHDRAWN", currentStatus(talk));
        assertTrue(get("/speakers/" + speaker + "/talks").contains("\"status\":\"WITHDRAWN\""));
    }

    // R6: an accepted talk can still be withdrawn by its speaker
    @Test
    void acceptedTalkCanBeWithdrawn() throws Exception {
        long speaker = newSpeaker();
        long talk = newTalk(speaker);
        rate(talk, 1, 4);
        rate(talk, 2, 4);
        status(talk, "ACCEPTED");
        assertEquals(200, withdraw(talk, speaker).statusCode());
        assertEquals("WITHDRAWN", currentStatus(talk));
    }

    // R7: nobody but the speaker can withdraw, and a refused withdrawal changes nothing
    @Test
    void nonOwnerCannotWithdraw() throws Exception {
        long owner = newSpeaker();
        long other = newSpeaker();
        long talk = newTalk(owner);
        assertEquals(403, withdraw(talk, other).statusCode());
        assertEquals("SUBMITTED", currentStatus(talk));
    }

    // R8: a rejected or withdrawn talk cannot be withdrawn
    @Test
    void rejectedOrWithdrawnCannotBeWithdrawn() throws Exception {
        long speaker = newSpeaker();
        long rejected = newTalk(speaker);
        status(rejected, "REJECTED");
        assertEquals(409, withdraw(rejected, speaker).statusCode());

        long withdrawn = newTalk(speaker);
        withdraw(withdrawn, speaker);
        assertEquals(409, withdraw(withdrawn, speaker).statusCode());   // out for good
    }

    // decision validation: bad/missing status is a 400
    @Test
    void invalidDecisionIsBadRequest() throws Exception {
        long talk = newTalk(newSpeaker());
        assertEquals(400, status(talk, "SUBMITTED").statusCode());      // not a committee decision
        assertEquals(400, status(talk, "WITHDRAWN").statusCode());
        assertEquals(400, status(talk, "FOO").statusCode());
        assertEquals(400, http.send(req("PUT", "/talks/" + talk + "/status", "{}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals("SUBMITTED", currentStatus(talk));
    }

    // withdraw validation: a missing or non-integer speakerId is a 400, and nothing changes
    @Test
    void withdrawWithoutSpeakerIdIsBadRequest() throws Exception {
        long speaker = newSpeaker();
        long talk = newTalk(speaker);
        for (String body : new String[]{"{}", "{\"speakerId\":1.5}", "{\"speakerId\":" + speaker + ".5}",
                "{\"speakerId\":\"" + speaker + "\"}", "{\"speakerId\":true}", "{\"speakerId\":null}"}) {
            assertEquals(400, http.send(req("POST", "/talks/" + talk + "/withdraw", body),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), body);
        }
        assertEquals("SUBMITTED", currentStatus(talk));
        assertEquals(200, withdraw(talk, speaker).statusCode());
    }

    // unknown talk is a 404 on both endpoints
    @Test
    void unknownTalkIsNotFound() throws Exception {
        assertEquals(404, status(99999999L, "REJECTED").statusCode());
        assertEquals(404, withdraw(99999999L, 1).statusCode());
    }

    // explicit guard: from the final/out states, no committee decision is legal and nothing changes
    @Test
    void everyIllegalDecisionIsRejectedAndChangesNothing() throws Exception {
        for (String from : new String[]{"ACCEPTED", "REJECTED", "WITHDRAWN"}) {
            for (String target : new String[]{"ACCEPTED", "REJECTED", "WAITLISTED"}) {
                long talk = inState(from, newSpeaker());
                assertEquals(409, status(talk, target).statusCode(), from + " -> " + target);
                assertEquals(from, currentStatus(talk), from + " unchanged after " + target);
            }
        }
    }

    // explicit guard: a rejected or withdrawn talk cannot be withdrawn, and nothing changes
    @Test
    void illegalWithdrawalsAreRejectedAndChangeNothing() throws Exception {
        for (String from : new String[]{"REJECTED", "WITHDRAWN"}) {
            long speaker = newSpeaker();
            long talk = inState(from, speaker);
            assertEquals(409, withdraw(talk, speaker).statusCode(), from + " withdraw");
            assertEquals(from, currentStatus(talk), from + " unchanged after withdraw");
        }
    }

    // the question that prompted this: a withdrawn or rejected talk can never go back to SUBMITTED
    @Test
    void withdrawnOrRejectedCannotReturnToSubmitted() throws Exception {
        long rejected = inState("REJECTED", newSpeaker());
        assertEquals(400, status(rejected, "SUBMITTED").statusCode());   // SUBMITTED is not a committee decision
        assertEquals("REJECTED", currentStatus(rejected));

        long withdrawn = inState("WITHDRAWN", newSpeaker());
        assertEquals(400, status(withdrawn, "SUBMITTED").statusCode());
        assertEquals("WITHDRAWN", currentStatus(withdrawn));
    }

    // a waitlisted talk can only be accepted or rejected later, not waitlisted again
    @Test
    void waitlistingAWaitlistedTalkIsRefused() throws Exception {
        long talk = inState("WAITLISTED", newSpeaker());
        assertEquals(409, status(talk, "WAITLISTED").statusCode());
        assertEquals("WAITLISTED", currentStatus(talk));
    }

    // the talk is looked up before the body is validated: an unknown talk is a 404 even with a bad body
    @Test
    void unknownTalkIsNotFoundEvenWithABadBody() throws Exception {
        assertEquals(404, status(99999999L, "FOO").statusCode());
        assertEquals(404, http.send(req("PUT", "/talks/99999999/status", null),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, http.send(req("POST", "/talks/99999999/withdraw", "{}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, http.send(req("POST", "/talks/99999999/withdraw", null),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // R10: concurrent decisions on one talk never reach an illegal state; exactly one terminal decision wins
    @Test
    void concurrentDecisionsStayLegal() throws Exception {
        long talk = newTalk(newSpeaker());
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sent.add(http.sendAsync(req("PUT", "/talks/" + talk + "/status", "{\"status\":\"REJECTED\"}"),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                ok++;
            }
        }
        assertEquals(1, ok);                                   // REJECTED is terminal: only the first wins
        assertEquals("REJECTED", currentStatus(talk));
    }

    // ---------------------------------------------------------------- helpers

    private long newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(req("POST", "/speakers",
                "{\"name\":\"Speaker\",\"email\":\"s-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private long newTalk(long speakerId) throws Exception {
        HttpResponse<String> res = http.send(req("POST", "/talks",
                "{\"title\":\"T " + UUID.randomUUID() + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        assertEquals("SUBMITTED", status(res.body()));
        return firstId(res.body());
    }

    /** Creates a talk owned by {@code speakerId} and drives it into the given status by the legal path. */
    private long inState(String state, long speakerId) throws Exception {
        long talk = newTalk(speakerId);
        switch (state) {
            case "SUBMITTED":
                break;
            case "WAITLISTED":
                assertEquals(200, status(talk, "WAITLISTED").statusCode());
                break;
            case "ACCEPTED":
                rate(talk, 1, 4);
                rate(talk, 2, 4);
                assertEquals(200, status(talk, "ACCEPTED").statusCode());
                break;
            case "REJECTED":
                assertEquals(200, status(talk, "REJECTED").statusCode());
                break;
            case "WITHDRAWN":
                assertEquals(200, withdraw(talk, speakerId).statusCode());
                break;
            default:
                throw new IllegalArgumentException(state);
        }
        assertEquals(state, currentStatus(talk));
        return talk;
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

    private HttpResponse<String> withdraw(long talkId, long speakerId) throws Exception {
        return http.send(req("POST", "/talks/" + talkId + "/withdraw", "{\"speakerId\":" + speakerId + "}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private String get(String path) throws Exception {
        return http.send(req("GET", path, null), HttpResponse.BodyHandlers.ofString()).body();
    }

    private String currentStatus(long talkId) throws Exception {
        return status(get("/talks/" + talkId));
    }

    private static long firstId(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return Long.parseLong(m.group(1));
    }

    private static String status(String body) {
        Matcher m = STATUS.matcher(body);
        assertTrue(m.find(), "no status in " + body);
        return m.group(1);
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}

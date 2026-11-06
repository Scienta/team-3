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
 * Co-speakers: up to two extra registered speakers per talk, in the order added, and never the main speaker.
 * Runs the real app on a random port with a throwaway database, like {@link TalksTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoSpeakersTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern CO = Pattern.compile("\"coSpeakerIds\":\\[([^\\]]*)]");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void addsAndRemovesCoSpeakersInOrder() throws Exception {
        long main = newSpeaker();
        long co1 = newSpeaker();
        long co2 = newSpeaker();
        long talk = newTalk(main);

        assertEquals(List.of(), coSpeakerIds(get("/talks/" + talk)));

        HttpResponse<String> r1 = put("/talks/" + talk + "/co-speakers/" + co1);
        assertEquals(200, r1.statusCode(), r1.body());
        assertEquals(List.of(co1), coSpeakerIds(r1.body()));

        HttpResponse<String> r2 = put("/talks/" + talk + "/co-speakers/" + co2);
        assertEquals(200, r2.statusCode(), r2.body());
        assertEquals(List.of(co1, co2), coSpeakerIds(r2.body()));

        // read back shows the same order
        assertEquals(List.of(co1, co2), coSpeakerIds(get("/talks/" + talk)));

        HttpResponse<String> del = delete("/talks/" + talk + "/co-speakers/" + co1);
        assertEquals(200, del.statusCode(), del.body());
        assertEquals(List.of(co2), coSpeakerIds(del.body()));
    }

    @Test
    void addingAnExistingCoSpeakerChangesNothing() throws Exception {
        long main = newSpeaker();
        long co1 = newSpeaker();
        long talk = newTalk(main);

        put("/talks/" + talk + "/co-speakers/" + co1);
        HttpResponse<String> again = put("/talks/" + talk + "/co-speakers/" + co1);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(List.of(co1), coSpeakerIds(again.body()));
    }

    @Test
    void removingSomeoneWhoIsNotACoSpeakerIsANoOp() throws Exception {
        long main = newSpeaker();
        long other = newSpeaker();
        long talk = newTalk(main);

        HttpResponse<String> del = delete("/talks/" + talk + "/co-speakers/" + other);
        assertEquals(200, del.statusCode(), del.body());
        assertEquals(List.of(), coSpeakerIds(del.body()));
    }

    @Test
    void mainSpeakerCannotBeACoSpeaker() throws Exception {
        long main = newSpeaker();
        long talk = newTalk(main);

        HttpResponse<String> res = put("/talks/" + talk + "/co-speakers/" + main);
        assertEquals(409, res.statusCode(), res.body());
    }

    @Test
    void atMostTwoCoSpeakers() throws Exception {
        long main = newSpeaker();
        long co1 = newSpeaker();
        long co2 = newSpeaker();
        long co3 = newSpeaker();
        long talk = newTalk(main);

        assertEquals(200, put("/talks/" + talk + "/co-speakers/" + co1).statusCode());
        assertEquals(200, put("/talks/" + talk + "/co-speakers/" + co2).statusCode());
        HttpResponse<String> third = put("/talks/" + talk + "/co-speakers/" + co3);
        assertEquals(409, third.statusCode(), third.body());
    }

    @Test
    void unknownSpeakerIsNotFound() throws Exception {
        long main = newSpeaker();
        long talk = newTalk(main);

        HttpResponse<String> res = put("/talks/" + talk + "/co-speakers/99999999");
        assertEquals(404, res.statusCode(), res.body());
    }

    @Test
    void unknownTalkIsNotFound() throws Exception {
        long co1 = newSpeaker();
        assertEquals(404, put("/talks/99999999/co-speakers/" + co1).statusCode());
        assertEquals(404, delete("/talks/99999999/co-speakers/" + co1).statusCode());
    }

    @Test
    void coSpeakingDoesNotUseUpTheSubmissionLimitOrShowInOwnTalks() throws Exception {
        long main = newSpeaker();
        long busy = newSpeaker();
        // busy reaches the 3-talk limit as a main speaker
        long talkA = newTalk(busy);
        newTalk(busy);
        newTalk(busy);
        HttpResponse<String> fourth = http.send(req("POST", "/talks", talkJson(busy)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, fourth.statusCode(), fourth.body());

        // ...yet can still co-speak on someone else's talk
        long talk = newTalk(main);
        assertEquals(200, put("/talks/" + talk + "/co-speakers/" + busy).statusCode());

        // and co-speaking does not add to busy's own talk list (only main-speaker talks)
        assertEquals(3, countIds(get("/speakers/" + busy + "/talks")));
        assertTrue(get("/speakers/" + busy + "/talks").contains("\"id\":" + talkA));
    }

    @Test
    void concurrentAddsNeverExceedTwoCoSpeakers() throws Exception {
        long main = newSpeaker();
        long talk = newTalk(main);
        List<Long> cos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cos.add(newSpeaker());
        }
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (long co : cos) {
            sent.add(http.sendAsync(req("PUT", "/talks/" + talk + "/co-speakers/" + co, null),
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
        assertEquals(2, ok);
        assertEquals(2, coSpeakerIds(get("/talks/" + talk)).size());
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
        HttpResponse<String> res = http.send(req("POST", "/talks", talkJson(speakerId)),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private static String talkJson(long speakerId) {
        return "{\"title\":\"T " + UUID.randomUUID() + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}";
    }

    private String get(String path) throws Exception {
        return http.send(req("GET", path, null), HttpResponse.BodyHandlers.ofString()).body();
    }

    private HttpResponse<String> put(String path) throws Exception {
        return http.send(req("PUT", path, null), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return http.send(req("DELETE", path, null), HttpResponse.BodyHandlers.ofString());
    }

    private static long firstId(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return Long.parseLong(m.group(1));
    }

    private static int countIds(String body) {
        Matcher m = ID.matcher(body);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static List<Long> coSpeakerIds(String body) {
        Matcher m = CO.matcher(body);
        assertTrue(m.find(), "no coSpeakerIds in " + body);
        List<Long> ids = new ArrayList<>();
        String inner = m.group(1).trim();
        if (!inner.isEmpty()) {
            for (String part : inner.split(",")) {
                ids.add(Long.parseLong(part.trim()));
            }
        }
        return ids;
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}

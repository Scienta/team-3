package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression guard for the shared Talk shape. Every endpoint that returns a Talk must keep the full
 * contract of the earlier specs — the talks spec fields (incl. the exact submittedAt/submittedOn
 * formats), the ratings spec fields (averageRating/ratingCount), and the co-speakers field
 * (coSpeakerIds) — even after a status change (acceptance) or a co-speaker edit. The later features
 * (co-speakers, acceptance) must not drop or corrupt any of these wherever a talk is shown.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TalkShapeRegressionTest {

    // talks spec: submittedAt is yyyy-MM-dd'T'HH:mm:ss.SSS+00:00 (always ms, offset +00:00, never Z)
    private static final String AT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00$";
    private static final String ON = "^\\d{4}-\\d{2}-\\d{2}$";
    private static final List<String> FORMATS = List.of("LIGHTNING_10", "LIGHTNING_20", "PRESENTATION", "WORKSHOP");
    private static final List<String> LEVELS = List.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final List<String> STATUSES = List.of("SUBMITTED", "ACCEPTED", "REJECTED", "WAITLISTED", "WITHDRAWN");

    private final ObjectMapper om = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void everyTalkReturningEndpointKeepsTheSharedShape() throws Exception {
        long speaker = newSpeaker();
        long co = newSpeaker();
        long talk = newTalk(speaker);

        // POST /talks (talks spec): fresh talk, no ratings, no co-speakers
        assertTalkShape(post("/talks", talkJson(speaker)).body(), "SUBMITTED", 0, null, List.of());

        // two different reviewers rate it -> averageRating 4.5, ratingCount 2 (ratings spec)
        rate(talk, 1, 4);
        rate(talk, 2, 5);

        // PUT co-speakers (co-speakers spec) returns the full Talk, now with ratings + the co-speaker
        assertTalkShape(put("/talks/" + talk + "/co-speakers/" + co).body(), "SUBMITTED", 2, 4.5, List.of(co));

        // GET /talks/{id} and the two list endpoints keep the same shape
        assertTalkShape(get("/talks/" + talk), "SUBMITTED", 2, 4.5, List.of(co));
        assertTalkShape(firstOfArray(get("/speakers/" + speaker + "/talks")), "SUBMITTED", 2, 4.5, List.of(co));
        assertTalkShape(firstOfArray(get("/talks?sort=rating")), "SUBMITTED", 2, 4.5, List.of(co));

        // PUT /status (acceptance): status flips to ACCEPTED, but ratings + co-speaker survive
        assertTalkShape(status(talk, "ACCEPTED").body(), "ACCEPTED", 2, 4.5, List.of(co));

        // DELETE co-speakers: co-speaker gone, but the ACCEPTED status + ratings survive
        assertTalkShape(delete("/talks/" + talk + "/co-speakers/" + co).body(), "ACCEPTED", 2, 4.5, List.of());

        // POST /withdraw (acceptance): status WITHDRAWN, ratings still shown
        assertTalkShape(withdraw(talk, speaker).body(), "WITHDRAWN", 2, 4.5, List.of());
    }

    @SuppressWarnings("unchecked")
    private void assertTalkShape(String body, String status, int ratingCount, Double averageRating, List<Long> coSpeakerIds)
            throws Exception {
        Map<String, Object> t = om.readValue(body, Map.class);
        // talks spec fields
        assertTrue(t.get("id") instanceof Number, "id: " + body);
        assertTrue(t.get("title") instanceof String, "title: " + body);
        assertTrue(FORMATS.contains(t.get("format")), "format: " + body);
        assertTrue(LEVELS.contains(t.get("level")), "level: " + body);
        assertTrue(t.get("speakerId") instanceof Number, "speakerId: " + body);
        assertTrue(t.get("durationMinutes") instanceof Number, "durationMinutes: " + body);
        assertTrue(STATUSES.contains(t.get("status")), "status enum: " + body);
        assertEquals(status, t.get("status"), "status value: " + body);
        assertTrue(((String) t.get("submittedAt")).matches(AT), "submittedAt format: " + body);
        assertTrue(((String) t.get("submittedOn")).matches(ON), "submittedOn format: " + body);
        // ratings spec fields
        assertTrue(t.containsKey("ratingCount") && t.get("ratingCount") instanceof Number, "ratingCount: " + body);
        assertEquals(ratingCount, ((Number) t.get("ratingCount")).intValue(), "ratingCount value: " + body);
        assertTrue(t.containsKey("averageRating"), "averageRating present: " + body);
        if (averageRating == null) {
            assertNull(t.get("averageRating"), "averageRating null: " + body);
        } else {
            assertEquals(averageRating, ((Number) t.get("averageRating")).doubleValue(), 1e-9, "averageRating: " + body);
        }
        // co-speakers spec field
        assertTrue(t.get("coSpeakerIds") instanceof List, "coSpeakerIds: " + body);
        List<Number> ids = (List<Number>) t.get("coSpeakerIds");
        assertEquals(coSpeakerIds.size(), ids.size(), "coSpeakerIds size: " + body);
        for (int i = 0; i < coSpeakerIds.size(); i++) {
            assertEquals(coSpeakerIds.get(i).longValue(), ids.get(i).longValue(), "coSpeakerIds[" + i + "]: " + body);
        }
    }

    // ---------------------------------------------------------------- helpers

    private long newSpeaker() throws Exception {
        HttpResponse<String> res = post("/speakers",
                "{\"name\":\"Speaker\",\"email\":\"s-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) om.readValue(res.body(), Map.class).get("id")).longValue();
    }

    private long newTalk(long speakerId) throws Exception {
        HttpResponse<String> res = post("/talks", talkJson(speakerId));
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) om.readValue(res.body(), Map.class).get("id")).longValue();
    }

    private static String talkJson(long speakerId) {
        return "{\"title\":\"T " + UUID.randomUUID() + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}";
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

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(req("POST", path, json), HttpResponse.BodyHandlers.ofString());
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

    @SuppressWarnings("unchecked")
    private String firstOfArray(String body) throws Exception {
        List<Object> arr = om.readValue(body, List.class);
        assertTrue(!arr.isEmpty(), "empty array: " + body);
        return om.writeValueAsString(arr.get(0));
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}

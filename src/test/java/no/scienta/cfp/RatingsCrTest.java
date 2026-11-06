package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Change request ratings-cr: from 5 ratings the average leaves out one highest and one lowest score; it is rounded
 * to 2 decimals with banker's rounding; ratingCount still counts everyone; the ranking uses the new average.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsCrTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    // ---- the arithmetic, exactly (no HTTP) ----

    // R3: banker's rounding at 2 decimals, the spec's own examples
    @Test
    void bankersRoundingAtTwoDecimals() {
        // 10 ratings: drop one 5 and one 1, the middle eight sum to 25 -> 3.125 -> 3.12
        assertEquals(3.12, ConferenceService.calculateAverage(1 + 5 + 25, 10, 5, 1));
        // middle eight sum to 27 -> 3.375 -> 3.38
        assertEquals(3.38, ConferenceService.calculateAverage(1 + 5 + 27, 10, 5, 1));
        // below 5 ratings no trimming, still 2 decimals: 4,4,4,5 -> 4.25 (was 4.3 before the change)
        assertEquals(4.25, ConferenceService.calculateAverage(17, 4, 5, 4));
        assertEquals(3.67, ConferenceService.calculateAverage(11, 3, 5, 1));   // 3.666.. rounds up
    }

    // R1/R2: trimming starts at exactly 5 ratings, and drops exactly one of each even when tied
    @Test
    void trimsExactlyOneHighestAndOneLowestFromFive() {
        assertEquals(3.5, ConferenceService.calculateAverage(4 + 3 + 5 + 2, 4, 5, 2));        // 4 ratings: all count
        assertEquals(3.67, ConferenceService.calculateAverage(5 + 5 + 5 + 1 + 1, 5, 5, 1));  // 5,5,5,1,1 -> (5+5+1)/3
        assertEquals(5.0, ConferenceService.calculateAverage(25, 5, 5, 5));                   // all equal: unchanged
    }

    // ---- through the API ----

    // R1-R4, R6: trimmed average and full ratingCount on every view of a talk
    @Test
    void trimmedAverageAndFullCountEverywhereATalkIsShown() throws Exception {
        long speaker = newSpeaker();
        long talk = newTalk(speaker);
        rateAll(talk, 5, 5, 5, 1, 1);

        for (Map<String, Object> t : List.of(single(talk), inList(get("/talks"), talk),
                inList(get("/speakers/" + speaker + "/talks"), talk), inList(get("/talks?sort=rating"), talk))) {
            assertEquals(3.67, ((Number) t.get("averageRating")).doubleValue(), 1e-9, t.toString());
            assertEquals(5, ((Number) t.get("ratingCount")).intValue());
        }
    }

    // R1: under 5 ratings every score counts; R3 applies too (2 decimals, no longer 1)
    @Test
    void underFiveRatingsEveryScoreCountsAtTwoDecimals() throws Exception {
        long talk = newTalk(newSpeaker());
        rateAll(talk, 4, 4, 4, 5);
        assertEquals(4.25, ((Number) single(talk).get("averageRating")).doubleValue(), 1e-9);
        assertEquals(4, ((Number) single(talk).get("ratingCount")).intValue());
    }

    // R2: a re-rating replaces the old score, so the trim follows the current scores
    @Test
    void reRatingChangesWhatIsTrimmed() throws Exception {
        long talk = newTalk(newSpeaker());
        rateAll(talk, 5, 4, 4, 4, 1);                 // (4+4+4)/3 = 4.0
        assertEquals(4.0, ((Number) single(talk).get("averageRating")).doubleValue(), 1e-9);
        rate(talk, 5, 3);                              // reviewer 5 changes 1 -> 3: 5,4,4,4,3 -> (4+4+4)/3
        assertEquals(4.0, ((Number) single(talk).get("averageRating")).doubleValue(), 1e-9);
        rate(talk, 1, 2);                              // reviewer 1 changes 5 -> 2: 2,4,4,4,3 -> (4+4+3)/3 = 3.67
        assertEquals(3.67, ((Number) single(talk).get("averageRating")).doubleValue(), 1e-9);
        assertEquals(5, ((Number) single(talk).get("ratingCount")).intValue());
    }

    // R5: the ranking uses the new rounded average; same average -> more reviewers first; unrated last
    @Test
    void rankingUsesTheTrimmedAverage() throws Exception {
        long outlier = newTalk(newSpeaker());
        rateAll(outlier, 5, 5, 4, 4, 1);              // untrimmed 3.8, trimmed (5+4+4)/3 = 4.33
        long steady = newTalk(newSpeaker());
        rateAll(steady, 4, 4, 4, 4);                  // 4.0 either way
        long tieMore = newTalk(newSpeaker());
        rateAll(tieMore, 5, 4, 4, 4, 3);              // trimmed 4.0 with 5 ratings: ties steady, more reviewers
        long unrated = newTalk(newSpeaker());

        List<Long> order = new ArrayList<>();
        for (Map<String, Object> t : list(get("/talks?sort=rating"))) {
            long id = ((Number) t.get("id")).longValue();
            if (id == outlier || id == steady || id == tieMore || id == unrated) {
                order.add(id);
            }
        }
        assertEquals(List.of(outlier, tieMore, steady, unrated), order);
        assertNull(single(unrated).get("averageRating"));
    }

    // ---- helpers ----

    private void rateAll(long talk, int... scores) throws Exception {
        for (int i = 0; i < scores.length; i++) {
            rate(talk, i + 1, scores[i]);
        }
    }

    private void rate(long talk, long reviewer, int score) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":" + score + "}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private long newSpeaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"R\",\"email\":\"r-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) om.readValue(res.body(), Map.class).get("id")).longValue();
    }

    private long newTalk(long speaker) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"T\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) om.readValue(res.body(), Map.class).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> single(long talk) throws Exception {
        return om.readValue(get("/talks/" + talk), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String body) throws Exception {
        return om.readValue(body, List.class);
    }

    private Map<String, Object> inList(String body, long talk) throws Exception {
        for (Map<String, Object> t : list(body)) {
            if (((Number) t.get("id")).longValue() == talk) {
                return t;
            }
        }
        throw new AssertionError("talk " + talk + " not in " + body);
    }

    private String get(String path) throws Exception {
        HttpResponse<String> res = send("GET", path, null);
        assertEquals(200, res.statusCode(), path + ": " + res.body());
        assertTrue(res.body().startsWith("[") || res.body().startsWith("{"));
        return res.body();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

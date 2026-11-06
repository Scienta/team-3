package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import javax.sql.DataSource;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression tests for the whole-application code review of 2026-11-06 (findings 2-9). Each test fails on the code
 * before the fix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CodeReviewFixesTest {

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SpeakerDao speakerDao;

    // #2: a reviewer id that doesn't fit rating.reviewer_id (INTEGER) is a 400, never a 500
    @Test
    void reviewerIdBeyondIntegerRangeIs400() throws Exception {
        long talk = newTalk(newSpeaker("Rev"), "Rated");
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/ratings/3000000000", "{\"score\":3}");
        assertEquals(400, res.statusCode(), res.body());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/2147483647", "{\"score\":3}").statusCode());
    }

    // #3: /health answers 200 while every pooled connection is taken (it has its own connection)
    @Test
    void healthIsOkWhileThePoolIsExhausted() throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {                  // spring.datasource.hikari.maximum-pool-size
                held.add(dataSource.getConnection());
            }
            long start = System.nanoTime();
            HttpResponse<String> res = send("GET", "/health", null);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(200, res.statusCode(), res.body());
            assertTrue(res.body().contains("\"status\":\"ok\""), res.body());
            assertTrue(ms < 3000, "health took " + ms + " ms with the pool exhausted");
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
    }

    // #8: id lookups bind one array, so huge id lists don't hit the driver's bind-parameter limit (65535 here)
    @Test
    void idLookupsHandleMoreThan65535Ids() {
        List<Long> ids = LongStream.rangeClosed(1, 70_000).boxed().collect(Collectors.toList());
        long speaker = jdbc.queryForObject("INSERT INTO speaker (name, email) VALUES ('Many', ?) RETURNING id",
                Long.class, "many-" + UUID.randomUUID() + "@example.com");
        ids.add(speaker);
        assertEquals("Many", speakerDao.findNames(ids).get(speaker));
    }

    // #9: a legacy talk without submitted_at reads as null, not 1970, in JSON and in the admin CSV
    @Test
    void talkWithoutSubmittedAtIsNullNot1970() throws Exception {
        long talk = newTalk(newSpeaker("Legacy"), "Legacy talk");
        jdbc.update("UPDATE talk SET submitted_at = NULL WHERE id = ?", talk);
        @SuppressWarnings("unchecked")
        Map<String, Object> t = om.readValue(send("GET", "/talks/" + talk, null).body(), Map.class);
        assertNull(t.get("submittedAt"), t.toString());
        assertNull(t.get("submittedOn"), t.toString());
        HttpResponse<String> csv = send("GET", "/admin/talks.csv", null);
        assertEquals(200, csv.statusCode());
        assertTrue(!csv.body().contains("1970"), "no 1970 dates in " + csv.body());
    }

    // #5: the admin CSV quotes line breaks and doubles quotes instead of changing them
    @Test
    void adminCsvKeepsQuotesAndLineBreaksIntact() throws Exception {
        newTalk(newSpeaker("Csv"), "Two\nlines and \"quotes\"; too");
        String body = send("GET", "/admin/talks.csv", null).body();
        assertTrue(body.contains("\"Two\nlines and \"\"quotes\"\"; too\""), body);
    }

    // #6/#7: the single lockForUpdate still gives the right answers on every path that locks a talk
    @Test
    void everyLockingPathStillWorks() throws Exception {
        long main = newSpeaker("Main");
        long co = newSpeaker("Co");
        long talk = newTalk(main, "Locked");
        assertEquals(200, send("PUT", "/talks/" + talk + "/co-speakers/" + co, null).statusCode());
        assertEquals(400, send("PUT", "/talks/" + talk + "/co-speakers/" + main, null).statusCode());
        assertEquals(200, send("DELETE", "/talks/" + talk + "/co-speakers/" + co, null).statusCode());
        assertEquals(404, send("PUT", "/talks/99999999/co-speakers/" + co, null).statusCode());
        assertEquals(403, send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + co + "}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":5}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}").statusCode());
        assertEquals(409, send("PUT", "/talks/" + talk + "/status", "{\"status\":\"REJECTED\"}").statusCode());
        assertEquals(409, send("POST", "/workshops/" + talk + "/registrations",
                "{\"name\":\"A\",\"email\":\"a@example.com\"}").statusCode());          // not a workshop
        assertEquals(200, send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + main + "}").statusCode());
    }

    // ---- helpers ----

    private long newSpeaker(String name) throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"" + name + "\",\"email\":\"cr-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) om.readValue(res.body(), Map.class).get("id")).longValue();
    }

    private long newTalk(long speaker, String title) throws Exception {
        HttpResponse<String> res = send("POST", "/talks", "{\"title\":" + om.writeValueAsString(title)
                + ",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) om.readValue(res.body(), Map.class).get("id")).longValue();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

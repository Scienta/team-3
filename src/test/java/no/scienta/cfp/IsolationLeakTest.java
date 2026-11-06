package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression for the 14:07 production incident: programme-export's REPEATABLE READ transaction made the driver
 * send a session-level SET, and on the deployed database (server-side pooling) that leaked to other connections.
 * Under a leaked REPEATABLE READ the workshop rush let 22 attendees into 20 seats (workshop-registration/check-4)
 * and concurrent ratings failed with "could not serialize access" (ratings/check-3).
 *
 * Locally the pool doesn't leak, so this test poisons every pooled connection with that session default itself and
 * then checks that our transactions still behave as READ COMMITTED (see {@link TransactionConfig}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IsolationLeakTest {

    private static final int POOL = 5;   // spring.datasource.hikari.maximum-pool-size

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

    @BeforeEach
    void poisonPool() throws Exception {
        sessionDefault("REPEATABLE READ");
    }

    @AfterEach
    void healPool() throws Exception {
        sessionDefault("READ COMMITTED");
    }

    @Test
    void workshopRushStaysAtTwentyUnderLeakedRepeatableRead() throws Exception {
        long ws = accepted("WORKSHOP");
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sent.add(async("POST", "/workshops/" + ws + "/registrations",
                    "{\"name\":\"A" + i + "\",\"email\":\"leak" + i + "@example.com\"}"));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.get();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            created += res.statusCode() == 201 ? 1 : 0;
        }
        assertEquals(20, created);
    }

    @Test
    void concurrentRatingsBySameReviewerNeverFailUnderLeakedRepeatableRead() throws Exception {
        long talk = accepted("PRESENTATION");
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            sent.add(async("PUT", "/talks/" + talk + "/ratings/777", "{\"score\":" + (1 + i % 5) + "}"));
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.get();
            assertEquals(200, res.statusCode(), res.body());
        }
    }

    // ---- helpers ----

    /** Sets the session default isolation on every pooled connection, as a leaked SET would on the deployed DB. */
    private void sessionDefault(String level) throws Exception {
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < POOL; i++) {
                Connection c = dataSource.getConnection();
                held.add(c);
                try (Statement st = c.createStatement()) {
                    st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + level);
                }
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
    }

    private long accepted(String format) throws Exception {
        HttpResponse<String> sp = send("POST", "/speakers",
                "{\"name\":\"Leak\",\"email\":\"leak-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, sp.statusCode(), sp.body());
        HttpResponse<String> t = send("POST", "/talks", "{\"title\":\"Leak\",\"format\":\"" + format
                + "\",\"level\":\"BEGINNER\",\"speakerId\":" + id(sp.body()) + "}");
        assertEquals(201, t.statusCode(), t.body());
        long talk = id(t.body());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":5}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}").statusCode());
        return talk;
    }

    @SuppressWarnings("unchecked")
    private long id(String body) throws Exception {
        return ((Number) om.readValue(body, Map.class).get("id")).longValue();
    }

    private HttpRequest req(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return http.send(req(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> async(String method, String path, String json) {
        return http.sendAsync(req(method, path, json), HttpResponse.BodyHandlers.ofString());
    }
}

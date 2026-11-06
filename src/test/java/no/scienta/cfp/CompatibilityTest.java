package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * spring-boot-4: "nothing else changes for our users ... formats included". Pins what the API looked like on Spring
 * Boot 2.7 / Jackson 2 wherever Boot 4 / Spring 7 / Jackson 3 / newer Java would change it by default. The expected
 * values were captured from the 2.7 build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompatibilityTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    private static final String TIMESTAMP = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}\\+00:00";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void reportsTheRealSpringBoot4Version() throws Exception {
        HttpResponse<String> res = send("GET", "/actuator/info", null);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"springBoot\":\"" + org.springframework.boot.SpringBootVersion.getVersion() + "\""),
                res.body());
        assertTrue(res.body().contains("\"springBoot\":\"4."), res.body());
        assertTrue(res.body().contains("\"springFramework\":\"7."), res.body());
    }

    @Test
    void talkJsonKeepsFieldOrderAndDateFormats() throws Exception {
        String speaker = newSpeaker();
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"Compat\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        assertEquals(JSON_UTF8, contentType(res));
        // declaration order (Jackson 3 sorts alphabetically), "+00:00" (Jackson 3: "Z"), plain date for submittedOn
        assertTrue(res.body().matches("\\{\"id\":\\d+,\"title\":\"Compat\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\","
                + "\"speakerId\":" + speaker + ",\"durationMinutes\":45,\"status\":\"SUBMITTED\",\"submittedAt\":\"" + TIMESTAMP + "\","
                + "\"submittedOn\":\"\\d{4}-\\d\\d-\\d\\d\",\"averageRating\":null,\"ratingCount\":0,\"coSpeakerIds\":\\[]}"),
                res.body());
    }

    @Test
    void errorBodiesKeepTheirShapeAndCharset() throws Exception {
        HttpResponse<String> res = send("GET", "/talks/999999", null);
        assertEquals(404, res.statusCode());
        assertEquals(JSON_UTF8, contentType(res));
        assertTrue(res.body().matches("\\{\"timestamp\":\"" + TIMESTAMP + "\",\"status\":404,\"error\":\"Not Found\","
                + "\"message\":\"talk not found\",\"path\":\"/talks/999999\"}"), res.body());

        // framework errors (error dispatch) too: message included, charset kept
        HttpResponse<String> notAllowed = send("DELETE", "/talks/1", null);
        assertEquals(405, notAllowed.statusCode());
        assertEquals(JSON_UTF8, contentType(notAllowed));
        assertTrue(notAllowed.body().contains("\"message\":\"Method 'DELETE' is not supported.\""), notAllowed.body());
        assertTrue(notAllowed.body().contains("\"path\":\"/talks/1\""), notAllowed.body());
    }

    @Test
    void unknownPathsSayNoMessageAvailable() throws Exception {
        for (String path : new String[]{"/nope", "/TALKS", "//talks"}) {
            HttpResponse<String> res = send("GET", path, null);
            assertEquals(404, res.statusCode(), path);
            assertEquals(JSON_UTF8, contentType(res), path);
            assertTrue(res.body().matches("\\{\"timestamp\":\"" + TIMESTAMP + "\",\"status\":404,\"error\":\"Not Found\","
                    + "\"message\":\"No message available\",\"path\":\"" + path + "\"}"), res.body());
        }
    }

    @Test
    void contentAfterTheJsonBodyIsIgnored() throws Exception {
        HttpResponse<String> garbage = send("POST", "/speakers",
                "{\"name\":\"Tr\",\"email\":\"tr-" + UUID.randomUUID() + "@example.com\"} trailing");
        assertEquals(201, garbage.statusCode(), garbage.body());
        HttpResponse<String> second = send("POST", "/speakers",
                "{\"name\":\"Tr\",\"email\":\"tr-" + UUID.randomUUID() + "@example.com\"}{\"x\":1}");
        assertEquals(201, second.statusCode(), second.body());
    }

    @Test
    void validationErrorsStillListTheFields() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers", "{\"email\":\"x@example.com\"}");
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("\"status\":400,\"error\":\"Bad Request\",\"message\":\"Validation failed for object='speakerForm'. Error count: 1\",\"errors\":["),
                res.body());
        assertTrue(res.body().contains("\"field\":\"name\""), res.body());
        assertTrue(res.body().contains("\"defaultMessage\":\"must not be blank\""), res.body());
    }

    @Test
    void trailingSlashStillMatches() throws Exception {
        String speaker = newSpeaker();
        HttpResponse<String> res = send("GET", "/speakers/" + speaker + "/", null);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals(200, send("GET", "/talks/", null).statusCode());
    }

    @Test
    void commentsInRequestJsonAreAccepted() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\n // the mobile client sends these\n \"name\":\"Cy\", /* block */ \"email\":\"cy-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
    }

    @Test
    void ratingDisplayHasAPlainSpaceBeforeAmPm() throws Exception {
        String speaker = newSpeaker();
        HttpResponse<String> talk = send("POST", "/talks",
                "{\"title\":\"Rated\",\"format\":\"LIGHTNING_10\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        Matcher m = ID.matcher(talk.body());
        assertTrue(m.find(), talk.body());
        HttpResponse<String> res = send("PUT", "/talks/" + m.group(1) + "/ratings/7", "{\"score\":4}");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().matches(".*\"ratedAtDisplay\":\"\\d{1,2}/\\d{1,2}/\\d\\d, \\d{1,2}:\\d\\d [AP]M\"}"), res.body());
    }

    @Test
    void actuatorHealthIsUnchanged() throws Exception {
        HttpResponse<String> res = send("GET", "/actuator/health", null);
        assertEquals(200, res.statusCode());
        assertEquals("{\"status\":\"UP\"}", res.body());
    }

    private String newSpeaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private static String contentType(HttpResponse<String> res) {
        return res.headers().firstValue("Content-Type").orElse("");
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The deploy contract's GET /health: 200 with the baked-in version while the database answers, 503 within 5 s
 * when it doesn't. The database here is a container of its own, so the test can pause it without hurting others.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "GIT_SHA=0123abc")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        TestDatabase.pointAt(registry, POSTGRES, POSTGRES.getDatabaseName());
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Order(1)
    void healthIsOkWithTheVersionWhileTheDatabaseAnswers() throws Exception {
        HttpResponse<String> res = health();
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("{\"status\":\"ok\",\"version\":\"0123abc\"}", res.body());
    }

    @Test
    @Order(2)
    void healthIsDbUnavailableWithinFiveSecondsWhenTheDatabaseHangs() throws Exception {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            HttpResponse<String> res = health();
            long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertEquals(503, res.statusCode(), res.body());
            assertEquals("{\"status\":\"db-unavailable\",\"version\":\"0123abc\"}", res.body());
            assertTrue(took < 6000, "took " + took + " ms");
        } finally {
            unpause();
        }
    }

    @Test
    @Order(3)
    void probesThatTimeOutDoNotPileUpWhileTheDatabaseHangs() throws Exception {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            for (int i = 0; i < 3; i++) {
                assertEquals(503, health().statusCode());
            }
            long threads = healthCheckThreads();
            assertTrue(threads <= 1, threads + " health-check threads");
        } finally {
            unpause();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int status = health().statusCode();
        while (status != 200 && System.nanoTime() < deadline) {
            Thread.sleep(200);
            status = health().statusCode();
        }
        assertEquals(200, status, "health did not recover once the database came back");
    }

    private static long healthCheckThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("health-check"))
                .count();
    }

    // Podman drops the docker client's idle connection while the test waits, so the first try can hit a broken pipe.
    private static void unpause() {
        try {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        } catch (RuntimeException e) {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
    }

    private HttpResponse<String> health() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

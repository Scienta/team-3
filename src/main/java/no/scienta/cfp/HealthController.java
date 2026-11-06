package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// The deploy pipeline polls /health, not /actuator/health.
// 200 only if the database answers within 5 s, else 503. version is the GIT_SHA baked into the image.
// One probe at a time: while a SELECT 1 is still running, later requests wait on that same probe instead of
// starting another, so a hung database can't pile up threads and pool connections.
@RestController
public class HealthController {

    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "health-check");
        t.setDaemon(true);
        return t;
    });

    private final JdbcTemplate jdbc;
    private final String version;
    private CompletableFuture<Integer> probe;

    public HealthController(JdbcTemplate jdbc, @Value("${GIT_SHA:dev}") String version) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(5);
        this.version = version;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> body = new LinkedHashMap<>();
        try {
            currentProbe().get(5, TimeUnit.SECONDS);
            body.put("status", "ok");
            body.put("version", version);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            body.put("status", "db-unavailable");
            body.put("version", version);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    private synchronized CompletableFuture<Integer> currentProbe() {
        if (probe == null || probe.isDone()) {
            probe = CompletableFuture.supplyAsync(() -> jdbc.queryForObject("SELECT 1", Integer.class), CHECKS);
        }
        return probe;
    }
}

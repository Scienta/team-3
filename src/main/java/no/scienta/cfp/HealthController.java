package no.scienta.cfp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// The deploy pipeline polls /health, not /actuator/health.
// 200 only if the database answers within 5 s, else 503. version is the GIT_SHA baked into the image.
// One probe at a time: while a SELECT 1 is still running, later requests wait on that same probe instead of
// starting another, so a hung database can't pile up threads.
// The probe has its OWN connection, outside the Hikari pool (max 5): during a harness rush every pooled connection
// can be busy or waiting on a row lock, and a probe queued behind them reported db-unavailable while the database
// was fine (code review, 2026-11-06). Each probe opens a fresh connection and closes it: a long-lived one can be
// dropped silently by the platform's proxy, and isClosed() wouldn't notice (independent review). Probes are one at a
// time, so this is at most one short extra connection, and nothing is held between probes.
@RestController
public class HealthController {

    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "health-check");
        t.setDaemon(true);
        return t;
    });

    private final String url;
    private final Properties props = new Properties();
    private final String version;
    private CompletableFuture<Integer> probe;

    public HealthController(@Value("${spring.datasource.url}") String url,
                            @Value("${spring.datasource.username}") String username,
                            @Value("${spring.datasource.password}") String password,
                            @Value("${GIT_SHA:dev}") String version) {
        this.url = url;
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "5");   // seconds: an unreachable host fails inside the 5 s budget
        props.setProperty("loginTimeout", "5");
        props.setProperty("socketTimeout", "10");
        props.setProperty("ApplicationName", "cfp-health");
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
            probe = CompletableFuture.supplyAsync(this::selectOne, CHECKS);
        }
        return probe;
    }

    private int selectOne() {
        try (Connection connection = DriverManager.getConnection(url, props);
             Statement st = connection.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

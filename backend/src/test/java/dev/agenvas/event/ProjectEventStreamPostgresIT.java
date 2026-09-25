package dev.agenvas.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** End-to-end HTTP evidence for authenticated SSE replay, native cursor precedence, and expiry. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agenvas.identity.bootstrap-secret=stream-integration-bootstrap-secret",
            // 生产心跳为 15 秒；这里缩短，否则每轮等待失效连接回收要花几十秒。
            "agenvas.sse.heartbeat-interval=200ms"
        })
class ProjectEventStreamPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AgentInstanceService agentService;

    @Autowired
    private AgentRunService runService;

    @Autowired
    private ProjectEventService eventService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void replaysHistoryAndLastEventIdWithoutLeakingOtherProjects() throws Exception {
        AdminPrincipal owner = identityService.setup(
                "stream-integration-bootstrap-secret", "stream-admin", "stream-password-123");
        Project project = projectService.create(
                owner.userId(), "Stream project", Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agentService.create(
                owner.userId(), project.id(), "Creator", "Create", List.of());

        HttpClient anonymous = HttpClient.newHttpClient();
        assertThat(anonymous.send(eventRequest(project.id(), 0, null),
                        HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
        String activeMetricPath = "/actuator/metrics/agenvas.sse.connections.active";
        assertThat(anonymous.send(HttpRequest.newBuilder(uri(activeMetricPath)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        login(client);
        HttpResponse<String> activeMetric = client.send(
                HttpRequest.newBuilder(uri(activeMetricPath)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(activeMetric.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(activeMetric.body()).get("name").stringValue())
                .isEqualTo("agenvas.sse.connections.active");
        assertThat(objectMapper.readTree(activeMetric.body()).get("availableTags").isEmpty())
                .isTrue();
        try (InputStream firstBody = client.send(
                        eventRequest(project.id(), 0, null),
                        HttpResponse.BodyHandlers.ofInputStream())
                .body()) {
            EventFrame first = readFrame(firstBody);
            assertThat(first.id()).isEqualTo("1");
            assertThat(first.type()).isEqualTo("agent.instance.changed");
            assertThat(objectMapper.readTree(first.data()).get("projectId").stringValue())
                    .isEqualTo(project.id().toString());
        }
        HttpResponse<String> deliveryLag = client.send(HttpRequest.newBuilder(
                        uri("/actuator/metrics/agenvas.sse.event.delivery.lag")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deliveryLag.statusCode()).isEqualTo(200);
        double deliveredCount = 0;
        for (var measurement : objectMapper.readTree(deliveryLag.body()).path("measurements")) {
            if ("COUNT".equals(measurement.path("statistic").asText())) {
                deliveredCount = measurement.path("value").asDouble();
            }
        }
        assertThat(deliveredCount).isGreaterThanOrEqualTo(1);

        // Repeated real socket closes must not retain subscribers or exhaust the 64-client cap.
        for (int round = 0; round < 3; round++) {
            for (int index = 0; index < 20; index++) {
                HttpResponse<InputStream> repeated = client.send(
                        eventRequest(project.id(), 0, null),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertThat(repeated.statusCode()).isEqualTo(200);
                try (InputStream body = repeated.body()) {
                    assertThat(readFrame(body).id()).isEqualTo("1");
                }
            }
            awaitNoActiveStreams(client, activeMetricPath);
        }

        runService.create(owner.userId(), project.id(), agent.id(), "Create shots", "stream-run");
        HttpResponse<InputStream> replay = client.send(
                eventRequest(project.id(), 0, "1"),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(replay.statusCode()).isEqualTo(200);
        try (InputStream body = replay.body()) {
            EventFrame second = readFrame(body);
            assertThat(second.id()).isEqualTo("2");
            assertThat(second.type()).isEqualTo("agent.run.changed");
        }

        assertThat(client.send(eventRequest(UUID.randomUUID(), 0, null),
                        HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(404);
        jdbcClient.sql("""
                        update project_event
                        set occurred_at = now() - interval '31 days'
                        where project_id = :projectId and seq = 1
                        """)
                .param("projectId", project.id())
                .update();
        assertThat(eventService.pruneExpired(100)).isEqualTo(1);
        HttpResponse<String> expired = client.send(
                eventRequest(project.id(), 0, null),
                HttpResponse.BodyHandlers.ofString());
        assertThat(expired.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(expired.body()).get("code").stringValue())
                .isEqualTo("EVENT_CURSOR_EXPIRED");
    }

    private void login(HttpClient client) throws IOException, InterruptedException {
        HttpResponse<String> csrf = client.send(HttpRequest.newBuilder()
                        .uri(uri("/api/v1/auth/csrf"))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
        String token = objectMapper.readTree(csrf.body()).get("token").stringValue();
        HttpResponse<String> login = client.send(HttpRequest.newBuilder()
                        .uri(uri("/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"loginName\":\"stream-admin\",\"password\":\"stream-password-123\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
    }

    /** A closed client may be noticed on the next bounded heartbeat, never indefinitely. */
    private void awaitNoActiveStreams(HttpClient client, String metricPath) throws Exception {
        Instant deadline = Instant.now().plusSeconds(45);
        double lastActive = -1;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> metric = client.send(HttpRequest.newBuilder(uri(metricPath))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(metric.statusCode()).isEqualTo(200);
            double active = objectMapper.readTree(metric.body())
                    .path("measurements").path(0).path("value").asDouble(-1);
            lastActive = active;
            if (active == 0) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Closed SSE clients remained active beyond the heartbeat window: "
                + lastActive);
    }

    private HttpRequest eventRequest(UUID projectId, long after, String lastEventId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri("/api/v1/projects/" + projectId + "/events?after=" + after))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return builder.build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private EventFrame readFrame(InputStream body) throws Exception {
        try (ExecutorService readerThread = Executors.newSingleThreadExecutor()) {
            Future<EventFrame> future = readerThread.submit(() -> {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(body, StandardCharsets.UTF_8));
                String id = null;
                String type = null;
                String data = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("id:")) id = line.substring(3).trim();
                    if (line.startsWith("event:")) type = line.substring(6).trim();
                    if (line.startsWith("data:")) data = line.substring(5).trim();
                    if (line.isEmpty() && id != null && type != null && data != null) {
                        return new EventFrame(id, type, data);
                    }
                }
                throw new IOException("SSE stream ended before a complete event frame");
            });
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private record EventFrame(String id, String type, String data) {}
}

package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real HTTP requests must populate a bounded URI-tagged p95 server latency timer. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agenvas.identity.bootstrap-secret=http-latency-integration-secret",
                "agenvas.llm.scheduler-enabled=false",
                "agenvas.provider.mock.scheduler-enabled=false",
                "agenvas.provider.mock.video-scheduler-enabled=false",
                "agenvas.export.scheduler-enabled=false"})
class HttpLatencyMetricsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort private int port;
    @Autowired private MeterRegistry meters;
    @Autowired private ObjectMapper mapper;

    @Test
    void realHttpRequestsExposeP95WithoutRequestSpecificLabels() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/auth/setup-status");
        for (int request = 0; request < 12; request++) {
            HttpRequest call = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Request-Id", "untrusted-client-value")
                    .GET().build();
            HttpResponse<Void> response = client.send(call,
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Request-Id"))
                    .hasValueSatisfying(id -> assertThat(id)
                            .matches("[0-9a-f]{32}").isNotEqualTo("untrusted-client-value"));
        }
        var timer = meters.get("http.server.requests")
                .tag("uri", "/api/v1/auth/setup-status").timer();
        assertThat(timer.count()).isGreaterThanOrEqualTo(12);
        assertThat(Arrays.stream(timer.takeSnapshot().percentileValues())
                .map(ValueAtPercentile::percentile)).contains(0.95);
        assertThat(timer.getId().getTags()).allSatisfy(tag ->
                assertThat(tag.getKey()).isNotIn("projectId", "runId", "taskId",
                        "userId", "requestId"));

        HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/projects"))
                        .timeout(Duration.ofSeconds(10))
                        .header("X-Request-Id", "attacker-request-id")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(401);
        String serverId = rejected.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(serverId).matches("[0-9a-f]{32}");
        assertThat(mapper.readTree(rejected.body()).path("traceId").asText())
                .isEqualTo(serverId);
    }
}

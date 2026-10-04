package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Database loss must remove readiness without claiming the JVM process is dead. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.hikari.connection-timeout=2000"
        })
class ReadinessPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    /** A stopped isolated test database yields 503 readiness but 200 liveness. */
    @Test
    void databaseOutageChangesReadinessNotLiveness() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        assertThat(status(client, "readiness")).isEqualTo(200);
        assertThat(status(client, "liveness")).isEqualTo(200);

        POSTGRES.stop();

        assertThat(status(client, "readiness")).isEqualTo(503);
        assertThat(status(client, "liveness")).isEqualTo(200);
    }

    /** Calls the public health probe without authentication. */
    private int status(HttpClient client, String probe) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/actuator/health/" + probe))
                .timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

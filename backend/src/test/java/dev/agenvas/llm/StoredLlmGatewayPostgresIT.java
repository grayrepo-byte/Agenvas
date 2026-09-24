package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.settings.application.LlmProviderConfigService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real database-to-Spring-AI wiring against a fake HTTP endpoint, never a real Provider. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=stored-llm-integration-secret",
        "agenvas.llm.mode=configured",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.settings.llm.allow-loopback-http=true"})
class StoredLlmGatewayPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer SERVER = fakeModel();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired private LlmProviderConfigService configs;
    @Autowired private ChatGateway gateway;
    @Autowired private JdbcClient jdbc;

    @Test
    void decryptsStoredVersionOnlyAfterCapabilityVerificationAndCallsSelectedEndpoint() {
        String endpoint = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1";
        configs.replace(0, endpoint, "stored-model-a", "stored-secret-key-1234");
        assertThat(gateway.configVersion()).isEqualTo(1);
        assertThat(gateway.modelDetails().modelId()).isEqualTo("stored-model-a");
        assertThat(gateway.capabilities().toolCalling()).isFalse();
        assertThatThrownBy(() -> gateway.call(List.of(new UserMessage("Hello")),
                List.of(), Map.of())).isInstanceOf(IllegalStateException.class);
        assertThat(CALLS).hasValue(0);

        // Fixture-only capability stamp; production has no path to set this without a diagnostic.
        jdbc.sql("update llm_provider_config set tool_calling_verified = true where version = 1")
                .update();
        assertThat(gateway.capabilities().toolCalling()).isTrue();
        ChatGateway.Exchange exchange = gateway.call(List.of(new UserMessage("Hello")),
                List.of(), Map.of());
        assertThat(exchange.configVersion()).isEqualTo(1);
        assertThat(exchange.response().getResult().getOutput().getText()).isEqualTo("Stored model reply");
        assertThat(CALLS).hasValue(1);
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer stored-secret-key-1234");
        assertThat(REQUEST.get()).contains("\"model\":\"stored-model-a\"")
                .doesNotContain("stored-secret-key-1234");

        configs.replace(1, endpoint, "stored-model-b", "stored-secret-key-5678");
        assertThat(gateway.configVersion()).isEqualTo(2);
        assertThat(gateway.capabilities().toolCalling()).isFalse();
        ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity("stored", 1);
        assertThat(gateway.capabilitiesFor(pinned).toolCalling()).isTrue();
        ChatGateway.Exchange historical = gateway.call(List.of(new UserMessage("Continue")),
                List.of(), Map.of(), pinned);
        assertThat(historical.configVersion()).isEqualTo(1);
        assertThat(CALLS).hasValue(2);
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer stored-secret-key-1234");
        assertThat(REQUEST.get()).contains("\"model\":\"stored-model-a\"")
                .doesNotContain("stored-secret-key-5678");
        assertThatThrownBy(() -> gateway.call(List.of(new UserMessage("No switch")),
                List.of(), Map.of(), new ChatGateway.ConfigIdentity("environment", 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CALLS).hasValue(2);
    }

    /** Minimal OpenAI-compatible response; no application tool is executed by the server. */
    private static HttpServer fakeModel() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                CALLS.incrementAndGet();
                byte[] response = ("{\"id\":\"chatcmpl-stored-1\",\"object\":\"chat.completion\","
                        + "\"created\":1700000000,\"model\":\"stored-model-a\",\"choices\":[{"
                        + "\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Stored model reply\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,"
                        + "\"completion_tokens\":3,\"total_tokens\":5}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake stored-model endpoint", failure);
        }
    }
}

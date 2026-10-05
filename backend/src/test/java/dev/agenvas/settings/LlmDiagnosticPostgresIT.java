package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.settings.application.LlmProviderConfigService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and fake HTTP prove capability is earned only by a full tool round-trip. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.mode=configured",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.settings.llm.allow-loopback-http=true"})
class LlmDiagnosticPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Pattern NONCE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicBoolean RETURN_TOOL = new AtomicBoolean(true);
    private static final AtomicBoolean IGNORE_TOOL_RESULT = new AtomicBoolean();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
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

    @Autowired private IdentityService identities;
    @Autowired private LlmProviderConfigService configs;
    @Autowired private ChatGateway gateway;
    @Autowired private WebApplicationContext context;

    @Test
    void requiresAdminCsrfCostConsentAndExactTwoRoundProtocol() throws Exception {
        String endpoint = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1";
        configs.replace(0, endpoint, "diagnostic-model", "diagnostic-secret-1234");
        AdminPrincipal admin = identities.setup("diagnostic-admin", "diagnostic-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v1/settings/llm/diagnose";
        mvc.perform(post(path).with(csrf()).contentType("application/json")
                        .content(request(1, true))).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(auth).contentType("application/json")
                        .content(request(1, true))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(request(1, false))).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(request(2, true))).andExpect(status().isConflict());
        assertThat(CALLS).hasValue(0);

        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(request(1, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.toolCallingVerified").value(true));
        assertThat(CALLS).hasValue(2);
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer diagnostic-secret-1234");
        assertThat(gateway.capabilities().toolCalling()).isTrue();
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(request(1, true))).andExpect(status().isOk());
        assertThat(CALLS).hasValue(2);

        configs.replace(1, endpoint, "diagnostic-model", "diagnostic-secret-5678");
        RETURN_TOOL.set(false);
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(request(2, true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PROVIDER_TOOL_PROTOCOL_UNVERIFIED"));
        assertThat(CALLS).hasValue(3);
        assertThat(configs.status().toolCallingVerified()).isFalse();
        assertThat(gateway.capabilities().toolCalling()).isFalse();

        configs.replace(2, endpoint, "diagnostic-model", "diagnostic-secret-9012");
        RETURN_TOOL.set(true);
        IGNORE_TOOL_RESULT.set(true);
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json")
                        .content(request(3, true)))
                .andExpect(status().isUnprocessableContent());
        assertThat(CALLS).hasValue(5);
        assertThat(configs.status().toolCallingVerified()).isFalse();
    }

    private String request(int version, boolean acknowledge) {
        return "{\"expectedVersion\":" + version + ",\"acknowledgeCost\":" + acknowledge + "}";
    }

    /** Echoes only the synthetic nonce after seeing the exact prior tool-call ID. */
    private static HttpServer fakeModel() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode request = new ObjectMapper().readTree(body);
                CALLS.incrementAndGet();
                Matcher matcher = NONCE.matcher(body);
                if (!matcher.find()) throw new IOException("Probe request lacks nonce");
                String nonce = matcher.group();
                boolean reply = body.contains("tool_call_id");
                JsonNode messages = request.path("messages");
                String proof = reply ? new ObjectMapper().readTree(
                        messages.get(messages.size() - 1).path("content").asText())
                        .path("proof").asText() : "";
                String message = !RETURN_TOOL.get()
                        ? "{\"role\":\"assistant\",\"content\":\"No tool requested\"}"
                        : reply
                        ? "{\"role\":\"assistant\",\"content\":\""
                            + (IGNORE_TOOL_RESULT.get() ? nonce : proof) + "\"}"
                        : "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{"
                            + "\"id\":\"probe-call-1\",\"type\":\"function\",\"function\":{"
                            + "\"name\":\"agenvas_connection_probe\",\"arguments\":"
                            + "\"{\\\"nonce\\\":\\\"" + nonce + "\\\"}\"}}]}";
                String finish = RETURN_TOOL.get() && !reply ? "tool_calls" : "stop";
                byte[] response = ("{\"id\":\"chatcmpl-probe-" + CALLS.get()
                        + "\",\"object\":\"chat.completion\",\"created\":1700000000,"
                        + "\"model\":\"diagnostic-model\",\"choices\":[{\"index\":0,"
                        + "\"message\":" + message + ",\"finish_reason\":\"" + finish
                        + "\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5,"
                        + "\"total_tokens\":10}}")
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
            throw new IllegalStateException("Cannot start fake diagnostic endpoint", failure);
        }
    }
}

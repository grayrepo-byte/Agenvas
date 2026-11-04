package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.application.CallLogRetentionService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    @Autowired private CallLogService callLogs;
    @Autowired private CallLogRetentionService retention;
    @Autowired private JdbcClient jdbc;

    @Test
    void requiresAdminCsrfCostConsentAndExactTwoRoundProtocol() throws Exception {
        String endpoint = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1";
        configs.replace(0, endpoint, "diagnostic-model", "diagnostic-secret-1234");
        AdminPrincipal admin = identities.setup("diagnostic-admin", "diagnostic-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(admin, null, List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
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
        var filter = new CallLogService.Filter(null, null, null, null, null, null, 0, 100);
        var successfulCalls = callLogs.list(admin.userId(), filter).items();
        assertThat(successfulCalls).hasSize(2).allSatisfy(call -> {
            assertThat(call.projectId()).isNull();
            assertThat(call.projectTitle()).isNull();
            assertThat(call.operation()).isEqualTo(CallLog.Operation.DIAGNOSTIC);
            assertThat(call.status()).isEqualTo(CallLog.Status.SUCCEEDED);
            assertThat(callLogs.debug(admin.userId(), call.id()).captured()).isFalse();
        });
        UUID other = UUID.randomUUID();
        assertThat(callLogs.list(other, filter).items()).isEmpty();
        assertThat(callLogs.list(admin.userId(), new CallLogService.Filter(other, null, null, null,
                null, null, 0, 100)).items()).isEmpty();
        assertThatThrownBy(() -> callLogs.debug(other, successfulCalls.getFirst().id()))
                .isInstanceOf(ApiProblemException.class);
        mvc.perform(get("/api/v1/call-logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/call-logs").with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].projectId").isEmpty())
                .andExpect(jsonPath("$.items[0].operation").value("DIAGNOSTIC"));
        // Debug capture is opt-in; subsequent synthetic calls prove metadata and private body access.
        callLogs.updateSettings(true, callLogs.settings().version());
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
        var allCalls = callLogs.list(admin.userId(), filter).items();
        assertThat(allCalls).hasSize(5);
        assertThat(allCalls).filteredOn(call -> call.status() == CallLog.Status.FAILED)
                .extracting(CallLog::errorCode)
                .containsExactlyInAnyOrder("PROVIDER_DIAGNOSTIC_NO_TOOL", "PROVIDER_DIAGNOSTIC_PROOF_MISMATCH");
        var captured = allCalls.stream().filter(call -> !successfulCalls.contains(call)).toList();
        assertThat(captured).allSatisfy(call -> {
            var debug = callLogs.debug(admin.userId(), call.id());
            assertThat(debug.captured()).isTrue();
            assertThat(debug.exchanges()).hasSize(1);
        });
        assertThat(allCalls.toString()).doesNotContain("diagnostic-secret", endpoint);
        jdbc.sql("update app_user set status='DISABLED' where id=:id").param("id", admin.userId()).update();
        try {
            assertThat(callLogs.list(admin.userId(), filter).items()).isEmpty();
            assertThatThrownBy(() -> callLogs.debug(admin.userId(), allCalls.getFirst().id()))
                    .isInstanceOf(ApiProblemException.class);
        } finally {
            jdbc.sql("update app_user set status='ACTIVE' where id=:id").param("id", admin.userId()).update();
        }
        // A lost process cannot leave ancient diagnostic rows projected as calling forever.
        UUID recent = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        for (UUID id : List.of(recent, stale)) {
            jdbc.sql("""
                    insert into call_log(id,project_id,kind,operation,status,trace_id,started_at,mock)
                    values(:id,null,'LLM','DIAGNOSTIC','RUNNING',:trace,now(),false)
                    """).param("id", id).param("trace", id.toString().replace("-", "")).update();
        }
        jdbc.sql("update call_log set started_at=now()-interval '1 day' where id=:id").param("id", stale).update();
        var projected = callLogs.list(admin.userId(), filter).items();
        assertThat(projected.stream().filter(call -> call.id().equals(recent)).findFirst().orElseThrow().status())
                .isEqualTo(CallLog.Status.RUNNING);
        assertThat(projected.stream().filter(call -> call.id().equals(stale)).findFirst().orElseThrow().status())
                .isEqualTo(CallLog.Status.UNKNOWN);
        assertThat(callLogs.list(admin.userId(), new CallLogService.Filter(null, null,
                CallLog.Status.UNKNOWN, null, null, null, 0, 100)).items())
                .extracting(CallLog::id).contains(stale).doesNotContain(recent);
        jdbc.sql("delete from call_log where id=:id").param("id", recent).update();
        jdbc.sql("delete from call_log where id=:id").param("id", stale).update();
        // Null project is reserved for diagnostics; the database still rejects unscoped business calls.
        assertThatThrownBy(() -> jdbc.sql("update call_log set operation='CHAT' where id=:id")
                .param("id", allCalls.getFirst().id()).update()).isInstanceOf(RuntimeException.class);
        jdbc.sql("update call_log set started_at=now()-interval '60 days', responded_at=now()-interval '60 days'")
                .update();
        var policy = retention.update(30, retention.settings().version());
        assertThat(retention.cleanExpired(policy.version()).cleanedExecutions()).isEqualTo(5);
        assertThat(callLogs.list(admin.userId(), filter).items()).isEmpty();
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

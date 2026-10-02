package dev.agenvas.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import dev.agenvas.shared.http.DebugHttpCapture;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.net.InetSocketAddress;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Dns;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.application.CallLogService.CallDescriptor;
import dev.agenvas.audit.application.CallLogService.CallOutcome;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and HTTP proof of audit isolation, honest legacy fields, and durable call timing. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=call-log-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false",
        "agenvas.export.scheduler-enabled=false"})
class CallLogPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String PATH = "/api/v1/call-logs";
    private static final Instant SAME_START = Instant.parse("2026-09-25T10:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String PRIVATE_MARKER = "PRIVATE_AUDIT_BODY_MARKER";
    private static final String PRIVATE_KEY = "sk-private-audit-key";
    private static final String PRIVATE_ENDPOINT = "https://private-provider.invalid/v1";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private dev.agenvas.task.application.TaskRepository taskRepository;
    @Autowired private CallLogService calls;
    @Autowired private dev.agenvas.audit.application.CallLogRepository auditRepository;
    @Autowired private dev.agenvas.audit.application.CallLogRetentionService retention;
    @Autowired private JdbcClient jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;
    private AdminPrincipal owner;
    private MockMvc mvc;
    private final Map<UUID, AgentRun> fixtureRuns = new java.util.HashMap<>();

    @BeforeEach
    void setUpOwnerAndHttp() {
        owner = jdbc.sql("select id from app_user where login_name='call-log-admin'")
                .query(UUID.class).optional().map(id -> new AdminPrincipal(id, "call-log-admin"))
                .orElseGet(() -> identities.setup("call-log-integration-secret", "call-log-admin",
                        "call-log-password-123"));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("update audit_debug_settings set debug_mode=false").update();
        jdbc.sql("update audit_log_retention_settings set retention_days=null").update();
        // Retention is global; defer all units left by other test cases.
        jdbc.sql("update agent_run set updated_at=now()").update();
        jdbc.sql("update task set updated_at=now() where run_id is null").update();
    }

    @Test
    void requiresAdministratorAndNeverLeaksAnotherOwnersProject() throws Exception {
        Project own = newProject("Owner audit");
        UUID ownCall = insertCall(own, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED,
                SAME_START, null);
        AdminPrincipal foreign = new AdminPrincipal(UUID.randomUUID(), "foreign-audit-owner");
        // The product permits one active administrator; a disabled account still owns historical data.
        jdbc.sql("""
                insert into app_user (id, login_name, password_hash, status, created_at)
                values (:id, :login, 'unused-test-password-hash', 'DISABLED', now())
                """).param("id", foreign.userId()).param("login", foreign.loginName()).update();
        Project privateProject = projects.create(foreign.userId(), "FOREIGN_AUDIT_PROJECT",
                Project.AspectRatio.SQUARE_1_1);
        UUID privateCall = insertCall(privateProject, CallLog.Kind.VIDEO, CallLog.Status.FAILED,
                SAME_START, null);

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(authentication(asUser(owner, "ROLE_USER"))))
                .andExpect(status().isForbidden());
        String visible = list(owner, Map.of("size", "100")).toString();
        assertThat(visible).contains(ownCall.toString())
                .doesNotContain(privateCall.toString(), privateProject.id().toString(), "FOREIGN_AUDIT_PROJECT");
        JsonNode guessed = list(owner, Map.of("projectId", privateProject.id().toString()));
        assertThat(guessed.path("items").size()).isZero();
        assertThat(guessed.path("totalElements").asLong()).isZero();
        JsonNode otherView = list(foreign, Map.of("size", "100"));
        assertThat(otherView.toString()).contains(privateCall.toString()).doesNotContain(ownCall.toString());
    }

    @Test
    void filtersMetadataAndPaginatesEqualTimestampsWithoutDuplicates() throws Exception {
        Project project = newProject("Filtered audit");
        List<UUID> expected = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            expected.add(insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED,
                    SAME_START, index == 0 ? TRACE : null));
        }
        insertCall(project, CallLog.Kind.LLM, CallLog.Status.SUCCEEDED, SAME_START, null);
        insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.FAILED, SAME_START, null);
        insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED,
                SAME_START.minusSeconds(1), null);
        insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED,
                SAME_START.plusSeconds(1), null);
        insertCall(newProject("Different filtered project"), CallLog.Kind.IMAGE,
                CallLog.Status.SUCCEEDED, SAME_START, null);
        Map<String, String> filters = Map.of("projectId", project.id().toString(),
                "kind", "IMAGE", "status", "SUCCEEDED",
                "from", SAME_START.toString(), "to", SAME_START.toString(), "size", "2");
        JsonNode first = list(owner, filters);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first).isEqualTo(list(owner, filters));
        var nextFilters = new java.util.HashMap<>(filters);
        nextFilters.put("page", "1");
        JsonNode second = list(owner, nextFilters);
        List<String> ids = new ArrayList<>(ids(first));
        ids.addAll(ids(second));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected.stream().map(UUID::toString).toList());
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        nextFilters.put("page", "2");
        assertThat(list(owner, nextFilters).path("items").size()).isZero();
        JsonNode defaults = list(owner, Map.of("projectId", project.id().toString()));
        assertThat(defaults.path("page").asInt()).isZero();
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(defaults.path("totalElements").asLong()).isEqualTo(7);
        JsonNode exactTrace = list(owner, Map.of("projectId", project.id().toString(), "traceId", TRACE));
        assertThat(ids(exactTrace)).containsExactly(expected.getFirst().toString());
    }

    @Test
    void rejectsInvalidFiltersWithoutReflectingPrivateInput() throws Exception {
        List<Map<String, String>> invalid = List.of(
                Map.of("projectId", PRIVATE_MARKER), Map.of("kind", "UNSUPPORTED"),
                Map.of("status", "READY"), Map.of("traceId", "not-a-trace"),
                Map.of("traceId", "0123456789ABCDEF0123456789ABCDEF"),
                Map.of("page", "-1"), Map.of("page", "not-an-integer"),
                Map.of("size", "0"), Map.of("size", "101"),
                Map.of("from", "not-an-instant"), Map.of("to", "not-an-instant"),
                Map.of("from", SAME_START.plusSeconds(1).toString(), "to", SAME_START.toString()));
        for (Map<String, String> values : invalid) {
            String body = mvc.perform(request(owner, values)).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertRedacted(body);
        }
        mvc.perform(request(owner, Map.of("kind", "AUDIO"))).andExpect(status().isOk());
    }

    @Test
    void legacyUnknownRemainsAuditableWithoutInventedTelemetryOrPrivateBodies() throws Exception {
        Project project = newProject("Legacy audit");
        var agent = agents.create(owner.userId(), project.id(), "Audit agent", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                PRIVATE_MARKER, "legacy-audit-run").run();
        Task task = tasks.create(owner.userId(), project.id(), run.id(),
                "legacy-media", Task.Kind.IMAGE_GENERATION, privatePayload(), 1);
        jdbc.sql("update task set status='UNKNOWN', error_code='PROVIDER_SUBMISSION_UNKNOWN' where id=:id")
                .param("id", task.id()).update();
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                insert into provider_attempt (id, project_id, task_id, lease_epoch, status,
                    request_key, provider_request_id, created_at, updated_at)
                values (:id, :project, :task, 1, 'UNKNOWN', :request, null, :time, :time)
                """).param("id", attemptId).param("project", project.id()).param("task", task.id())
                .param("request", UUID.randomUUID()).param("time", Timestamp.from(SAME_START)).update();
        jdbc.sql("""
                insert into llm_turn (project_id, run_id, step_index, status, model_config_version,
                    request_json, created_at)
                values (:project, :run, 0, 'REQUESTED', 1, cast(:request as jsonb), :time)
                """).param("project", project.id()).param("run", run.id())
                .param("request", privatePayload().toString()).param("time", Timestamp.from(SAME_START)).update();
        JsonNode page = list(owner, Map.of("projectId", project.id().toString(), "status", "UNKNOWN"));
        assertThat(page.path("items").size()).isEqualTo(2);
        List<String> kinds = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            kinds.add(item.path("kind").asText());
            assertThat(item.path("operation").asText()).isEqualTo("LEGACY");
            assertThat(item.path("historical").asBoolean()).isTrue();
            assertThat(item.path("startedAt").asText()).isEqualTo(SAME_START.toString());
            for (String absent : List.of("traceId", "respondedAt", "durationMs")) {
                assertThat(item.has(absent)).as(absent + " must be explicitly nullable").isTrue();
                assertThat(item.path(absent).isNull()).as(absent + " must not be fabricated").isTrue();
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder("IMAGE", "LLM");
        assertRedacted(page.toString());

        calls.record(new CallDescriptor(project.id(), task.id(), run.id(), null, CallLog.Kind.IMAGE,
                        CallLog.Operation.SUBMIT, "MOCK", "mock-image", true),
                () -> "accepted", ignored -> new CallOutcome(CallLog.Status.SUCCEEDED, "request-image", null));
        calls.record(new CallDescriptor(project.id(), null, run.id(), 0, CallLog.Kind.LLM,
                        CallLog.Operation.CHAT, "MOCK", "mock-chat", true),
                () -> "reply", ignored -> new CallOutcome(CallLog.Status.SUCCEEDED, null, null));
        JsonNode recorded = list(owner, Map.of("projectId", project.id().toString()));
        assertThat(recorded.path("items").size()).isEqualTo(2);
        assertThat(recorded.toString()).doesNotContain("LEGACY");
        assertThat(jdbc.sql("select status from task where id=:id").param("id", task.id())
                .query(String.class).single()).isEqualTo("UNKNOWN");
    }

    @Test
    void recordsCommittedStartBeforeInvocationAndPersistsResponseTiming() throws Exception {
        Project project = newProject("Recorded call");
        Task task = newTask(project, CallLog.Kind.IMAGE);
        CallDescriptor descriptor = new CallDescriptor(project.id(), task.id(), task.runId(), null,
                CallLog.Kind.IMAGE, CallLog.Operation.SUBMIT, "MOCK", "mock-image", true);
        String result = calls.record(descriptor, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var row = jdbc.sql("select status, responded_at, duration_ms, trace_id from call_log where project_id=:id")
                    .param("id", project.id()).query().singleRow();
            assertThat(row.get("status")).isEqualTo("RUNNING");
            assertThat(row.get("responded_at")).isNull();
            assertThat(row.get("duration_ms")).isNull();
            assertThat(row.get("trace_id").toString()).matches("[0-9a-f]{32}");
            return PRIVATE_MARKER;
        }, ignored -> new CallOutcome(CallLog.Status.SUCCEEDED, "accepted-request-1", null));
        assertThat(result).isEqualTo(PRIVATE_MARKER);
        JsonNode row = list(owner, Map.of("projectId", project.id().toString())).path("items").get(0);
        assertThat(row.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(row.path("operation").asText()).isEqualTo("SUBMIT");
        assertThat(row.path("providerRequestId").asText()).isEqualTo("accepted-request-1");
        assertThat(row.path("provider").asText()).isEqualTo("MOCK");
        assertThat(row.path("model").asText()).isEqualTo("mock-image");
        assertThat(row.path("mock").asBoolean()).isTrue();
        assertThat(row.path("historical").asBoolean()).isFalse();
        assertThat(Instant.parse(row.path("respondedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(row.path("startedAt").asText()));
        assertThat(row.path("durationMs").isNumber()).isTrue();
        assertThat(row.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertRedacted(row.toString());
    }

    @Test
    void exceptionPropagatesWhileOnlySafeFailureMetadataIsSaved() throws Exception {
        Project project = newProject("Failed call");
        ApiProblemException failure = new ApiProblemException(HttpStatus.BAD_GATEWAY,
                "PROVIDER_HTTP_ERROR", dev.agenvas.shared.i18n.ApiMessage.of("problem.fallback"), dev.agenvas.shared.i18n.ApiMessage.of("api.video-generation-parameters.video-generation-parameters-contain-an-unknown-field", PRIVATE_MARKER + PRIVATE_KEY + PRIVATE_ENDPOINT), false);
        assertThatThrownBy(() -> calls.record(new CallDescriptor(project.id(), null, fixtureRun(project).id(), 0,
                        CallLog.Kind.LLM, CallLog.Operation.CHAT, "OPENAI", "chat-model", false),
                () -> { throw failure; }, ignored -> new CallOutcome(CallLog.Status.SUCCEEDED, null, null)))
                .isSameAs(failure);
        JsonNode row = list(owner, Map.of("projectId", project.id().toString())).path("items").get(0);
        assertThat(row.path("errorCode").asText()).isEqualTo("PROVIDER_HTTP_ERROR");
        assertThat(row.path("status").asText()).isEqualTo("FAILED");
        assertThat(row.path("respondedAt").isNull()).isFalse();
        assertThat(row.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        assertRedacted(row.toString());

        Project submission = newProject("Unknown submission");
        Task task = newTask(submission, CallLog.Kind.VIDEO);
        IllegalStateException uncertain = new IllegalStateException(PRIVATE_MARKER + PRIVATE_KEY);
        assertThatThrownBy(() -> calls.record(new CallDescriptor(submission.id(), task.id(), task.runId(), null,
                        CallLog.Kind.VIDEO, CallLog.Operation.SUBMIT, PRIVATE_ENDPOINT, "unsafe model value", false),
                () -> { throw uncertain; }, ignored -> new CallOutcome(CallLog.Status.SUCCEEDED, null, null)))
                .isSameAs(uncertain);
        JsonNode unknown = list(owner, Map.of("projectId", submission.id().toString())).path("items").get(0);
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("errorCode").asText()).isEqualTo("CALL_TECHNICAL_FAILURE");
        assertThat(unknown.path("provider").isNull()).isTrue();
        assertThat(unknown.path("model").isNull()).isTrue();
        assertRedacted(unknown.toString());
    }

    @Test
    void expiredLeaseShowsUnknownWithoutInventingAResponseOrMutatingTheLedger() throws Exception {
        Project project = newProject("Interrupted call");
        UUID callId = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED,
                SAME_START, null);
        UUID taskId = jdbc.sql("select task_id from call_log where id=:id")
                .param("id", callId).query(UUID.class).single();
        jdbc.sql("update call_log set status='RUNNING', responded_at=null, duration_ms=null where id=:id")
                .param("id", callId).update();
        jdbc.sql("""
                update task set status='RUNNING', lease_owner='audit-test-worker',
                    lease_until=now() + interval '1 hour', lease_epoch=1 where id=:id
                """).param("id", taskId).update();
        Map<String, String> scope = Map.of("projectId", project.id().toString());
        assertThat(list(owner, scope).path("items").get(0).path("status").asText())
                .isEqualTo("RUNNING");
        jdbc.sql("update task set lease_until=now() - interval '1 second' where id=:id")
                .param("id", taskId).update();
        JsonNode interrupted = list(owner, Map.of("projectId", project.id().toString(), "status", "UNKNOWN"))
                .path("items").get(0);
        assertThat(interrupted.path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(interrupted.path("respondedAt").isNull()).isTrue();
        assertThat(interrupted.path("durationMs").isNull()).isTrue();
        assertThat(interrupted.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(interrupted.path("historical").asBoolean()).isFalse();
        assertThat(jdbc.sql("select status from call_log where id=:id").param("id", callId)
                .query(String.class).single()).isEqualTo("RUNNING");
    }

    @Test
    void debugSettingsAreDefaultOffPersistentCsrfProtectedAndUseCas() throws Exception {
        String path = "/api/v1/settings/debug";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(asUser(owner, "ROLE_USER"))))
                .andExpect(status().isForbidden());
        JsonNode setting = mapper.readTree(mvc.perform(get(path).with(authentication(asUser(owner, "ROLE_ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(setting.path("debugMode").asBoolean()).isFalse();
        String body = mapper.createObjectNode().put("debugMode", true)
                .put("expectedVersion", setting.path("version").asInt()).toString();
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN")))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_USER"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isOk());
        assertThat(calls.settings().debugMode()).isTrue();
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content("{\"debugMode\":null,\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void realHttpBodiesAreStoredOnlyWhileEnabledAndDetailRemainsOwnerScoped() throws Exception {
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = "{\"content\":\"RAW_RESPONSE\",\"reasoning_content\":\"PRIVATE_REASONING\",\"echo\":\"sk-debug-secret\"}".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        provider.start();
        try {
            var client = PinnedHttpClients.pinned(Dns.SYSTEM, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
            Project project = newProject("Raw debug log");
            Task task = newTask(project, CallLog.Kind.IMAGE);
            var descriptor = new CallDescriptor(project.id(), task.id(), task.runId(), null,
                    CallLog.Kind.IMAGE, CallLog.Operation.SUBMIT, "OPENAI", "debug-fixture", false);
            java.util.function.Supplier<String> invoke = () -> {
                try (var response = client.newCall(new Request.Builder()
                        .url("http://127.0.0.1:" + provider.getAddress().getPort() + "/chat")
                        .header("Authorization", "Bearer sk-debug-secret")
                        .post(RequestBody.create("{\"prompt\":\"RAW_PROMPT\"}", MediaType.get("application/json")))
                        .build()).execute()) { return response.body().string(); }
                catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            };
            calls.record(descriptor, invoke, ignored -> CallOutcome.succeeded(null));
            UUID disabled = jdbc.sql("select id from call_log where project_id=:id").param("id", project.id())
                    .query(UUID.class).single();
            assertThat(calls.debug(owner.userId(), disabled).captured()).isFalse();
            calls.updateSettings(true, calls.settings().version());
            calls.record(descriptor, invoke, ignored -> CallOutcome.succeeded(null));
            UUID captured = jdbc.sql("select call_id from call_log_debug d join call_log c on c.id=d.call_id where c.project_id=:id")
                    .param("id", project.id()).query(UUID.class).single();
            String path = PATH + "/" + captured + "/debug";
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(authentication(asUser(owner, "ROLE_USER"))))
                    .andExpect(status().isForbidden());
            AdminPrincipal other = new AdminPrincipal(UUID.randomUUID(), "other");
            mvc.perform(get(path).with(authentication(asUser(other, "ROLE_ADMIN"))))
                    .andExpect(status().isNotFound());
            String body = mvc.perform(get(path).with(authentication(asUser(owner, "ROLE_ADMIN"))))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).contains("RAW_PROMPT", "RAW_RESPONSE", "/chat")
                    .doesNotContain("sk-debug-secret", "PRIVATE_REASONING", "Authorization");
            String stored = jdbc.sql("select exchanges_json::text from call_log_debug where call_id=:id")
                    .param("id", captured).query(String.class).single();
            assertThat(stored).contains("RAW_PROMPT", "RAW_RESPONSE").doesNotContain("sk-debug-secret", "PRIVATE_REASONING");
            assertThat(list(owner, Map.of("projectId", project.id().toString())).toString())
                    .doesNotContain("RAW_PROMPT", "RAW_RESPONSE", "/chat");
            calls.updateSettings(false, calls.settings().version());
            calls.record(descriptor, invoke, ignored -> CallOutcome.succeeded(null));
            assertThat(jdbc.sql("select count(*) from call_log_debug d join call_log c on c.id=d.call_id where c.project_id=:id")
                    .param("id", project.id()).query(Integer.class).single()).isEqualTo(1);
            assertThat(calls.debug(owner.userId(), captured).captured()).isTrue();
            assertThat(DebugHttpCapture.enabled()).isFalse();
        } finally { provider.stop(0); }
    }

    @Test
    void retentionApiIsDefaultPermanentValidatedCasAndCsrfProtected() throws Exception {
        String path = "/api/v1/settings/call-log-retention";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(authentication(asUser(owner, "ROLE_USER")))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(authentication(asUser(owner, "ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(retention.settings().retentionDays()).isNull();
        String body = mapper.createObjectNode().put("retentionDays", 30)
                .put("expectedVersion", retention.settings().version()).toString();
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN")))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_USER"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        for (String invalid : List.of("{\"expectedVersion\":1}", "{\"retentionDays\":0,\"expectedVersion\":1}",
                "{\"retentionDays\":3651,\"expectedVersion\":1}", "{\"retentionDays\":30}")) {
            mvc.perform(put(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        retention.update(17, retention.settings().version());
        assertThat(retention.settings().retentionDays()).isEqualTo(17);
        retention.update(null, retention.settings().version());
        assertThat(retention.settings().retentionDays()).isNull();
    }

    @Test
    void cleansHistoryAtomicallyKeepsBusinessRowsAndNeverRecreatesLegacyLogs() throws Exception {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Project project = newProject("Terminal retention");
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, now.minus(Duration.ofDays(31)), null);
        UUID task = taskForCall(call);
        UUID run = fixtureRun(project).id();
        terminalHistory(project, task, now.minus(Duration.ofDays(31)));
        jdbc.sql("insert into call_log_debug(call_id,exchanges_json) values(:id,'[]'::jsonb)").param("id", call).update();
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isZero();
        retention.update(30, retention.settings().version());
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        assertThat(count("call_log", "id", call)).isZero();
        assertThat(count("call_log_debug", "call_id", call)).isZero();
        assertThat(count("provider_attempt", "task_id", task)).isZero();
        assertThat(count("tool_execution", "run_id", run)).isZero();
        assertThat(count("llm_turn", "run_id", run)).isZero();
        assertThat(count("task", "id", task)).isEqualTo(1);
        assertThat(count("agent_run", "id", run)).isEqualTo(1);
        assertThat(list(owner, Map.of("projectId", project.id().toString())).path("items").size()).isZero();
        retention.update(null, retention.settings().version());
        assertThat(list(owner, Map.of("projectId", project.id().toString())).path("items").size()).isZero();
    }

    @Test
    void boundsBatchesAndPreservesBoundaryAndRecentlyUpdatedUnknownExecution() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Instant cutoff = now.minus(Duration.ofDays(30));
        retention.update(30, retention.settings().version());
        List<UUID> eligible = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Project project = newProject("Bounded cleanup " + index);
            UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, cutoff.minusSeconds(1), null);
            terminalHistory(project, taskForCall(call), cutoff.minusSeconds(1)); eligible.add(call);
        }
        Project boundary = newProject("Retention boundary");
        UUID boundaryCall = insertCall(boundary, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, cutoff, null);
        terminalHistory(boundary, taskForCall(boundaryCall), cutoff);
        Project unknown = newProject("Retention unknown");
        UUID unknownCall = insertCall(unknown, CallLog.Kind.IMAGE, CallLog.Status.UNKNOWN, cutoff.minusSeconds(1), null);
        jdbc.sql("update task set status='UNKNOWN' where id=:id").param("id", taskForCall(unknownCall)).update();
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        assertThat(eligible.stream().mapToInt(id -> count("call_log", "id", id)).sum()).isEqualTo(1);
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isZero();
        assertThat(count("call_log", "id", boundaryCall)).isEqualTo(1);
        assertThat(count("call_log", "id", unknownCall)).isEqualTo(1);
        jdbc.sql("update call_log set responded_at=:time where id=:id").param("time", Timestamp.from(now))
                .param("id", boundaryCall).update();
        jdbc.sql("update agent_run set completed_at=:time,updated_at=:time where id=:id")
                .param("time", Timestamp.from(cutoff.minusSeconds(1))).param("id", fixtureRun(boundary).id()).update();
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isZero();
        assertThat(count("llm_turn", "run_id", fixtureRun(boundary).id())).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Task.Status.class,
            names = {"READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER", "UNKNOWN", "BLOCKED"})
    void stopsAndPurgesEveryExpiredNonterminalRunTaskWithWorkerFencing(Task.Status state) throws Exception {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Instant old = now.minus(Duration.ofDays(31));
        Project project = newProject("Expired " + state);
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.UNKNOWN, old, null);
        UUID taskId = taskForCall(call);
        terminalHistory(project, taskId, old);
        makeUnfinished(project, state, old, now);
        Task staleLease = tasks.get(owner.userId(), project.id(), taskId);
        long runVersion = runs.get(owner.userId(), project.id(), fixtureRun(project).id()).version();
        retention.update(30, retention.settings().version());
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        Task stopped = tasks.get(owner.userId(), project.id(), taskId);
        assertThat(stopped.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(stopped.cancelRequested()).isTrue();
        assertThat(stopped.errorCode()).isEqualTo("EXECUTION_HISTORY_CLEANED");
        assertThat(stopped.leaseEpoch()).isEqualTo(staleLease.leaseEpoch() + 1);
        assertThat(stopped.leaseOwner()).isNull(); assertThat(stopped.leaseUntil()).isNull();
        assertThat(stopped.version()).isEqualTo(staleLease.version() + 1);
        assertThat(stopped.providerRequestId()).isEqualTo("expired-provider-request");
        var stoppedRun = runs.get(owner.userId(), project.id(), fixtureRun(project).id());
        assertThat(stoppedRun.status()).isEqualTo(AgentRun.Status.CANCELED);
        assertThat(stoppedRun.version()).isEqualTo(runVersion + 1);
        assertThat(jdbc.sql("select active_run_id from project where id=:id").param("id", project.id())
                .query((rs, index) -> rs.getObject(1)).optional()).isEmpty();
        assertThat(count("call_log", "id", call)).isZero();
        assertThat(count("llm_turn", "run_id", stoppedRun.id())).isZero();
        assertThat(count("tool_execution", "run_id", stoppedRun.id())).isZero();
        assertThat(count("provider_attempt", "task_id", taskId)).isZero();
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:id and type='task.status.changed' and aggregate_id=:task")
                .param("id", project.id()).param("task", taskId).query(Integer.class).single()).isPositive();
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:id and type='agent.run.changed' and aggregate_version=:version")
                .param("id", project.id()).param("version", stoppedRun.version()).query(Integer.class).single()).isPositive();
        assertThat(taskRepository.heartbeat(taskId, "expired-worker", staleLease.leaseEpoch(), now, now.plusSeconds(60))).isFalse();
        assertThat(taskRepository.finishProviderResult(staleLease, "expired-worker", mapper.createObjectNode(), now)).isFalse();
        assertThat(taskRepository.recordLateResult(staleLease, mapper.createObjectNode(), now)).isFalse();
        assertThat(taskRepository.claimDueProviderPolls("cleanup-proof", 100, now, now.plusSeconds(60))).extracting(Task::id).doesNotContain(taskId);
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isZero();
        assertThat(list(owner, Map.of("projectId", project.id().toString())).path("items").size()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Task.Status.class,
            names = {"READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER", "UNKNOWN", "BLOCKED"})
    void alsoStopsExpiredStandaloneTasksInEveryNonterminalState(Task.Status state) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z"), old = now.minus(Duration.ofDays(31));
        Project project = newProject("Expired direct " + state);
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.UNKNOWN, old, null);
        UUID task = taskForCall(call);
        terminalHistory(project, task, old);
        makeUnfinished(project, state, old, now);
        jdbc.sql("update task set run_id=null where id=:id").param("id", task).update();
        jdbc.sql("update call_log set run_id=null,status='RUNNING',responded_at=null,duration_ms=null where id=:id").param("id", call).update();
        // The parent run remains recent; only its detached direct task is eligible.
        jdbc.sql("update agent_run set updated_at=:time where id=:id").param("time", Timestamp.from(now))
                .param("id", fixtureRun(project).id()).update();
        retention.update(30, retention.settings().version());
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), task).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(count("call_log", "id", call)).isZero();
        assertThat(count("provider_attempt", "task_id", task)).isZero();
        assertThat(runs.get(owner.userId(), project.id(), fixtureRun(project).id()).status()).isEqualTo(AgentRun.Status.BLOCKED);
    }

    @Test
    void stoppingAndHistoryDeletionRollBackTogetherOnFailure() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z"), old = now.minus(Duration.ofDays(31));
        Project project = newProject("Abandon rollback");
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.UNKNOWN, old, null);
        UUID taskId = taskForCall(call); terminalHistory(project, taskId, old);
        makeUnfinished(project, Task.Status.UNKNOWN, old, now);
        Task before = tasks.get(owner.userId(), project.id(), taskId);
        int eventCount = count("project_event", "project_id", project.id());
        retention.update(30, retention.settings().version());
        jdbc.sql("CREATE FUNCTION retention_stop_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$").update();
        jdbc.sql("CREATE TRIGGER retention_stop_failure BEFORE DELETE ON provider_attempt FOR EACH ROW EXECUTE FUNCTION retention_stop_failure()").update();
        try {
            assertThatThrownBy(() -> auditRepository.purgeExpired(now, 1, retention.settings().version())).isInstanceOf(RuntimeException.class);
            assertThat(tasks.get(owner.userId(), project.id(), taskId)).isEqualTo(before);
            assertThat(runs.get(owner.userId(), project.id(), fixtureRun(project).id()).status()).isEqualTo(AgentRun.Status.BLOCKED);
            assertThat(count("project_event", "project_id", project.id())).isEqualTo(eventCount);
            assertThat(count("call_log", "id", call)).isEqualTo(1);
            assertThat(count("llm_turn", "run_id", fixtureRun(project).id())).isEqualTo(1);
            assertThat(count("provider_attempt", "task_id", taskId)).isEqualTo(1);
            assertThat(jdbc.sql("select active_run_id from project where id=:id").param("id", project.id()).query(UUID.class).single()).isEqualTo(fixtureRun(project).id());
        } finally {
            jdbc.sql("DROP TRIGGER retention_stop_failure ON provider_attempt").update();
            jdbc.sql("DROP FUNCTION retention_stop_failure()").update();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = AgentRun.Status.class,
            names = {"QUEUED", "RUNNING", "WAITING_TASKS", "BLOCKED", "CANCEL_REQUESTED"})
    void abandonsEveryExpiredNonterminalRunState(AgentRun.Status state) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z"), old = now.minus(Duration.ofDays(31));
        Project project = newProject("Expired run " + state);
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.UNKNOWN, old, null);
        terminalHistory(project, taskForCall(call), old);
        // An expired nonterminal fixture has no completion time, so retention uses its old creation time.
        jdbc.sql("update agent_run set status=:status,completed_at=null,created_at=:time where id=:id")
                .param("status", state.name()).param("time", Timestamp.from(old))
                .param("id", fixtureRun(project).id()).update();
        retention.update(30, retention.settings().version());
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), fixtureRun(project).id()).status()).isEqualTo(AgentRun.Status.CANCELED);
        assertThat(count("call_log", "id", call)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"llm_turn", "tool_execution", "provider_attempt"})
    void recentLedgerActivityDefersTheEntireExpiredUnknownExecution(String ledger) {
        Instant now = Instant.parse("2026-10-01T00:00:00Z"), old = now.minus(Duration.ofDays(31));
        Project project = newProject("Recent " + ledger);
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.UNKNOWN, old, null);
        UUID task = taskForCall(call); terminalHistory(project, task, old);
        makeUnfinished(project, Task.Status.UNKNOWN, old, now);
        switch (ledger) {
            case "llm_turn" -> jdbc.sql("update llm_turn set responded_at=:time where run_id=:id").param("time", Timestamp.from(now)).param("id", fixtureRun(project).id()).update();
            case "tool_execution" -> jdbc.sql("update tool_execution set completed_at=:time where run_id=:id").param("time", Timestamp.from(now)).param("id", fixtureRun(project).id()).update();
            case "provider_attempt" -> jdbc.sql("update provider_attempt set updated_at=:time where task_id=:id").param("time", Timestamp.from(now)).param("id", task).update();
            default -> throw new IllegalArgumentException("Unknown fixture ledger");
        }
        retention.update(30, retention.settings().version());
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isZero();
        assertThat(tasks.get(owner.userId(), project.id(), task).status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(count("call_log", "id", call)).isEqualTo(1);
        assertThat(count("llm_turn", "run_id", fixtureRun(project).id())).isEqualTo(1);
        assertThat(count("provider_attempt", "task_id", task)).isEqualTo(1);
    }

    private void makeUnfinished(Project project, Task.Status state, Instant old, Instant now) {
        UUID run = fixtureRun(project).id();
        jdbc.sql("update agent_run set status='BLOCKED',completed_at=null,created_at=:old,updated_at=:old where id=:id")
                .param("old", Timestamp.from(old)).param("id", run).update();
        jdbc.sql("update project set active_run_id=:run where id=:id").param("run", run).param("id", project.id()).update();
        jdbc.sql("update task set status=:status,completed_at=null,created_at=:old,updated_at=:old,lease_epoch=1,lease_owner='expired-worker',lease_until=:until,provider_request_id='expired-provider-request' where run_id=:run")
                .param("status", state.name()).param("old", Timestamp.from(old)).param("until", Timestamp.from(now.plusSeconds(60))).param("run", run).update();
    }

    @Test
    void rollsBackEveryDeletionWhenOneLedgerDeleteFails() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Project project = newProject("Rollback cleanup");
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, now.minus(Duration.ofDays(31)), null);
        UUID task = taskForCall(call);
        terminalHistory(project, task, now.minus(Duration.ofDays(31)));
        retention.update(30, retention.settings().version());
        jdbc.sql("CREATE FUNCTION retention_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test rollback'; END $$").update();
        jdbc.sql("CREATE TRIGGER retention_test_failure BEFORE DELETE ON provider_attempt FOR EACH ROW EXECUTE FUNCTION retention_test_failure()").update();
        try {
            assertThatThrownBy(() -> auditRepository.purgeExpired(now, 1, retention.settings().version())).isInstanceOf(RuntimeException.class);
            assertThat(count("call_log", "id", call)).isEqualTo(1);
            assertThat(count("llm_turn", "run_id", fixtureRun(project).id())).isEqualTo(1);
            assertThat(count("tool_execution", "run_id", fixtureRun(project).id())).isEqualTo(1);
            assertThat(count("provider_attempt", "task_id", task)).isEqualTo(1);
        } finally {
            jdbc.sql("DROP TRIGGER retention_test_failure ON provider_attempt").update();
            jdbc.sql("DROP FUNCTION retention_test_failure()").update();
        }
    }

    @Test
    void alsoPurgesStandaloneAndLegacyOnlyHistories() throws Exception {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Instant old = now.minus(Duration.ofDays(31));
        Project legacy = newProject("Legacy-only retention");
        UUID oldCall = insertCall(legacy, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, old, null);
        UUID legacyTask = taskForCall(oldCall);
        terminalHistory(legacy, legacyTask, old);
        jdbc.sql("delete from call_log where id=:id").param("id", oldCall).update();
        assertThat(list(owner, Map.of("projectId", legacy.id().toString())).path("items").size()).isEqualTo(2);
        Project direct = newProject("Standalone retention");
        UUID directCall = insertCall(direct, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, old, null);
        UUID directTask = taskForCall(directCall);
        jdbc.sql("update call_log set run_id=null where id=:id").param("id", directCall).update();
        jdbc.sql("update task set run_id=null,status='SUCCEEDED',completed_at=:time,updated_at=:time where id=:id")
                .param("time", Timestamp.from(old)).param("id", directTask).update();
        retention.update(30, retention.settings().version());
        assertThat(auditRepository.purgeExpired(now, 2, retention.settings().version())).isEqualTo(2);
        assertThat(count("call_log", "id", directCall)).isZero();
        assertThat(count("task", "id", directTask)).isEqualTo(1);
        assertThat(list(owner, Map.of("projectId", legacy.id().toString())).path("items").size()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"task", "project", "agent_run"})
    void skipsAnEntireRunWhenAMemberOrItsProjectIsLocked(String lockedTable) throws Exception {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Instant old = now.minus(Duration.ofDays(31));
        Project project = newProject("Locked retention checkpoint");
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, old, null);
        UUID task = taskForCall(call);
        terminalHistory(project, task, old);
        retention.update(30, retention.settings().version());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var checkpoint = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        UUID lockedId = switch (lockedTable) {
                            case "task" -> task;
                            case "project" -> project.id();
                            case "agent_run" -> fixtureRun(project).id();
                            default -> throw new IllegalArgumentException("Unknown lock fixture");
                        };
                        // Fixed parameterized test identifiers only.
                        jdbc.sql("select id from " + lockedTable + " where id=:id for update").param("id", lockedId).query(UUID.class).single();
                        locked.countDown();
                        try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    }));
            try {
                assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var cleanup = pool.submit(() -> auditRepository.purgeExpired(now, 1, retention.settings().version()));
                assertThat(cleanup.get(3, java.util.concurrent.TimeUnit.SECONDS)).isZero();
                assertThat(count("call_log", "id", call)).isEqualTo(1);
                assertThat(count("llm_turn", "run_id", fixtureRun(project).id())).isEqualTo(1);
                assertThat(count("provider_attempt", "task_id", task)).isEqualTo(1);
            } finally { release.countDown(); }
            checkpoint.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(auditRepository.purgeExpired(now, 1, retention.settings().version())).isEqualTo(1);
        assertThat(count("call_log", "id", call)).isZero();
    }

    @Test
    void manuallyCleansOnlyOnPostWithAdministratorCsrfAndConfirmedPolicyVersion() throws Exception {
        assertThat(context.containsBean("callLogRetentionScheduler")).isFalse();
        String path = "/api/v1/settings/call-log-retention/cleanup";
        String body = mapper.createObjectNode().put("expectedVersion", retention.settings().version()).toString();
        mvc.perform(post(path).contentType("application/json").content(body).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_USER"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_ADMIN")))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        JsonNode permanent = mapper.readTree(mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
        assertThat(permanent.path("cleanedExecutions").asInt()).isZero();
        assertThat(permanent.path("batchLimitReached").asBoolean()).isFalse();
        Instant old = context.getBean(java.time.Clock.class).instant().minus(Duration.ofDays(31));
        Project project = newProject("Manual cleanup API");
        UUID call = insertCall(project, CallLog.Kind.IMAGE, CallLog.Status.SUCCEEDED, old, null);
        UUID task = taskForCall(call);
        terminalHistory(project, task, old);
        retention.update(30, retention.settings().version());
        // Reading and saving policy never delete, and the stale confirmation is rejected before deletion.
        assertThat(count("call_log", "id", call)).isEqualTo(1);
        mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(body)).andExpect(status().isConflict());
        assertThat(count("llm_turn", "run_id", fixtureRun(project).id())).isEqualTo(1);
        String current = mapper.createObjectNode().put("expectedVersion", retention.settings().version()).toString();
        JsonNode result = mapper.readTree(mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(current)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
        assertThat(result.path("cleanedExecutions").asInt()).isEqualTo(1);
        assertThat(result.path("batchLimitReached").asBoolean()).isFalse();
        assertThat(count("call_log", "id", call)).isZero();
        assertThat(count("provider_attempt", "task_id", task)).isZero();
        assertThat(count("llm_turn", "run_id", fixtureRun(project).id())).isZero();
        assertThat(count("task", "id", task)).isEqualTo(1);
        JsonNode again = mapper.readTree(mvc.perform(post(path).with(authentication(asUser(owner, "ROLE_ADMIN"))).with(csrf())
                .contentType("application/json").content(current)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(again.path("cleanedExecutions").asInt()).isZero();
    }

    private UUID taskForCall(UUID call) {
        return jdbc.sql("select task_id from call_log where id=:id").param("id", call).query(UUID.class).single();
    }
    private void terminalHistory(Project project, UUID task, Instant time) {
        UUID run = fixtureRun(project).id();
        jdbc.sql("update task set status='SUCCEEDED',completed_at=:time,updated_at=:time,lease_owner=null,lease_until=null where run_id=:run")
                .param("time", Timestamp.from(time)).param("run", run).update();
        jdbc.sql("update project set active_run_id=null where id=:id").param("id", project.id()).update();
        jdbc.sql("update agent_run set status='SUCCEEDED',completed_at=:time,updated_at=:time where id=:id")
                .param("time", Timestamp.from(time)).param("id", run).update();
        jdbc.sql("""
                insert into llm_turn(project_id,run_id,step_index,status,model_config_version,request_json,response_json,created_at,responded_at)
                values(:project,:run,0,'RESPONDED',1,'{}','{}',:time,:time)
                """).param("project", project.id()).param("run", run).param("time", Timestamp.from(time)).update();
        jdbc.sql("""
                insert into tool_execution(id,project_id,run_id,step_index,tool_call_id,tool_name,argument_hash,status,result_json,created_at,completed_at)
                values(:id,:project,:run,0,'retention-tool','read_project',:hash,'COMPLETED','{}',:time,:time)
                """).param("id", UUID.randomUUID()).param("project", project.id()).param("run", run)
                .param("hash", "a".repeat(64)).param("time", Timestamp.from(time)).update();
        jdbc.sql("""
                insert into provider_attempt(id,project_id,task_id,lease_epoch,status,request_key,created_at,updated_at)
                values(:id,:project,:task,1,'ACCEPTED',:request,:time,:time)
                """).param("id", UUID.randomUUID()).param("project", project.id()).param("task", task)
                .param("request", UUID.randomUUID()).param("time", Timestamp.from(time)).update();
    }
    private int count(String table, String column, UUID id) {
        // Only fixed test identifiers; never API input.
        return jdbc.sql("select count(*) from " + table + " where " + column + "=:id").param("id", id).query(Integer.class).single();
    }

    private Project newProject(String title) {
        return projects.create(owner.userId(), title, Project.AspectRatio.SQUARE_1_1);
    }

    private UUID insertCall(Project project, CallLog.Kind kind, CallLog.Status callStatus,
            Instant startedAt, String trace) {
        UUID id = UUID.randomUUID();
        boolean llm = kind == CallLog.Kind.LLM;
        Task task = llm ? null : newTask(project, kind);
        String operation = llm ? "CHAT" : "SUBMIT";
        jdbc.sql("""
                insert into call_log (id, project_id, task_id, run_id, step_index, kind, operation, status, provider, model,
                    trace_id, started_at, responded_at, duration_ms, mock)
                values (:id, :project, :task, :run, :step, :kind, :operation, :status, 'MOCK', 'fixture-model',
                    :trace, :started, :responded, 125, true)
                """).param("id", id).param("project", project.id()).param("kind", kind.name())
                .param("task", task == null ? null : task.id(), Types.OTHER)
                .param("run", fixtureRun(project).id()).param("step", llm ? 0 : null, Types.INTEGER)
                .param("operation", operation).param("status", callStatus.name())
                .param("trace", trace == null ? UUID.randomUUID().toString().replace("-", "") : trace)
                .param("started", Timestamp.from(startedAt))
                .param("responded", Timestamp.from(startedAt.plusMillis(125))).update();
        return id;
    }

    private AgentRun fixtureRun(Project project) {
        return fixtureRuns.computeIfAbsent(project.id(), ignored -> {
            var agent = agents.create(project.ownerId(), project.id(), "Audit fixture", "Create", List.of());
            return runs.create(project.ownerId(), project.id(), agent.id(), "Audit fixture",
                    "audit-fixture-run").run();
        });
    }

    private Task newTask(Project project, CallLog.Kind kind) {
        Task.Kind taskKind = kind == CallLog.Kind.IMAGE ? Task.Kind.IMAGE_GENERATION : Task.Kind.VIDEO_GENERATION;
        return tasks.create(project.ownerId(), project.id(), fixtureRun(project).id(),
                "audit-" + UUID.randomUUID(), taskKind, privatePayload(), 1);
    }

    private JsonNode privatePayload() {
        return mapper.createObjectNode().put("prompt", PRIVATE_MARKER)
                .put("apiKey", PRIVATE_KEY).put("endpoint", PRIVATE_ENDPOINT);
    }

    private JsonNode list(AdminPrincipal principal, Map<String, String> filters) throws Exception {
        return mapper.readTree(mvc.perform(request(principal, filters)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder request(AdminPrincipal principal, Map<String, String> filters) {
        var builder = get(PATH).with(authentication(asUser(principal, "ROLE_ADMIN")));
        filters.forEach(builder::param);
        return builder;
    }

    private static UsernamePasswordAuthenticationToken asUser(AdminPrincipal principal, String role) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("id").asText()));
        return result;
    }

    private static void assertRedacted(String body) {
        assertThat(body).doesNotContain(PRIVATE_MARKER, PRIVATE_KEY, PRIVATE_ENDPOINT,
                "requestJson", "responseJson", "request_json", "response_json", "apiKey", "endpoint");
    }
}

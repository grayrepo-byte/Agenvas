package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.application.ComfyUiUnknownTaskReconciler;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Crash recovery against fake ComfyUI HTTP and real PostgreSQL, never a real GPU claim. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=comfy-reconcile-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=comfyui",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.comfyui.image.checkpoint=test-model.safetensors"})
class ComfyUiReconciliationPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final AtomicReference<UUID> CANDIDATE = new AtomicReference<>();
    private static final AtomicReference<String> LOOKUP = new AtomicReference<>("MISSING");
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();
    private static final AtomicInteger LOOKUPS = new AtomicInteger();
    private static final HttpServer SERVER = server();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.provider.comfyui.endpoint",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private PlanProviderProperties provider;
    @Autowired private ComfyUiImageWorkflow workflow;
    @Autowired private ComfyUiClient client;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void missingEvidenceStaysUnknownAndVerifiedOriginalResumesWithoutNewSubmission()
            throws Exception {
        AdminPrincipal owner = identities.setup("comfy-reconcile-integration-secret",
                "reconcile-admin", "reconcile-password-123");
        Project project = projects.create(owner.userId(), "Original prompt recovery",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Make one image", "reconcile-run").run();
        run = runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.WAITING_TASKS);
        ObjectNode input = mapper.createObjectNode();
        input.put("providerConfigVersion", provider.configVersion());
        input.put("workflowVersion", workflow.version());
        Task original = tasks.create(owner.userId(), project.id(), run.id(), null,
                "image-original", Task.Kind.IMAGE_GENERATION, input, null, 1, List.of());
        Task lease = tasks.claimImagesDue("crashed-submitter", 1).getFirst();
        UUID promptId = tasks.beginSubmission(lease, "crashed-submitter", client.originSha256());
        CANDIDATE.set(promptId);
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", original.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        // This fixture omits an approved plan, so reproduce the Run blockage explicitly.
        AgentRun waiting = runs.get(owner.userId(), project.id(), run.id());
        runs.transition(owner.userId(), project.id(), run.id(), waiting.version(),
                AgentRun.Status.BLOCKED);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);

        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var authenticated = authentication(new UsernamePasswordAuthenticationToken(
                owner, null, List.of()));
        String path = "/api/v1/projects/" + project.id() + "/tasks/" + original.id()
                + "/reconcile";
        mvc.perform(get("/api/v1/projects/" + project.id() + "/tasks/" + original.id()
                        + "/attempts").with(authenticated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reconcilable").value(true))
                .andExpect(jsonPath("$[0].candidateRequestId").value(promptId.toString()))
                .andExpect(jsonPath("$[0].candidateOriginSha256").doesNotExist());
        mvc.perform(post(path).with(authenticated)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/projects/" + UUID.randomUUID() + "/tasks/"
                        + original.id() + "/reconcile")
                .with(authenticated).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(path).with(authenticated).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.taskStatus").value("UNKNOWN"));
        assertThat(tasks.get(owner.userId(), project.id(), original.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(SUBMISSIONS).hasValue(0);

        ComfyUiClient rotatedClient = new ComfyUiClient(
                new ComfyUiProperties("http://127.0.0.1:65534"), mapper);
        ComfyUiClientRegistry rotatedRegistry = new ComfyUiClientRegistry(jdbc, mapper,
                new PlanProviderProperties("comfyui", 2),
                new ComfyUiProperties("http://127.0.0.1:65534"), rotatedClient, false);
        rotatedRegistry.registerActive();
        int oldLookups = LOOKUPS.get();
        ComfyUiImageWorkflow rotatedWorkflow = new ComfyUiImageWorkflow(
                new ComfyUiImageProperties("rotated-model.safetensors"), mapper);
        assertThat(rotatedWorkflow.version()).isNotEqualTo(workflow.version());
        var historical = new ComfyUiUnknownTaskReconciler(tasks, rotatedRegistry)
                .reconcile(owner.userId(), project.id(), original.id());
        assertThat(historical.outcome())
                .isEqualTo(dev.agenvas.task.application.UnknownTaskReconciler.Outcome.NO_EVIDENCE);
        assertThat(LOOKUPS.get()).isGreaterThan(oldLookups);
        assertThat(SUBMISSIONS).hasValue(0);

        LOOKUP.set("MATCH");
        mvc.perform(post(path).with(authenticated).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESUMED"))
                .andExpect(jsonPath("$.taskStatus").value("WAITING_PROVIDER"))
                .andExpect(jsonPath("$.providerRequestId").value(promptId.toString()));
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), original.id()))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo(ProviderAttempt.Status.ACCEPTED);
                    assertThat(attempt.candidateRequestId()).isEqualTo(promptId);
                    assertThat(attempt.providerRequestId()).isEqualTo(promptId.toString());
                });
        assertThat(tasks.claimComfyImagePolls("original-poller", 1).getFirst()
                .providerRequestId()).isEqualTo(promptId.toString());
        assertThat(SUBMISSIONS).hasValue(0);

        Task mismatched = tasks.create(owner.userId(), project.id(), run.id(), null,
                "image-mismatched", Task.Kind.IMAGE_GENERATION, input, null, 1, List.of());
        Task mismatchLease = tasks.claimImagesDue("mismatch-submitter", 1).getFirst();
        UUID mismatchId = tasks.beginSubmission(mismatchLease, "mismatch-submitter",
                client.originSha256());
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", mismatched.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        CANDIDATE.set(mismatchId);
        LOOKUP.set("MISMATCH");
        mvc.perform(post("/api/v1/projects/" + project.id() + "/tasks/" + mismatched.id()
                        + "/reconcile").with(authenticated).with(csrf()))
                .andExpect(status().isBadGateway());
        assertThat(tasks.get(owner.userId(), project.id(), mismatched.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), mismatched.id()))
                .singleElement().satisfies(attempt ->
                        assertThat(attempt.status()).isEqualTo(ProviderAttempt.Status.UNKNOWN));
        jdbc.sql("update provider_attempt set candidate_origin_sha256 = :other where task_id = :id")
                .param("other", "0".repeat(64)).param("id", mismatched.id()).update();
        LOOKUP.set("MATCH");
        int lookupsBeforeOriginChange = LOOKUPS.get();
        mvc.perform(post("/api/v1/projects/" + project.id() + "/tasks/" + mismatched.id()
                        + "/reconcile").with(authenticated).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(LOOKUPS).hasValue(lookupsBeforeOriginChange);

        Task legacy = tasks.create(owner.userId(), project.id(), run.id(), null,
                "image-legacy", Task.Kind.IMAGE_GENERATION, input, null, 1, List.of());
        Task legacyLease = tasks.claimImagesDue("legacy-submitter", 1).getFirst();
        tasks.beginSubmission(legacyLease, "legacy-submitter");
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", legacy.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        int lookupsBeforeLegacy = LOOKUPS.get();
        mvc.perform(post("/api/v1/projects/" + project.id() + "/tasks/" + legacy.id()
                        + "/reconcile").with(authenticated).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(LOOKUPS).hasValue(lookupsBeforeLegacy);
        assertThat(SUBMISSIONS).hasValue(0);

        jdbc.sql("update provider_attempt set candidate_origin_sha256 = :original where task_id = :id")
                .param("original", client.originSha256()).param("id", mismatched.id()).update();
        runs.cancel(owner.userId(), project.id(), run.id());
        CANDIDATE.set(mismatchId);
        LOOKUP.set("MATCH");
        int lookupsBeforeCanceled = LOOKUPS.get();
        mvc.perform(post("/api/v1/projects/" + project.id() + "/tasks/" + mismatched.id()
                        + "/reconcile").with(authenticated).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(LOOKUPS).hasValue(lookupsBeforeCanceled);
        assertThat(tasks.get(owner.userId(), project.id(), mismatched.id()).providerRequestId())
                .isNull();
        assertThat(SUBMISSIONS).hasValue(0);
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/prompt", exchange -> {
                SUBMISSIONS.incrementAndGet();
                respond(exchange, 500, "unexpected submission");
            });
            server.createContext("/history/", exchange -> {
                LOOKUPS.incrementAndGet();
                respond(exchange, 200, "{}");
            });
            server.createContext("/queue", exchange -> {
                String pending = "[]";
                if (!"MISSING".equals(LOOKUP.get())) {
                    UUID id = CANDIDATE.get();
                    String clientId = "MATCH".equals(LOOKUP.get()) ? id.toString()
                            : UUID.randomUUID().toString();
                    pending = "[[0,\"" + id + "\",{}, {\"client_id\":\"" + clientId
                            + "\"}]]";
                }
                respond(exchange, 200, "{\"queue_running\":[],\"queue_pending\":"
                        + pending + "}");
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake ComfyUI", failure);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

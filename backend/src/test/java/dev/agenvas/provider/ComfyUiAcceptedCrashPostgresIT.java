package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskRecoveryScheduler;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Kills a real submitter after fake ComfyUI accepts a prompt but before its response is saved. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=comfy-crash-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.export.scheduler-enabled=false",
        "agenvas.provider.mode=comfyui",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.comfyui.image.checkpoint=test-model.safetensors"})
class ComfyUiAcceptedCrashPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final CountDownLatch PROMPT_ACCEPTED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_PROMPT = new CountDownLatch(1);
    private static final AtomicReference<UUID> ACCEPTED_ID = new AtomicReference<>();
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();
    private static final HttpServer SERVER = startServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.provider.comfyui.endpoint",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        RELEASE_PROMPT.countDown();
        SERVER.stop(0);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private ProviderProperties provider;
    @Autowired private ComfyUiImageWorkflow workflow;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    /** Parent test context must not race the restarted child for expired-lease recovery. */
    @MockitoBean private TaskRecoveryScheduler disabledParentRecovery;

    @Test
    void acceptedPromptSurvivesSubmitterProcessKillWithoutSecondSubmission() throws Exception {
        AdminPrincipal owner = identities.setup("comfy-crash-integration-secret",
                "crash-admin", "crash-password-123");
        Project project = projects.create(owner.userId(), "Accepted crash",
                Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Generate image", "accepted-crash-run").run();
        run = runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.WAITING_TASKS);
        ObjectNode input = mapper.createObjectNode();
        input.put("providerConfigVersion", provider.configVersion());
        input.put("workflowVersion", workflow.version());
        input.put("prompt", "A coffee pour in a studio");
        // Even an empty reference set is an explicit frozen input in the current contract.
        ObjectNode mediaInput = input.putObject("mediaInput");
        mediaInput.putArray("images");
        mediaInput.putArray("audios");
        Task submitted = tasks.create(owner.userId(), project.id(), run.id(),
                "image-crash", Task.Kind.IMAGE_GENERATION, input, null, 1, List.of());

        Path childLog = Files.createTempFile("agenvas-comfy-accepted-crash-", ".log");
        Process first = null;
        Process second = null;
        try {
            first = startChild(childLog);
            assertThat(PROMPT_ACCEPTED.await(30, TimeUnit.SECONDS))
                    .as("fake ComfyUI must accept the child's POST /prompt").isTrue();
            UUID acceptedId = ACCEPTED_ID.get();
            assertThat(acceptedId).isNotNull();
            assertThat(SUBMISSIONS).hasValue(1);
            assertThat(tasks.get(owner.userId(), project.id(), submitted.id()).status())
                    .isEqualTo(Task.Status.SUBMITTING);
            assertThat(jdbc.sql("select candidate_request_id from provider_attempt "
                            + "where task_id = :id")
                    .param("id", submitted.id()).query(UUID.class).single())
                    .isEqualTo(acceptedId);
            assertThat(jdbc.sql("select provider_request_id from task where id = :id")
                    .param("id", submitted.id()).query(String.class).optional()).isEmpty();

            first.destroyForcibly();
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).isNotZero();
            RELEASE_PROMPT.countDown();
            assertThat(tasks.get(owner.userId(), project.id(), submitted.id()).status())
                    .isEqualTo(Task.Status.SUBMITTING);
            jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                    .param("id", submitted.id()).update();

            second = startChild(childLog);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline
                    && tasks.get(owner.userId(), project.id(), submitted.id()).status()
                    != Task.Status.UNKNOWN) {
                Thread.sleep(200);
            }
            assertThat(second.isAlive()).isTrue();
            assertThat(tasks.get(owner.userId(), project.id(), submitted.id()).status())
                    .isEqualTo(Task.Status.UNKNOWN);
            assertThat(jdbc.sql("select status from provider_attempt where task_id = :id")
                    .param("id", submitted.id()).query(String.class).single())
                    .isEqualTo("UNKNOWN");
            assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                    .param("id", submitted.id()).query(Integer.class).single()).isEqualTo(1);
            // Let another full submitter tick pass after recovery, not just its first scan.
            Thread.sleep(5_500);
            assertThat(SUBMISSIONS).hasValue(1);
            assertThat(jdbc.sql("select count(*) from task where id = :id and status = 'READY'")
                    .param("id", submitted.id()).query(Integer.class).single()).isZero();
        } finally {
            RELEASE_PROMPT.countDown();
            stopChild(first);
            stopChild(second);
            Files.deleteIfExists(childLog);
        }
    }

    /** A packaged second JVM shares only this test's PostgreSQL and fake HTTP endpoint. */
    private Process startChild(Path log) throws IOException {
        Path jar = Path.of("target/agenvas-server-0.1.0-SNAPSHOT.jar").toAbsolutePath();
        assertThat(Files.isRegularFile(jar)).isTrue();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Xmx512m", "-jar",
                jar.toString(), "--server.port=0", "--agenvas.provider.mode=comfyui",
                "--agenvas.provider.comfyui.endpoint=http://127.0.0.1:"
                        + SERVER.getAddress().getPort(),
                "--agenvas.provider.comfyui.image.checkpoint=test-model.safetensors",
                "--agenvas.provider.comfyui.scheduler-enabled=true",
                "--agenvas.provider.comfyui.video.scheduler-enabled=false",
                "--agenvas.llm.scheduler-enabled=false",
                "--agenvas.export.scheduler-enabled=false")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("AGENVAS_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("AGENVAS_DB_USER", POSTGRES.getUsername());
        builder.environment().put("AGENVAS_DB_PASSWORD", POSTGRES.getPassword());
        builder.environment().put("AGENVAS_BOOTSTRAP_SECRET", "comfy-crash-integration-secret");
        return builder.start();
    }

    /** Terminate only subprocesses created by this test. */
    private static void stopChild(Process child) throws InterruptedException {
        if (child != null && child.isAlive()) {
            child.destroyForcibly();
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upload/image", exchange -> {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, "{\"name\":\"crash-input.png\",\"type\":\"input\",\"subfolder\":\"\"}");
            });
            server.createContext("/prompt", exchange -> {
                JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                UUID id = UUID.fromString(body.path("prompt_id").asText());
                assertThat(body.path("client_id").asText()).isEqualTo(id.toString());
                ACCEPTED_ID.set(id);
                SUBMISSIONS.incrementAndGet();
                PROMPT_ACCEPTED.countDown();
                try {
                    RELEASE_PROMPT.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake ComfyUI", failure);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

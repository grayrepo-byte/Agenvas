package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.OpenAiImage2Client;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real Task/Asset path against a loopback fake OpenAI API; no paid call is made. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=openai-image-integration-secret")
class OpenAiImage2PostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final AtomicInteger EDITS = new AtomicInteger();
    private static final AtomicInteger DOWNLOADS = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_GENERATION = new AtomicBoolean();
    /** 中转站只回结果 URL 的形态；官方 API 不走这条分支。 */
    private static final AtomicBoolean URL_RESULT = new AtomicBoolean();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stopServer() { SERVER.stop(0); }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private ExecutionPlanService plans;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void approvedReferenceUsesEditsAndLostGenerationResponseRemainsUnknown() {
        var connection = catalog.createConnection("openai-it-connection", "OpenAI fake",
                "OPENAI", "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1", "fake-secret");
        var ability = catalog.publishCapability(connection.id(), "GPT Image 2",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"quality\":\"medium\"}"));
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), ability.id());
        AdminPrincipal owner = identities.setup("openai-image-integration-secret",
                "openai-admin", "openai-password-123");
        Fixture edited = fixture(owner.userId(), "Reference edit", true);
        Task editTask = approve(owner.userId(), edited);
        assertThat(editTask.input().path("referenceImageVersionId").asText()).isNotBlank();
        assertThat(worker.submitOnce("openai-edit-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), edited.project().id(), editTask.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(EDITS).hasValue(1);
        assertThat(GENERATIONS).hasValue(0);
        assertThat(jdbc.sql("select status from call_log where task_id=:task")
                .param("task", editTask.id()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select model from call_log where task_id=:task")
                .param("task", editTask.id()).query(String.class).single()).isEqualTo("gpt-image-2");
        assertThat(artifacts.get(owner.userId(), edited.project().id(),
                UUID.fromString(completed.output().path("artifactId").asText()))
                .currentVersion().content().path("assetId").asText()).isNotBlank();

        Fixture generated = fixture(owner.userId(), "Lost generation", false);
        Task uncertain = approve(owner.userId(), generated);
        DROP_NEXT_GENERATION.set(true);
        assertThat(worker.submitOnce("openai-unknown-worker")).isEqualTo(1);
        assertThat(GENERATIONS).hasValue(1);
        assertThat(tasks.get(owner.userId(), generated.project().id(), uncertain.id()).status())
                .isEqualTo(Task.Status.SUBMITTING);
        jdbc.sql("update task set lease_until=now() - interval '1 second' where id=:id")
                .param("id", uncertain.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), generated.project().id(), uncertain.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(jdbc.sql("select status from call_log where task_id=:task")
                .param("task", uncertain.id()).query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("select count(*) from call_log where task_id=:task")
                .param("task", uncertain.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(worker.submitOnce("openai-second-worker")).isZero();
        assertThat(GENERATIONS).hasValue(1);

        Fixture foreign = fixture(owner.userId(), "Foreign reference", false);
        Task foreignTask = approve(owner.userId(), foreign);
        String otherProjectVersion = edited.shot().currentVersion().content()
                .path("selectedImageVersionId").asText();
        jdbc.sql("update task set input_json=input_json || "
                        + "jsonb_build_object('referenceImageVersionId', :versionId) "
                        + "where id=:id")
                .param("versionId", otherProjectVersion).param("id", foreignTask.id()).update();
        assertThat(worker.submitOnce("openai-foreign-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner.userId(), foreign.project().id(), foreignTask.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        assertThat(GENERATIONS).hasValue(1);
        assertThat(EDITS).hasValue(1);

        // 中转站只回结果 URL 时也必须下载并归档成 Asset，而不是判成 UNKNOWN。
        URL_RESULT.set(true);
        try {
            Fixture linked = fixture(owner.userId(), "Linked result", false);
            Task linkedTask = approve(owner.userId(), linked);
            assertThat(worker.submitOnce("openai-url-worker")).isEqualTo(1);
            Task linkedDone = tasks.get(owner.userId(), linked.project().id(), linkedTask.id());
            assertThat(linkedDone.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(GENERATIONS).hasValue(2);
            assertThat(DOWNLOADS).hasValue(1);
            assertThat(artifacts.get(owner.userId(), linked.project().id(),
                    UUID.fromString(linkedDone.output().path("artifactId").asText()))
                    .currentVersion().content().path("assetId").asText()).isNotBlank();
        } finally {
            URL_RESULT.set(false);
        }
    }

    private Fixture fixture(UUID ownerId, String name, boolean reference) {
        Project project = projects.create(ownerId, name, Project.AspectRatio.LANDSCAPE_16_9);
        ObjectNode scene = mapper.createObjectNode();
        scene.put("name", "Studio"); scene.put("location", "Shanghai");
        scene.put("timeOfDay", "Day"); scene.put("lighting", "Soft");
        scene.put("style", "Minimal"); scene.putArray("referenceVersionIds");
        UUID sceneVersion = artifacts.create(ownerId, project.id(), Artifact.Kind.SCENE,
                "Scene", scene).currentVersion().id();
        ObjectNode shot = mapper.createObjectNode();
        shot.put("order", 1); shot.put("durationSeconds", 4);
        shot.put("description", "A detailed studio shot"); shot.put("camera", "Close");
        shot.put("action", "Slow pan"); shot.putArray("characterVersionIds");
        shot.put("sceneVersionId", sceneVersion.toString());
        if (reference) {
            UUID assetId = ImageAssetFixture.archive(assets, ownerId, project.id());
            ObjectNode image = mapper.createObjectNode();
            image.put("assetId", assetId.toString());
            image.put("sourceTaskId", UUID.randomUUID().toString());
            image.put("prompt", "Reference");
            image.put("providerConfigVersion", 1);
            image.put("workflowVersion", "fixture");
            image.putObject("parameters");
            var artifact = artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE,
                    "Reference", image);
            shot.put("selectedImageVersionId", artifact.currentVersion().id().toString());
        }
        var target = artifacts.create(ownerId, project.id(), Artifact.Kind.SHOT, "Shot", shot);
        var agent = agents.create(ownerId, project.id(), "Creator", "Create",
                List.of(new AgentInstanceService.BindingInput(target.artifact().id(),
                        target.currentVersion().id())));
        AgentRun queued = runs.create(ownerId, project.id(), agent.id(), "Create image",
                "openai-" + UUID.randomUUID()).run();
        runs.transition(ownerId, project.id(), queued.id(), queued.version(),
                AgentRun.Status.RUNNING);
        return new Fixture(project, target, queued);
    }

    private Task approve(UUID ownerId, Fixture fixture) {
        ObjectNode proposal = mapper.createObjectNode();
        proposal.put("stage", "IMAGE");
        proposal.put("objective", "Create an approved keyframe");
        ObjectNode step = proposal.putArray("steps").addObject();
        step.put("stepKey", "frame-1"); step.put("outputSlotKey", "frame-output");
        step.put("shotArtifactId", fixture.shot().artifact().id().toString());
        step.put("shotVersionId", fixture.shot().currentVersion().id().toString());
        step.put("prompt", "A detailed cinematic studio scene");
        step.putArray("dependsOnStepKeys");
        var plan = plans.propose(new TrustedToolContext(ownerId, fixture.project().id(),
                fixture.run().id()), proposal);
        return plans.approve(ownerId, fixture.project().id(), plan.id(), plan.planHash(),
                List.of("frame-1")).tasks().getFirst();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/images/generations", exchange -> {
                GENERATIONS.incrementAndGet();
                if (DROP_NEXT_GENERATION.getAndSet(false)) {
                    exchange.close();
                    return;
                }
                respond(exchange);
            });
            server.createContext("/v1/images/edits", exchange -> {
                EDITS.incrementAndGet();
                respond(exchange);
            });
            // 结果托管端点与 API 不同路径，且不接收凭证。
            server.createContext("/cdn/result.png", exchange -> {
                DOWNLOADS.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                byte[] png = pngBytes();
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                try (var output = exchange.getResponseBody()) { output.write(png); }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer fake-secret");
        assertThat(body).isNotEmpty();
        String payload = URL_RESULT.get()
                ? "{\"data\":[{\"url\":\"http://127.0.0.1:"
                        + SERVER.getAddress().getPort() + "/cdn/result.png\"}]}"
                : "{\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(pngBytes()) + "\"}]}";
        byte[] response = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) { output.write(response); }
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    @TestConfiguration

    private record Fixture(Project project, ArtifactService.ArtifactView shot, AgentRun run) {}
}

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
import dev.agenvas.provider.infrastructure.GoogleNanoBananaClient;
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
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Real Task/Asset path against a loopback fake Google API; no paid call is made. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=google-image-integration-secret")
class GoogleNanoBananaPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final AtomicInteger EDITS = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_GENERATION = new AtomicBoolean();
    private static final AtomicReference<JsonNode> LAST_REQUEST = new AtomicReference<>();

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
        var connection = catalog.createConnection("google-it-connection", "Google fake",
                "GOOGLE", "http://127.0.0.1:" + SERVER.getAddress().getPort(), "fake-google-secret");
        var ability = catalog.publishCapability(connection.id(), "Nano Banana 2",
                "GOOGLE_NANO_BANANA_2", mapper.readTree("{}"));
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), ability.id());
        AdminPrincipal owner = identities.setup("google-image-integration-secret",
                "google-admin", "google-password-123");
        Fixture edited = fixture(owner.userId(), "Reference edit", true);
        Task editTask = approve(owner.userId(), edited);
        assertThat(editTask.input().path("referenceImageVersionId").asText()).isNotBlank();
        assertThat(worker.submitOnce("google-edit-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), edited.project().id(), editTask.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(EDITS).hasValue(1);
        assertThat(GENERATIONS).hasValue(0);
        assertThat(LAST_REQUEST.get().path("contents").path(0).path("parts").path(1)
                .path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(LAST_REQUEST.get().path("generationConfig").path("responseFormat")
                .path("image").path("aspectRatio").asText()).isEqualTo("16:9");
        assertThat(artifacts.get(owner.userId(), edited.project().id(),
                UUID.fromString(completed.output().path("artifactId").asText()))
                .currentVersion().content().path("assetId").asText()).isNotBlank();

        Fixture generated = fixture(owner.userId(), "Lost generation", false);
        Task uncertain = approve(owner.userId(), generated);
        DROP_NEXT_GENERATION.set(true);
        assertThat(worker.submitOnce("google-unknown-worker")).isEqualTo(1);
        assertThat(GENERATIONS).hasValue(1);
        assertThat(tasks.get(owner.userId(), generated.project().id(), uncertain.id()).status())
                .isEqualTo(Task.Status.SUBMITTING);
        jdbc.sql("update task set lease_until=now() - interval '1 second' where id=:id")
                .param("id", uncertain.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(1)).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), generated.project().id(), uncertain.id()).status())
                .isEqualTo(Task.Status.UNKNOWN);
        assertThat(worker.submitOnce("google-second-worker")).isZero();
        assertThat(GENERATIONS).hasValue(1);

        Fixture foreign = fixture(owner.userId(), "Foreign reference", false);
        Task foreignTask = approve(owner.userId(), foreign);
        String otherProjectVersion = edited.shot().currentVersion().content()
                .path("selectedImageVersionId").asText();
        jdbc.sql("update task set input_json=input_json || "
                        + "jsonb_build_object('referenceImageVersionId', :versionId) "
                        + "where id=:id")
                .param("versionId", otherProjectVersion).param("id", foreignTask.id()).update();
        assertThat(worker.submitOnce("google-foreign-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner.userId(), foreign.project().id(), foreignTask.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        assertThat(GENERATIONS).hasValue(1);
        assertThat(EDITS).hasValue(1);
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
                "google-" + UUID.randomUUID()).run();
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
            server.createContext("/v1/models/gemini-3.1-flash-image:generateContent", exchange -> {
                JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                LAST_REQUEST.set(request);
                boolean edit = request.path("contents").path(0).path("parts").size() > 1;
                if (edit) EDITS.incrementAndGet(); else GENERATIONS.incrementAndGet();
                if (!edit && DROP_NEXT_GENERATION.getAndSet(false)) {
                    exchange.close();
                    return;
                }
                respond(exchange);
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("x-goog-api-key"))
                .isEqualTo("fake-google-secret");
        assertThat(LAST_REQUEST.get().path("generationConfig").path("responseFormat")
                .path("image").path("imageSize").asText()).isEqualTo("1K");
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] response = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"},"
                + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(png.toByteArray())
                + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) { output.write(response); }
    }

    @TestConfiguration

    private record Fixture(Project project, ArtifactService.ArtifactView shot, AgentRun run) {}
}

package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(classes = AgenvasApplication.class)
class GoogleNanoBananaPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final AtomicInteger EDITS = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_GENERATION = new AtomicBoolean();
    private static final AtomicReference<JsonNode> LAST_REQUEST = new AtomicReference<>();
    private static AdminPrincipal owner;
    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenvas.storage.root", storage::toString);
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

    @Autowired
    private IdentityService identities;
    @Autowired
    private ProjectService projects;
    @Autowired
    private ArtifactService artifacts;
    @Autowired
    private AssetService assets;
    @Autowired
    private MediaDraftService drafts;
    @Autowired
    private CanvasService canvas;
    @Autowired
    private DirectMediaTaskService directMedia;
    @Autowired
    private MediaCapabilityService catalog;
    @Autowired
    private MediaFunctionService functions;
    @Autowired
    private MediaExecutionWorker worker;
    @Autowired
    private TaskService tasks;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        if (owner == null)
            owner = identities.setup("google-admin", "google-password-123");
        GENERATIONS.set(0);
        EDITS.set(0);
        DROP_NEXT_GENERATION.set(false);
        LAST_REQUEST.set(null);
    }

    @Test
    void threeViewSendsTheBoundImageSizeAndPinsItAcrossCapabilityUpdates() {
        var connection = catalog.createConnection("google-views", "Google views",
                "GOOGLE", "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1beta", "fake-google-secret");
        var capability = catalog.publishCapability(connection.id(), "Views", "GOOGLE_NANO_BANANA_2",
                mapper.readTree("{\"defaultParameters\":{\"resolution\":\"4K\"}}"));
        var previous = functions.list().stream().filter(row -> row.operation() == MediaFunction.IMAGE_THREE_VIEW)
                .findFirst().orElseThrow();
        functions.update(MediaFunction.IMAGE_THREE_VIEW, previous.version(), capability.id());
        long functionVersion = previous.version() + 1;
        var project = projects.create(owner.userId(), "Views", Project.AspectRatio.LANDSCAPE_16_9);
        var asset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Source",
                mapper.createObjectNode().put("assetId", asset.toString()).put("sourceType", "UPLOAD"));
        var artifact = image.artifact().id();
        var source = image.resourceDefaultVersion().id();
        var item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact);
        var parameters = mapper.createObjectNode().put("aspectRatio", "16:9").put("threeViewType", "FACE");
        assertThatThrownBy(() -> directMedia.runImageOperation(owner.userId(), project.id(), artifact, item, source, 0,
                ImageOperation.THREE_VIEW, "", functionVersion, 1, List.of(), null,
                parameters.deepCopy().put("quality", "high"), "google-unsupported-quality"))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("VALIDATION_ERROR");
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(1);
        var accepted = directMedia.runImageOperation(owner.userId(), project.id(), artifact, item, source, 0,
                ImageOperation.THREE_VIEW, "", functionVersion, 1, List.of(), null, parameters, "google-views-default");
        assertThat(worker.submitOnce("google-views-worker")).isOne();
        assertThat(tasks.get(owner.userId(), project.id(), accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(LAST_REQUEST.get().at("/generationConfig/imageConfig/imageSize").asText()).isEqualTo("4K");

        var updated = catalog.updateCapability(connection.id(), capability.id(), capability.version(), "Views", true,
                "GOOGLE_NANO_BANANA_2", mapper.createObjectNode());
        var replay = directMedia.runImageOperation(owner.userId(), project.id(), artifact, item, source, 0,
                ImageOperation.THREE_VIEW, "", functionVersion, 1, List.of(), null, parameters, "google-views-default");
        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(replay.input().at("/mediaInput/parameters/resolution").asText()).isEqualTo("4K");
        var next = directMedia.runImageOperation(owner.userId(), project.id(), artifact, item, source, 0,
                ImageOperation.THREE_VIEW, "", functionVersion, updated.currentVersion(), List.of(), null,
                parameters.deepCopy().put("resolution", "2K"), "google-views-override");
        assertThat(worker.submitOnce("google-views-worker")).isOne();
        assertThat(tasks.get(owner.userId(), project.id(), next.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(LAST_REQUEST.get().at("/generationConfig/imageConfig/imageSize").asText()).isEqualTo("2K");
        assertThat(EDITS).hasValue(2);
    }

    @Test
    void configuredBetaReferenceUsesEditsAndLostGenerationResponseRemainsUnknown() {
        var connection = catalog.createConnection("google-it-connection", "Google fake",
                "GOOGLE", "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1beta",
                "fake-google-secret");
        var ability = catalog.publishCapability(connection.id(), "Nano Banana 2",
                "GOOGLE_NANO_BANANA_2", mapper.readTree("{}"));
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), ability.id());
        Fixture edited = fixture(owner.userId(), "Reference edit", 2);
        Task editTask = approve(owner.userId(), edited);
        assertThat(editTask.input().path("mediaInput").path("images").size()).isEqualTo(2);
        assertThat(editTask.input().path("mediaInput").path("images").path(0)
                .path("order").asInt()).isZero();
        assertThat(editTask.input().path("mediaInput").path("images").path(1)
                .path("order").asInt()).isEqualTo(1);
        assertThat(worker.submitOnce("google-edit-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), edited.project().id(), editTask.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(EDITS).hasValue(1);
        assertThat(GENERATIONS).hasValue(0);
        assertThat(LAST_REQUEST.get().path("contents").path(0).path("parts").path(1)
                .path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(LAST_REQUEST.get().path("contents").path(0).path("parts").size())
                .isEqualTo(3);
        assertThat(LAST_REQUEST.get().path("contents").path(0).path("parts").path(2)
                .path("inlineData").path("mimeType").asText()).isEqualTo("image/png");
        assertThat(LAST_REQUEST.get().path("generationConfig").path("imageConfig")
                .path("aspectRatio").asText()).isEqualTo("16:9");
        assertThat(LAST_REQUEST.get().at("/generationConfig/imageConfig/imageSize").asText()).isEqualTo("1K");
        UUID completedVersionId = UUID.fromString(
                completed.output().path("artifactVersionId").asText());
        assertThat(artifacts.requireVersion(owner.userId(), edited.project().id(),
                edited.card().artifact().id(), completedVersionId)
                .content().path("assetId").asText()).isNotBlank();
        assertThat(artifacts.get(owner.userId(), edited.project().id(),
                edited.card().artifact().id()).resourceDefaultVersion()).isNull();
        assertThat(canvas.list(owner.userId(), edited.project().id()).stream()
                .filter(entry -> entry.item().id().toString().equals(
                        editTask.input().path("canvasItemId").asText()))
                .findFirst().orElseThrow().item().selectedVersionId())
                .isEqualTo(completedVersionId);

        Fixture generated = fixture(owner.userId(), "Lost generation", 0);
        Task uncertain = approve(owner.userId(), generated);
        DROP_NEXT_GENERATION.set(true);
        assertThat(worker.submitOnce("google-unknown-worker")).isEqualTo(1);
        assertThat(GENERATIONS).hasValue(1);
        // 连接被切断当场就按原因码判定，不再等租约到期。
        Task lost = tasks.get(owner.userId(), generated.project().id(), uncertain.id());
        assertThat(lost.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(lost.errorCode()).isEqualTo(ProviderFailureCodes.RESPONSE_LOST);
        assertThat(tasks.recoverExpiredSubmissions(1)).isZero();
        assertThat(worker.submitOnce("google-second-worker")).isZero();
        assertThat(GENERATIONS).hasValue(1);

        Fixture foreign = fixture(owner.userId(), "Foreign reference", 0);
        Task foreignTask = approve(owner.userId(), foreign);
        // 跨项目参考图：另一个项目里那张参考图的精确版本。
        jdbc.sql("update task set input_json=jsonb_set(input_json, '{mediaInput,images}', "
                + "jsonb_build_array(jsonb_build_object('artifactId', :artifactId, "
                + "'versionId', :versionId, 'role', 'REFERENCE', 'order', 0))) "
                + "where id=:id")
                .param("artifactId", edited.referenceImages().get(0).artifact().id().toString())
                .param("versionId", edited.referenceImages().get(0).resourceDefaultVersion()
                        .id().toString())
                .param("id", foreignTask.id()).update();
        assertThat(worker.submitOnce("google-foreign-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner.userId(), foreign.project().id(), foreignTask.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        assertThat(GENERATIONS).hasValue(1);
        assertThat(EDITS).hasValue(1);
    }

    /** 一张空图片卡片、其草稿和直连受理结果；参考图按传入顺序固定。 */
    private Fixture fixture(UUID ownerId, String name, int referenceCount) {
        Project project = projects.create(ownerId, name, Project.AspectRatio.LANDSCAPE_16_9);
        List<ArtifactService.ArtifactView> referenceImages = new ArrayList<>();
        for (int index = 0; index < referenceCount; index++) {
            UUID assetId = ImageAssetFixture.archive(assets, ownerId, project.id());
            ObjectNode image = mapper.createObjectNode();
            image.put("assetId", assetId.toString());
            image.put("sourceTaskId", UUID.randomUUID().toString());
            image.put("prompt", "Reference");
            image.put("workflowVersion", "fixture");
            image.putObject("parameters");
            referenceImages.add(artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE,
                    "Reference " + (index + 1), image));
        }
        var card = artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE, "Concept", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, ownerId, project.id(), card.artifact().id());
        List<MediaDraftService.SaveMediaInput> inputs = referenceImages.stream()
                .map(reference -> new MediaDraftService.SaveMediaInput(
                        reference.resourceDefaultVersion().id(),
                        dev.agenvas.artifact.domain.MediaDraft.InputRole.REFERENCE,
                        "#7C3AED"))
                .toList();
        long draftVersion = drafts.save(ownerId, project.id(), canvasItemId, 0,
                "A detailed cinematic studio scene", mapper.createObjectNode(), null, null,
                null, inputs, List.of(), null).version();
        Task task = directMedia.run(ownerId, project.id(), card.artifact().id(), canvasItemId,
                draftVersion,
                "google-" + UUID.randomUUID());
        return new Fixture(project, card, List.copyOf(referenceImages), task);
    }

    private Task approve(UUID ownerId, Fixture fixture) {
        return fixture.task();
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1beta/models/gemini-3.1-flash-image:generateContent", exchange -> {
                JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                LAST_REQUEST.set(request);
                boolean edit = request.path("contents").path(0).path("parts").size() > 1;
                if (edit)
                    EDITS.incrementAndGet();
                else
                    GENERATIONS.incrementAndGet();
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
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] response = ("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"},"
                + "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                + Base64.getEncoder().encodeToString(png.toByteArray())
                + "\"}}]}}]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private record Fixture(Project project, ArtifactService.ArtifactView card,
            List<ArtifactService.ArtifactView> referenceImages, Task task) {
    }
}

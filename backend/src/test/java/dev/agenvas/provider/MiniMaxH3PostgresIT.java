package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.MiniMaxH3Client;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
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

/** Pinned H3 submit/poll state machine against PostgreSQL and a local fake MiniMax API. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, MiniMaxH3PostgresIT.FakeClient.class})
class MiniMaxH3PostgresIT {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path assetsRoot;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String TASK_ID = "synthetic-minimax-h3-task";
    /** 假服务端与 MiniMax 适配器都要求 4 秒，直连入口写入的 durationSeconds 必须与之一致。 */
    private static final int CLIP_SECONDS = 4;
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger CREATES = new AtomicInteger();
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_CREATE = new AtomicBoolean();
    private static final AtomicReference<tools.jackson.databind.JsonNode> LAST_BODY = new AtomicReference<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenvas.storage.root", () -> assetsRoot.toString());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private dev.agenvas.asset.infrastructure.MediaToolRunner mediaTools;

    @Test
    void acceptedTaskPollsOnlyOriginalIdAndLostCreateResponseStaysUnknown() {
        var connection = catalog.createConnection("minimax-it-connection", "MiniMax fake", "MINIMAX",
                null, "fake-minimax-key");
        var capability = catalog.publishCapability(connection.id(), "H3 first frame",
                "MINIMAX_H3");
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), capability.id());
        AdminPrincipal owner = identities.setup("minimax-admin", "minimax-password-123");

        Fixture accepted = fixture(owner.userId(), "Accepted H3");
        Task acceptedTask = approve(owner.userId(), accepted);
        assertThat(acceptedTask.input().path("durationSeconds").asInt()).isEqualTo(CLIP_SECONDS);
        assertThat(acceptedTask.input().at("/mediaInput/providerParameters/ratio").asText()).isEqualTo("adaptive");
        assertThat(acceptedTask.input().at("/mediaInput/parameters/videoResolution").asText()).isEqualTo("768p");
        assertThat(worker.submitOnce("minimax-submit-worker")).isEqualTo(1);
        Task waiting = tasks.get(owner.userId(), accepted.project().id(), acceptedTask.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo(TASK_ID);
        assertThat(CREATES).hasValue(1);
        jdbc.sql("update task set next_action_at=now() - interval '1 second' where id=:id")
                .param("id", acceptedTask.id()).update();
        assertThat(worker.pollOnce("minimax-poll-worker")).isEqualTo(1);
        assertThat(QUERIES).hasValue(1);
        assertThat(CREATES).hasValue(1);

        Fixture uncertain = fixture(owner.userId(), "Uncertain H3");
        Task uncertainTask = approve(owner.userId(), uncertain);
        DROP_NEXT_CREATE.set(true);
        assertThat(worker.submitOnce("minimax-unknown-worker")).isEqualTo(1);
        assertThat(CREATES).hasValue(2);
        // A lost create response is persisted as UNKNOWN immediately; recovery never resubmits.
        Task uncertainResult = tasks.get(owner.userId(), uncertain.project().id(),
                uncertainTask.id());
        assertThat(uncertainResult.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(uncertainResult.errorCode()).isEqualTo("MINIMAX_CREATE_UNCERTAIN");
        assertThat(tasks.recoverExpiredSubmissions(1)).isZero();
        assertThat(worker.submitOnce("minimax-after-unknown-worker")).isZero();
        assertThat(CREATES).hasValue(2);

        // AUTO is resolved at acceptance, so a later project edit cannot change the submitted ratio.
        var text = fixture(owner.userId(), "Frozen text ratio");
        var textDraft = drafts.save(owner.userId(), text.project().id(), text.canvasItemId(), text.draftVersion(),
                "A moving landscape", mapper.createObjectNode(), CLIP_SECONDS, capability.id(),
                MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        var textTask = directMedia.run(owner.userId(), text.project().id(), text.card().artifact().id(),
                text.canvasItemId(), textDraft.version(), "frozen-text-ratio");
        assertThat(textTask.input().at("/mediaInput/providerParameters/ratio").asText()).isEqualTo("16:9");
        var currentProject = projects.get(owner.userId(), text.project().id());
        projects.update(owner.userId(), text.project().id(), currentProject.version(), null, Project.AspectRatio.PORTRAIT_9_16);
        assertThat(worker.submitOnce("minimax-text-worker")).isEqualTo(1);
        assertThat(LAST_BODY.get().path("ratio").asText()).isEqualTo("16:9");
        assertThat(LAST_BODY.get().path("content").size()).isEqualTo(1);

        // H3 accepts audio-only references; Seedance's visual requirement must not leak into this path.
        var sound = fixture(owner.userId(), "Audio-only reference");
        UUID audioAsset = archiveH3Audio(owner.userId(), sound.project().id());
        var audio = artifacts.create(owner.userId(), sound.project().id(), Artifact.Kind.AUDIO, "Synthetic sound",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", audioAsset.toString()));
        var soundDraft = drafts.save(owner.userId(), sound.project().id(), sound.canvasItemId(), sound.draftVersion(),
                "Follow the sound", mapper.createObjectNode(), CLIP_SECONDS, capability.id(),
                MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(audio.resourceDefaultVersion().id(), MediaDraft.InputRole.AUDIO_REFERENCE, "#7C3AED")),
                List.of(), null);
        directMedia.run(owner.userId(), sound.project().id(), sound.card().artifact().id(), sound.canvasItemId(),
                soundDraft.version(), "h3-audio-only");
        assertThat(worker.submitOnce("minimax-audio-worker")).isEqualTo(1);
        assertThat(LAST_BODY.get().path("ratio").asText()).isEqualTo("adaptive");
        assertThat(LAST_BODY.get().at("/content/1/role").asText()).isEqualTo("reference_audio");
        assertThat(LAST_BODY.get().at("/content/1/audio_url/url").asText()).startsWith("data:audio/wav;base64,");
        assertThat(CREATES).hasValue(4);
    }

    /** 直连入口只需要一张真实归档图片当关键帧，不再经过规划、审批和关键帧选择。 */
    private Fixture fixture(UUID ownerId, String name) {
        Project project = projects.create(ownerId, name, Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = archiveH3Image(ownerId, project.id());
        ObjectNode image = mapper.createObjectNode();
        image.put("assetId", assetId.toString());
        image.put("sourceTaskId", UUID.randomUUID().toString());
        image.put("prompt", "Studio keyframe");
        image.put("workflowVersion", "fixture");
        image.putObject("parameters");
        var keyframe = artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE,
                "Keyframe", image);
        var card = artifacts.create(ownerId, project.id(), Artifact.Kind.VIDEO, "Clip", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, ownerId, project.id(), card.artifact().id());
        long draftVersion = dev.agenvas.support.CanvasMediaFixture.save(drafts,
                ownerId, project.id(), canvasItemId, 0,
                "A detailed coffee pour", keyframe.resourceDefaultVersion().id(), CLIP_SECONDS, null)
                .version();
        return new Fixture(project, card, keyframe, canvasItemId, draftVersion);
    }

    private UUID archiveH3Image(UUID ownerId, UUID projectId) {
        try {
            var image = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", bytes);
            return assets.archiveImage(ownerId, projectId, new java.io.ByteArrayInputStream(bytes.toByteArray())).id();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private UUID archiveH3Audio(UUID ownerId, UUID projectId) {
        try {
            var path = java.nio.file.Files.createTempFile("agenvas-h3-sound-", ".wav");
            try {
                mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                        "anullsrc=r=8000:cl=mono", "-t", "2", "-c:a", "pcm_s16le", "-y", path.toString()));
                try (var input = java.nio.file.Files.newInputStream(path)) { return assets.archiveAudio(ownerId, projectId, input).id(); }
            } finally { java.nio.file.Files.deleteIfExists(path); }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private Task approve(UUID ownerId, Fixture fixture) {
        return directMedia.run(ownerId, fixture.project().id(), fixture.card().artifact().id(),
                fixture.canvasItemId(), fixture.draftVersion(), "minimax-" + UUID.randomUUID());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            com.sun.net.httpserver.HttpHandler handler = exchange -> {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer fake-minimax-key");
                if (exchange.getRequestMethod().equals("POST")) {
                    var body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                    assertThat(body.path("model").asText())
                            .isEqualTo("MiniMax-H3");
                    assertThat(body.path("duration").asInt()).isEqualTo(CLIP_SECONDS);
                    assertThat(body.has("generate_audio")).isFalse();
                    assertThat(body.path("resolution").asText()).isEqualTo("768P");
                    LAST_BODY.set(body);
                    CREATES.incrementAndGet();
                    if (DROP_NEXT_CREATE.getAndSet(false)) { exchange.close(); return; }
                    respond(exchange, "{\"task_id\":\"" + TASK_ID + "\"}");
                } else {
                    assertThat(exchange.getRequestURI().getPath())
                            .isEqualTo("/v2/query/video_generation/" + TASK_ID);
                    QUERIES.incrementAndGet();
                    respond(exchange, "{\"task\":{\"id\":\"" + TASK_ID + "\",\"model\":"
                            + "\"MiniMax-H3\",\"status\":\"running\"}}");
                }
            };
            server.createContext("/v2/video_generation", handler);
            server.createContext("/v2/query/video_generation", handler);
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @TestConfiguration
    static class FakeClient {
        @Bean @Primary
        MiniMaxH3Client fakeMiniMaxH3Client(ObjectMapper mapper) {
            return new MiniMaxH3Client(mapper,
                    URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort()));
        }
    }

    /** 直连视频任务的固定输入：视频卡片、作为关键帧的图片产物和已保存的草稿版本。 */
    private record Fixture(Project project, ArtifactService.ArtifactView card,
            ArtifactService.ArtifactView keyframe, UUID canvasItemId, long draftVersion) {}
}

package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.infrastructure.MiniMaxH3Client;
import dev.agenvas.provider.infrastructure.MiniMaxMediaDownloadPolicy;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** H3 result download, native audio retention and archive with a fake MiniMax API. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, MiniMaxH3ArchivePostgresIT.FakeClient.class})
class MiniMaxH3ArchivePostgresIT {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path assetsRoot;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String TASK_ID = "cgt-test-seedance-archive-123";
    /** 假服务端与 MiniMax 适配器都要求 4 秒，直连入口写入的 durationSeconds 必须与之一致。 */
    private static final int CLIP_SECONDS = 4;
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger CREATES = new AtomicInteger();
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static final AtomicBoolean MALICIOUS_RESULT = new AtomicBoolean();
    private static final AtomicBoolean EXPIRED_UNREFRESHABLE = new AtomicBoolean();
    private static final AtomicBoolean FAIL_DOWNLOAD_ONCE = new AtomicBoolean();
    private static volatile byte[] VIDEO_BYTES;

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
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void expiredUrlRefreshesOnlyOriginalTaskThenArchivesVideoWithNativeAudio() throws Exception {
        VIDEO_BYTES = videoWithAudio();
        var connection = catalog.createConnection("minimax-it-connection", "MiniMax fake", "MINIMAX",
                null, "fake-minimax-key");
        var capability = catalog.publishCapability(connection.id(), "H3 first frame",
                "MINIMAX_H3");
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), capability.id());
        AdminPrincipal owner = identities.setup("minimax-admin", "minimax-password-123");

        Fixture accepted = fixture(owner.userId(), "Archived H3");
        Task acceptedTask = approve(owner.userId(), accepted);
        assertThat(acceptedTask.input().path("durationSeconds").asInt()).isEqualTo(CLIP_SECONDS);
        assertThat(worker.submitOnce("minimax-submit-worker")).isEqualTo(1);
        Task waiting = tasks.get(owner.userId(), accepted.project().id(), acceptedTask.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo(TASK_ID);
        assertThat(CREATES).hasValue(1);
        jdbc.sql("update task set next_action_at=now() - interval '1 second' where id=:id")
                .param("id", acceptedTask.id()).update();
        assertThat(worker.pollOnce("minimax-poll-worker")).isEqualTo(1);
        assertThat(QUERIES).hasValue(2);
        assertThat(CREATES).hasValue(1);
        Task done = tasks.get(owner.userId(), accepted.project().id(), acceptedTask.id());
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.providerRequestId()).isEqualTo(TASK_ID);
        UUID completedVersionId = UUID.fromString(done.output()
                .path("artifactVersionId").asText());
        UUID assetId = UUID.fromString(artifacts.requireVersion(owner.userId(),
                accepted.project().id(), accepted.card().artifact().id(), completedVersionId)
                .content().path("assetId").asText());
        assertThat(artifacts.get(owner.userId(), accepted.project().id(),
                accepted.card().artifact().id()).resourceDefaultVersion()).isNull();
        assertThat(canvas.list(owner.userId(), accepted.project().id()).stream()
                .filter(entry -> entry.item().id().equals(accepted.canvasItemId()))
                .findFirst().orElseThrow().item().selectedVersionId())
                .isEqualTo(completedVersionId);
        Path archived = assets.get(owner.userId(), accepted.project().id(), assetId).path();
        var probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error",
                "-show_entries", "stream=codec_type:format=format_name,duration",
                "-of", "json", archived.toString())));
        assertThat(probe.path("streams").toString()).contains("video", "audio");
        assertThat(probe.path("format").path("duration").asDouble()).isBetween(3.0, 5.5);
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:id")
                .param("id", accepted.project().id()).query(Integer.class).single())
                .isGreaterThan(0);
        assertThat(worker.submitOnce("minimax-no-resubmit-worker")).isZero();
        assertThat(CREATES).hasValue(1);

        MALICIOUS_RESULT.set(true);
        Fixture rejected = fixture(owner.userId(), "Malicious result URL");
        Task rejectedTask = approve(owner.userId(), rejected);
        assertThat(worker.submitOnce("minimax-malicious-submit")).isEqualTo(1);
        due(rejectedTask.id());
        assertThat(worker.pollOnce("minimax-malicious-poll")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), rejected.project().id(), rejectedTask.id())
                .status()).isEqualTo(Task.Status.BLOCKED);
        MALICIOUS_RESULT.set(false);

        EXPIRED_UNREFRESHABLE.set(true);
        Fixture expired = fixture(owner.userId(), "Expired result URL");
        Task expiredTask = approve(owner.userId(), expired);
        assertThat(worker.submitOnce("minimax-expired-submit")).isEqualTo(1);
        due(expiredTask.id());
        assertThat(worker.pollOnce("minimax-expired-poll")).isEqualTo(1);
        Task expiredResult = tasks.get(owner.userId(), expired.project().id(), expiredTask.id());
        assertThat(expiredResult.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(expiredResult.errorCode()).isEqualTo("MINIMAX_RESULT_EXPIRED");
        EXPIRED_UNREFRESHABLE.set(false);

        FAIL_DOWNLOAD_ONCE.set(true);
        Fixture retry = fixture(owner.userId(), "Retry original result download");
        Task retryTask = approve(owner.userId(), retry);
        assertThat(worker.submitOnce("minimax-retry-submit")).isEqualTo(1);
        due(retryTask.id());
        assertThat(worker.pollOnce("minimax-retry-poll-1")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), retry.project().id(), retryTask.id())
                .status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        due(retryTask.id());
        assertThat(worker.pollOnce("minimax-retry-poll-2")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), retry.project().id(), retryTask.id())
                .status()).isEqualTo(Task.Status.SUCCEEDED);
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

    private Task approve(UUID ownerId, Fixture fixture) {
        return directMedia.run(ownerId, fixture.project().id(), fixture.card().artifact().id(),
                fixture.canvasItemId(), fixture.draftVersion(), "minimax-" + UUID.randomUUID());
    }

    private byte[] videoWithAudio() throws Exception {
        Path output = Files.createTempFile("minimax-video-with-audio-", ".mp4");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=16",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-shortest", "-y", output.toString()));
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private void due(UUID taskId) {
        jdbc.sql("update task set next_action_at=now() - interval '1 second' where id=:id")
                .param("id", taskId).update();
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
                    assertThat(body.path("ratio").asText()).isEqualTo("adaptive");
                    assertThat(body.path("content").get(1).path("role").asText())
                            .isEqualTo("first_frame");
                    CREATES.incrementAndGet();
                    respond(exchange, "{\"task_id\":\"" + TASK_ID + "\"}");
                } else {
                    assertThat(exchange.getRequestURI().getPath())
                            .isEqualTo("/v2/query/video_generation/" + TASK_ID);
                    int query = QUERIES.incrementAndGet();
                    String videoUrl = MALICIOUS_RESULT.get()
                            ? "http://169.254.169.254/latest/meta-data"
                            : "http://127.0.0.1:" + server.getAddress().getPort()
                                    + (query == 1 || EXPIRED_UNREFRESHABLE.get()
                                            ? "/expired.mp4" : "/result.mp4");
                    respond(exchange, "{\"task\":{\"id\":\"" + TASK_ID + "\",\"model\":"
                            + "\"MiniMax-H3\",\"status\":\"succeeded\","
                            + "\"content\":{\"url\":\"" + videoUrl + "\"}}}");
                }
            };
            server.createContext("/v2/video_generation", handler);
            server.createContext("/v2/query/video_generation", handler);
            server.createContext("/expired.mp4", exchange -> {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
            });
            server.createContext("/result.mp4", exchange -> {
                if (FAIL_DOWNLOAD_ONCE.getAndSet(false)) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.sendResponseHeaders(200, VIDEO_BYTES.length);
                try (var output = exchange.getResponseBody()) { output.write(VIDEO_BYTES); }
            });
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

        @Bean @Primary
        MiniMaxMediaDownloadPolicy fakeMiniMaxDownloadPolicy() {
            return MiniMaxMediaDownloadPolicy.forLoopbackTest(
                    URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort()));
        }
    }

    /** 直连视频任务的固定输入：视频卡片、作为关键帧的图片产物和已保存的草稿版本。 */
    private record Fixture(Project project, ArtifactService.ArtifactView card,
            ArtifactService.ArtifactView keyframe, UUID canvasItemId, long draftVersion) {}
}

package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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

/** A disabled new-video adapter must still recover accepted original video requests. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=disabled-video-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=configured",
        "agenvas.provider.media.scheduler-enabled=false"})
class ComfyUiVideoDisabledPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final AtomicInteger HISTORY_GETS = new AtomicInteger();
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();
    private static final AtomicInteger DOWNLOADS = new AtomicInteger();
    private static final AtomicReference<UUID> ORIGINAL_ID = new AtomicReference<>();
    private static final AtomicReference<byte[]> VIDEO_BYTES = new AtomicReference<>();
    private static final Path STORAGE_ROOT = storageRoot();
    private static final HttpServer SERVER = startServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private DirectMediaTaskService directMedia;

    @Test
    void disabledSubmissionStillArchivesOnlyTheSavedVideoId() throws Exception {
        var owner = identities.setup("disabled-video-integration-secret",
                "disabled-video-admin", "disabled-video-password-123");
        Project project = projects.create(owner.userId(), "Historical video",
                Project.AspectRatio.LANDSCAPE_16_9);
        UUID imageAssetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var imageContent = mapper.createObjectNode();
        imageContent.put("sourceType", "UPLOAD");
        imageContent.put("assetId", imageAssetId.toString());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Pinned source image", imageContent);
        UUID connection = catalog.createConnection("Disabled fake ComfyUI",
                "http://127.0.0.1:" + SERVER.getAddress().getPort()).id();
        UUID capability = catalog.publishCapability(connection, "Fixed video", "COMFY_VIDEO_V1",
                mapper.readTree("{\"diffusionModel\":\"test-wan.safetensors\","
                        + "\"textEncoder\":\"test-text.safetensors\",\"vae\":\"test-vae.safetensors\","
                        + "\"clipVision\":\"test-vision.safetensors\"}")).id();
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), capability);
        var videoCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO,
                "Accepted video card", null);
        UUID itemId = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(),
                project.id(), videoCard.artifact().id());
        var draft = dev.agenvas.support.CanvasMediaFixture.save(drafts, owner.userId(),
                project.id(), itemId, 0, "Animate the archived image",
                image.resourceDefaultVersion().id(), 1, null);
        Task task = directMedia.run(owner.userId(), project.id(), videoCard.artifact().id(),
                itemId, draft.version(), "video-original");
        Task lease = tasks.claimBoundMedia("original-submitter", 1).getFirst();
        UUID requestId = tasks.beginSubmission(lease, "original-submitter",
                tasks.mediaBinding(lease).orElseThrow());
        ORIGINAL_ID.set(requestId);
        VIDEO_BYTES.set(realMp4());
        tasks.waitForProvider(lease, "original-submitter", requestId.toString(),
                Instant.now().plusSeconds(1));
        var publishedConnection = catalog.getConnection(connection);
        catalog.setConnectionEnabled(connection, publishedConnection.version(), false);
        assertThat(worker.submitOnce("disabled-connection-submitter")).isZero();
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", task.id()).update();
        assertThat(worker.pollOnce("historical-video-poller")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), task.id()).status())
                .isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(HISTORY_GETS).hasValue(1);
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", task.id()).update();
        assertThat(worker.pollOnce("historical-video-poller")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), task.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.providerRequestId()).isEqualTo(requestId.toString());
        var version = artifacts.requireVersion(owner.userId(), project.id(), videoCard.artifact().id(),
                UUID.fromString(completed.output().path("artifactVersionId").asText()));
        assertThat(version.content().has("providerConfigVersion")).isFalse();
        assertThat(version.content().path("workflowVersion").asText())
                .isEqualTo(task.input().path("workflowVersion").asText());
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        assertThat(assetId).isEqualTo(AssetService.taskVideoAssetId(task.id()));
        assertThat(assets.get(owner.userId(), project.id(), assetId).asset().mediaKind())
                .isEqualTo(Asset.MediaKind.VIDEO);
        assertThat(HISTORY_GETS).hasValue(2);
        assertThat(DOWNLOADS).hasValue(1);
        assertThat(SUBMISSIONS).hasValue(0);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", task.id()).query(Integer.class).single()).isEqualTo(1);
    }

    /** The fake Provider returns a decodable MP4; this is not GPU/model generation. */
    private byte[] realMp4() throws Exception {
        Path image = Files.createTempFile(STORAGE_ROOT, "disabled-video-source-", ".png");
        Path mp4 = Files.createTempFile(STORAGE_ROOT, "disabled-video-result-", ".mp4");
        try {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png",
                    image.toFile());
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-loop", "1", "-framerate", "16", "-i", image.toString(),
                    "-t", "1", "-vf", "scale=320:180,format=yuv420p", "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                    "-y", mp4.toString()));
            return Files.readAllBytes(mp4);
        } finally {
            Files.deleteIfExists(image);
            Files.deleteIfExists(mp4);
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/history", exchange -> {
                int query = HISTORY_GETS.incrementAndGet();
                String response = query == 1 ? "{}" : "{\"" + ORIGINAL_ID.get()
                        + "\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},"
                        + "\"outputs\":{\"14\":{\"images\":[{\"filename\":\"result.mp4\","
                        + "\"type\":\"output\",\"subfolder\":\"\"}],\"animated\":[true]}}}}";
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.createContext("/prompt", exchange -> {
                SUBMISSIONS.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            server.createContext("/view", exchange -> {
                DOWNLOADS.incrementAndGet();
                byte[] body = VIDEO_BYTES.get();
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Path storageRoot() {
        try {
            return Files.createTempDirectory("agenvas-disabled-video-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

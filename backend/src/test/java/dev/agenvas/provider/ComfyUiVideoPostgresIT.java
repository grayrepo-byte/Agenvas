package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.ComfyUiImageWorker;
import dev.agenvas.provider.application.ComfyUiVideoWorker;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.ComfyUiVideoPoller;
import dev.agenvas.provider.infrastructure.ComfyUiClient;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoProperties;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Fake ComfyUI plus real PostgreSQL proves the approved I2V protocol, not GPU output. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=comfy-video-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mode=comfyui",
        "agenvas.provider.comfyui.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false",
        "agenvas.provider.comfyui.image.checkpoint=test-image.safetensors",
        "agenvas.provider.comfyui.video.enabled=true",
        "agenvas.provider.comfyui.video.scheduler-enabled=false",
        "agenvas.provider.comfyui.video.diffusion-model=test-wan.safetensors",
        "agenvas.provider.comfyui.video.text-encoder=test-text.safetensors",
        "agenvas.provider.comfyui.video.vae=test-vae.safetensors",
        "agenvas.provider.comfyui.video.clip-vision=test-vision.safetensors"})
class ComfyUiVideoPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = server();
    private static final Path STORAGE_ROOT = temporaryRoot();
    private static final AtomicReference<UUID> IMAGE_PROMPT = new AtomicReference<>();
    private static final AtomicReference<UUID> VIDEO_PROMPT = new AtomicReference<>();
    private static final AtomicInteger IMAGE_SUBMISSIONS = new AtomicInteger();
    private static final AtomicInteger VIDEO_SUBMISSIONS = new AtomicInteger();
    private static final AtomicInteger VIDEO_POLLS = new AtomicInteger();
    private static final AtomicInteger UPLOADS = new AtomicInteger();
    private static final AtomicReference<JsonNode> VIDEO_GRAPH = new AtomicReference<>();
    private static final AtomicReference<byte[]> VIDEO_BYTES = new AtomicReference<>();
    private static final AtomicReference<byte[]> KEYFRAME_UPLOAD = new AtomicReference<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
        registry.add("agenvas.provider.comfyui.endpoint",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    private dev.agenvas.audit.application.CallLogService callLogs;

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private TaskService tasks;
    @Autowired private AssetService assets;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private ComfyUiImageWorker images;
    @Autowired private ComfyUiVideoWorker videos;
    @Autowired private MediaCapabilityService catalog;
    @Autowired private MediaExecutionWorker mediaWorker;
    @Autowired private ComfyUiVideoWorkflow workflow;
    @Autowired private ComfyUiClient client;
    @Autowired private JdbcClient jdbc;
    @Autowired private DSLContext dsl;
    @Autowired private ObjectMapper mapper;

    @Test
    void approvedSelectedKeyframeReachesFixedWanGraphAndOriginalPromptArchivesMp4()
            throws Exception {
        UUID connection = catalog.createConnection("Comfy image and video",
                "http://127.0.0.1:" + SERVER.getAddress().getPort()).id();
        UUID imageCapability = catalog.publishCapability(connection, "Fixed image",
                "COMFY_IMAGE_V1",
                mapper.readTree("{\"checkpoint\":\"test-image.safetensors\"}")).id();
        UUID videoCapability = catalog.publishCapability(connection, "Fixed video",
                "COMFY_VIDEO_V1", mapper.readTree("{\"diffusionModel\":\"test-wan.safetensors\","
                        + "\"textEncoder\":\"test-text.safetensors\","
                        + "\"vae\":\"test-vae.safetensors\","
                        + "\"clipVision\":\"test-vision.safetensors\"}")).id();
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), imageCapability);
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), videoCapability);
        AdminPrincipal owner = identities.setup("comfy-video-integration-secret", "video-admin",
                "video-password-123");
        Project project = projects.create(owner.userId(), "I2V candidate",
                Project.AspectRatio.LANDSCAPE_16_9);
        // 直连图片卡片：先产出这次视频要固定的输入图版本。
        var imageCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Keyframe card", null);
        UUID imageItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), imageCard.artifact().id());
        MediaDraft imageDraft = drafts.save(owner.userId(), project.id(),
                imageItemId, 0, "Cinematic coffee pour", null, null, null);
        Task imageTask = directMedia.run(owner.userId(), project.id(),
                imageCard.artifact().id(), imageItemId,
                imageDraft.version(), "comfy-image-run");
        assertThat(IMAGE_SUBMISSIONS).hasValue(0);
        assertThat(mediaWorker.submitOnce("image-submitter")).isEqualTo(1);
        due(imageTask.id());
        assertThat(mediaWorker.pollOnce("image-poller")).isEqualTo(1);
        Task imageDone = tasks.get(owner.userId(), project.id(), imageTask.id());
        assertThat(imageDone.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID imageVersion = UUID.fromString(imageDone.output().path("artifactVersionId").asText());

        // 直连视频卡片：草稿直接固定刚生成的输入图版本与 5 秒时长。
        var videoCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO,
                "Clip card", null);
        UUID videoItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, owner.userId(), project.id(), videoCard.artifact().id());
        MediaDraft videoDraft = drafts.save(owner.userId(), project.id(),
                videoItemId, 0, "Cinematic coffee pour", imageVersion, 5, null);
        assertThat(VIDEO_SUBMISSIONS).hasValue(0);
        Task videoTask = directMedia.run(owner.userId(), project.id(),
                videoCard.artifact().id(), videoItemId,
                videoDraft.version(), "comfy-video-run");
        assertThat(videoTask.input().path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(videoTask.input().path("durationSeconds").asInt()).isEqualTo(5);
        assertThat(videoTask.input().path("imageVersionId").asText())
                .isEqualTo(imageVersion.toString());
        assertThat(videoTask.input().path("providerOriginSha256").asText())
                .isEqualTo(client.originSha256());
        assertThat(jdbc.sql("select quantity_json ->> 'videoSeconds' from usage_ledger "
                        + "where task_id = :taskId and entry_type = 'RESERVATION'")
                .param("taskId", videoTask.id()).query(String.class).single()).isEqualTo("5");
        VIDEO_BYTES.set(realMp4());
        assertThat(mediaWorker.submitOnce("video-submitter")).isEqualTo(1);
        assertThat(VIDEO_SUBMISSIONS).hasValue(1);
        JsonNode graph = VIDEO_GRAPH.get();
        assertThat(graph.path("1").path("inputs").path("image").asText())
                .isEqualTo("uploaded-keyframe.png");
        assertThat(graph.path("9").path("inputs").path("start_image").get(0).asText())
                .isEqualTo("1");
        assertThat(graph.path("9").path("inputs").path("length").asInt()).isEqualTo(81);
        assertThat(UPLOADS).hasValue(2);
        BufferedImage uploadedKeyframe = uploadedPng(KEYFRAME_UPLOAD.get());
        assertThat(uploadedKeyframe.getWidth()).isEqualTo(832);
        assertThat(uploadedKeyframe.getHeight()).isEqualTo(480);
        assertThat(uploadedKeyframe.getRGB(0, 0) & 0x00ffffff).isEqualTo(0x007f7f7f);
        assertThat(uploadedKeyframe.getRGB(416, 240) & 0x00ffffff).isZero();
        assertThat(tasks.get(owner.userId(), project.id(), videoTask.id()).providerRequestId())
                .isEqualTo(VIDEO_PROMPT.get().toString());
        assertThat(jdbc.sql("select request_key from provider_attempt where task_id = :id")
                .param("id", videoTask.id()).query(UUID.class).single())
                .isEqualTo(VIDEO_PROMPT.get());
        assertThat(jdbc.sql("select candidate_request_id from provider_attempt where task_id = :id")
                .param("id", videoTask.id()).query(UUID.class).single())
                .isEqualTo(VIDEO_PROMPT.get());
        assertThat(jdbc.sql("select candidate_origin_sha256 from provider_attempt where task_id = :id")
                .param("id", videoTask.id()).query(String.class).single())
                .hasSize(64);
        assertThat(mediaWorker.submitOnce("another-submitter")).isZero();
        var oldConnection = catalog.getConnection(connection);
        catalog.updateConnection(connection, oldConnection.version(), oldConnection.name(), true,
                "http://127.0.0.1:65534", null);
        ComfyUiClient rotatedClient = new ComfyUiClient(
                new ComfyUiProperties("http://127.0.0.1:65534"), mapper);
        ComfyUiClientRegistry rotatedRegistry = new ComfyUiClientRegistry(dsl, mapper,
                new ProviderProperties("comfyui", 2),
                new ComfyUiProperties("http://127.0.0.1:65534"), rotatedClient, false);
        rotatedRegistry.registerActive();
        ComfyUiVideoWorkflow rotatedWorkflow = new ComfyUiVideoWorkflow(
                new ComfyUiVideoProperties(true, "rotated-wan.safetensors",
                        "test-text.safetensors", "test-vae.safetensors",
                        "test-vision.safetensors"), mapper);
        assertThat(rotatedWorkflow.version()).isNotEqualTo(workflow.version());
        ComfyUiVideoPoller rotatedPoller = new ComfyUiVideoPoller(tasks, assets,
                rotatedRegistry, mapper, callLogs);
        ComfyUiVideoWorker rotatedVideo = new ComfyUiVideoWorker(tasks, artifacts, assets,
                projects, rotatedClient, rotatedPoller, rotatedWorkflow,
                new ProviderProperties("comfyui", 2), callLogs);
        due(videoTask.id());
        assertThat(mediaWorker.pollOnce("video-poller")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), videoTask.id()).status())
                .isEqualTo(Task.Status.WAITING_PROVIDER);
        due(videoTask.id());
        assertThat(mediaWorker.pollOnce("video-poller")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), videoTask.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(ComfyUiVideoWorkflow.supportsHistoricalVersion(workflow.version())).isTrue();
        assertThat(ComfyUiVideoWorkflow.supportsHistoricalVersion("image-to-video-v1-bad")).isFalse();
        assertThat(completed.output().path("selected").booleanValue()).isTrue();
        assertThat(VIDEO_SUBMISSIONS).hasValue(1);
        assertThat(VIDEO_POLLS).hasValue(2);
        var version = artifacts.get(owner.userId(), project.id(),
                UUID.fromString(completed.output().path("artifactId").asText())).resourceDefaultVersion();
        assertThat(version.content().path("keyframeVersionId").asText())
                .isEqualTo(imageVersion.toString());
        assertThat(version.content().path("providerConfigVersion").asInt()).isEqualTo(1);
        UUID assetId = UUID.fromString(version.content().path("assetId").asText());
        assertThat(assetId).isEqualTo(AssetService.taskVideoAssetId(videoTask.id()));
        assertThat(assets.get(owner.userId(), project.id(), assetId).asset().mediaKind())
                .isEqualTo(Asset.MediaKind.VIDEO);
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id = :id")
                .param("id", videoTask.id()).query(Integer.class).single()).isEqualTo(1);
    }

    private void due(UUID taskId) {
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id")
                .param("id", taskId).update();
    }

    private byte[] realMp4() throws Exception {
        Path image = Files.createTempFile(STORAGE_ROOT, "comfy-video-source-", ".png");
        Path mp4 = Files.createTempFile(STORAGE_ROOT, "comfy-video-result-", ".mp4");
        try {
            Files.write(image, png());
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-loop", "1", "-framerate", "16", "-i", image.toString(),
                    "-t", "5", "-vf", "scale=832:480,format=yuv420p", "-an",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
                    "-y", mp4.toString()));
            return Files.readAllBytes(mp4);
        } finally {
            Files.deleteIfExists(image);
            Files.deleteIfExists(mp4);
        }
    }

    private static byte[] png() throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        }
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upload/image", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                assertThat(body.length).isGreaterThan(1_000);
                int uploadNumber = UPLOADS.incrementAndGet();
                if (uploadNumber == 2) KEYFRAME_UPLOAD.set(body);
                String name = uploadNumber == 1
                        ? "uploaded-image-input.png" : "uploaded-keyframe.png";
                reply(exchange, 200, ("{\"name\":\"" + name
                        + "\",\"type\":\"input\",\"subfolder\":\"\"}").getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/prompt", exchange -> {
                JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                JsonNode graph = body.path("prompt");
                UUID id = UUID.fromString(body.path("prompt_id").asText());
                assertThat(body.path("client_id").asText()).isEqualTo(id.toString());
                boolean video = graph.has("14");
                if (video) {
                    VIDEO_GRAPH.set(graph);
                    VIDEO_SUBMISSIONS.incrementAndGet();
                    VIDEO_PROMPT.set(id);
                } else {
                    IMAGE_SUBMISSIONS.incrementAndGet();
                    IMAGE_PROMPT.set(id);
                }
                reply(exchange, 200, ("{\"prompt_id\":\"" + id + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/history/", exchange -> {
                boolean video = exchange.getRequestURI().getPath()
                        .equals("/history/" + VIDEO_PROMPT.get());
                UUID id = video ? VIDEO_PROMPT.get() : IMAGE_PROMPT.get();
                if (video && VIDEO_POLLS.incrementAndGet() == 1) {
                    reply(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String output = video
                        ? "\"14\":{\"images\":[{\"filename\":\"result.mp4\",\"type\":\"output\",\"subfolder\":\"\"}],\"animated\":[true]}"
                        : "\"8\":{\"images\":[{\"filename\":\"result.png\",\"type\":\"output\",\"subfolder\":\"\"}]}";
                reply(exchange, 200, ("{\"" + id + "\":{\"status\":{\"completed\":true,"
                        + "\"status_str\":\"success\"},\"outputs\":{" + output + "}}}")
                        .getBytes(StandardCharsets.UTF_8));
            });
            server.createContext("/view", exchange -> {
                boolean video = exchange.getRequestURI().getQuery().contains("result.mp4");
                reply(exchange, 200, video ? VIDEO_BYTES.get() : png());
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start fake ComfyUI video server", failure);
        }
    }

    /** Decodes the actual PNG part sent to the fake upload endpoint. */
    private static BufferedImage uploadedPng(byte[] multipart) throws IOException {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        for (int offset = 0; offset <= multipart.length - signature.length; offset++) {
            boolean match = true;
            for (int index = 0; index < signature.length; index++) {
                if (multipart[offset + index] != signature[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(multipart,
                        offset, multipart.length - offset));
                assertThat(image).isNotNull();
                return image;
            }
        }
        throw new AssertionError("Uploaded request contained no PNG part");
    }

    private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-comfy-video-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}

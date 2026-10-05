package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.infrastructure.AutoDlClient;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.ManualUnknownRetryService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and fake AutoDL HTTP, including immutable inputs, recovery and retained audio. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AutoDlVideoPostgresIT.FakeClient.class})
class AutoDlVideoPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger CREATES = new AtomicInteger();
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static final AtomicBoolean DROP_CREATE = new AtomicBoolean();
    private static final AtomicBoolean FAIL_DOWNLOAD = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean EXPIRED_DOWNLOAD = new AtomicBoolean();
    private static volatile byte[] videoBytes;
    private static volatile JsonNode receivedBody;
    private static volatile String submittedKey;
    private static volatile String submittedPath;
    private static volatile String queriedKey;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @AfterAll static void closeServer() { SERVER.stop(0); }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasService canvas;
    @Autowired DirectMediaTaskService direct;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired ManualUnknownRetryService retry;
    @Autowired MediaToolRunner tools;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test void base64InputsStayPinnedWhilePollingArchiveRetriesCancellationAndUnknownAreSafe() throws Exception {
        UUID owner = identities.setup("autodl-admin", "autodl-password-123").userId();
        videoBytes = video();
        var connection = catalog.createConnection("autodl-test-create", "AutoDL fake", "AUTODL", null, "fake-autodl-original-key");
        var settings = mapper.createObjectNode().put("workflowId", "minimax_h3_z0903").put("videoResolution", "480p").put("seed", 123);
        var capability = catalog.publishCapability(connection.id(), "H3 mixed", AutoDlWorkflows.ADAPTER_ID, settings);
        assertThat(catalog.inputPolicy(catalog.capabilitySnapshot(capability.id())).maxReferenceImages()).isEqualTo(6);
        assertThatThrownBy(() -> catalog.createConnection("bad-origin", "Bad", "AUTODL", "https://example.com", "fake"))
                .hasMessageContaining("固定端点");
        assertThatThrownBy(() -> catalog.publishCapability(connection.id(), "Bad", AutoDlWorkflows.ADAPTER_ID,
                settings.deepCopy().put("maxReferenceImages", 7))).hasMessageContaining("范围");
        var fixture = fixture(owner, capability.id());
        Task accepted = run(owner, fixture);
        assertThat(run(owner, fixture).id()).isEqualTo(accepted.id());
        assertThat(accepted.input().path("mediaInput").path("providerParameters").path("resolution").asText()).isEqualTo("480p横(864*480)");
        assertThat(worker.submitOnce("autodl-submit")).isEqualTo(1);
        assertThat(submittedKey).isEqualTo("fake-autodl-original-key");
        assertThat(receivedBody.path("seed").asInt()).isEqualTo(123);
        assertThat(receivedBody.path("ref_image_0").asText()).startsWith("data:image/png;base64,");
        assertThat(receivedBody.path("ref_audio_0").asText()).startsWith("data:audio/wav;base64,");
        assertThat(Base64.getDecoder().decode(receivedBody.path("ref_image_0").asText().split(",",2)[1])).isEqualTo(fixture.imageBytes());
        assertThat(Base64.getDecoder().decode(receivedBody.path("ref_audio_0").asText().split(",",2)[1])).isEqualTo(fixture.audioBytes());
        assertThat(receivedBody.has("ref_image_1")).isFalse();
        assertThat(receivedBody.has("ref_audio_1")).isFalse();
        Task waiting = current(owner, fixture, accepted);
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        catalog.updateConnection(connection.id(), connection.version(), connection.name(), true, null, "fake-autodl-replacement-key");
        RUNNING.set(true); poll(accepted); assertThat(current(owner, fixture, accepted).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(queriedKey).isEqualTo("fake-autodl-original-key");
        RUNNING.set(false); FAIL_DOWNLOAD.set(true);
        poll(accepted); assertThat(current(owner, fixture, accepted).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        EXPIRED_DOWNLOAD.set(true); poll(accepted);
        Task done = current(owner, fixture, accepted);
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(CREATES).hasValue(1);
        assertThat(QUERIES).hasValue(4);
        UUID versionId = UUID.fromString(done.output().path("artifactVersionId").asText());
        var result = artifacts.requireVersion(owner, fixture.project(), fixture.artifact(), versionId);
        Path archived = assets.get(owner, fixture.project(), UUID.fromString(result.content().path("assetId").asText())).path();
        assertThat(Files.readAllBytes(archived)).isEqualTo(videoBytes);
        assertThat(result.frozenInput().path("providerParameters").path("resolution").asText()).isEqualTo("480p横(864*480)");
        assertThat(tools.ffprobe(List.of("-v", "error", "-show_entries", "stream=codec_type", "-of", "json", archived.toString())))
                .contains("audio");
        assertThat(canvas.listMediaVersions(owner, fixture.project(), fixture.card())).hasSize(1);

        var changed = fixture(owner, capability.id()); Task changedTask = run(owner, changed);
        var before = drafts.get(owner, changed.project(), changed.card());
        drafts.save(owner, changed.project(), changed.card(), before.version(), "User changed while queued", before.parameters(),
                before.durationSeconds(), before.capabilityId(), before.videoInputMode(), changed.inputs(), List.of(), null);
        worker.submitOnce("autodl-pinned-submit");
        assertThat(receivedBody.path("prompt").asText()).isEqualTo("Pinned input");
        poll(changedTask);
        assertThat(current(owner, changed, changedTask).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(canvas.list(owner, changed.project()).stream().filter(entry -> entry.item().id().equals(changed.card()))
                .findFirst().orElseThrow().item().selectedVersionId()).isNull();

        var canceled = fixture(owner, capability.id()); Task canceledTask = run(owner, canceled);
        worker.submitOnce("autodl-cancel-submit");
        // Simulate a persisted cancellation intent for an already accepted request;
        // the direct queued-cancel API intentionally does not stop an external job.
        jdbc.sql("update task set cancel_requested=true where id=:id").param("id", canceledTask.id()).update();
        poll(canceledTask);
        assertThat(current(owner, canceled, canceledTask).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(canvas.listMediaVersions(owner, canceled.project(), canceled.card())).hasSize(1);

        var unknown = fixture(owner, capability.id()); Task unknownTask = run(owner, unknown);
        DROP_CREATE.set(true); worker.submitOnce("autodl-unknown-submit");
        Task uncertain = current(owner, unknown, unknownTask);
        assertThat(uncertain.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(uncertain.errorCode()).isEqualTo("AUTODL_CREATE_UNCERTAIN");
        int submissions = CREATES.get();
        assertThat(worker.submitOnce("autodl-no-auto-retry")).isZero();
        assertThat(tasks.recoverExpiredSubmissions(10)).isZero();
        assertThat(CREATES.get()).isEqualTo(submissions);
        Task replacement = retry.create(owner, unknown.project(), uncertain.id(), uncertain.version(), "autodl-explicit-retry");
        worker.submitOnce("autodl-explicit-submit"); poll(replacement);
        assertThat(current(owner, unknown, replacement).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(CREATES.get()).isEqualTo(submissions + 1);
        var incomplete = fixture(owner, capability.id());
        var incompleteDraft = drafts.get(owner, incomplete.project(), incomplete.card());
        var imageOnly = incomplete.inputs().stream().filter(input -> input.role() != MediaDraft.InputRole.AUDIO_REFERENCE).toList();
        var savedIncomplete = drafts.save(owner, incomplete.project(), incomplete.card(), incompleteDraft.version(),
                incompleteDraft.prompt(), incompleteDraft.parameters(), 1, capability.id(),
                MediaDraft.VideoInputMode.GENERAL_REFERENCE, imageOnly, List.of(), null);
        assertThatThrownBy(() -> direct.run(owner, incomplete.project(), incomplete.artifact(), incomplete.card(),
                savedIncomplete.version(), "missing-required-audio")).hasMessageContaining("条音频");
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", incomplete.project())
                .query(Integer.class).single()).isZero();

        var framesCapability = catalog.publishCapability(connection.id(), "H3 frames", AutoDlWorkflows.ADAPTER_ID,
                mapper.createObjectNode().put("workflowId", "minimax_h3_lightx2v").put("videoResolution", "480p"));
        var frames = fixture(owner, capability.id());
        UUID first = frames.inputs().stream().filter(input -> input.role() == MediaDraft.InputRole.REFERENCE).findFirst().orElseThrow().versionId();
        var lastAsset = assets.archiveImage(owner, frames.project(), new ByteArrayInputStream(frames.imageBytes()));
        var last = artifacts.create(owner, frames.project(), Artifact.Kind.IMAGE, "Last", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", lastAsset.id().toString()));
        var frameInputs = List.of(new MediaDraftService.SaveMediaInput(first, MediaDraft.InputRole.START_FRAME, "#67C7F3"),
                new MediaDraftService.SaveMediaInput(last.resourceDefaultVersion().id(), MediaDraft.InputRole.END_FRAME, "#F15CAF"));
        var frameDraft = drafts.save(owner, frames.project(), frames.card(), frames.draftVersion(), "Frames", mapper.createObjectNode(), 1,
                framesCapability.id(), MediaDraft.VideoInputMode.START_END, frameInputs, List.of(), null);
        Task frameTask = direct.run(owner, frames.project(), frames.artifact(), frames.card(), frameDraft.version(), "frames-run");
        worker.submitOnce("autodl-frame-submit");
        assertThat(receivedBody.path("first_frame").asText()).startsWith("data:image/png;base64,");
        assertThat(receivedBody.path("last_frame").asText()).startsWith("data:image/png;base64,");
        assertThat(receivedBody.has("ref_image_0")).isFalse();
        assertThat(receivedBody.path("resolution").asText()).isEqualTo("480p横");
        poll(frameTask);
        assertThat(current(owner, frames, frameTask).status()).isEqualTo(Task.Status.SUCCEEDED);

        var queued = fixture(owner, capability.id()); Task queuedTask = run(owner, queued);
        int createsBeforeCancel = CREATES.get();
        assertThat(direct.cancelQueued(owner, queued.project(), queuedTask.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(worker.submitOnce("autodl-queued-cancel")).isZero();
        assertThat(CREATES.get()).isEqualTo(createsBeforeCancel);
        // A new target is published from a data-only definition and its submitted version stays pinned while polling.
        var definition = mapper.readTree("""
                {"schemaVersion":1,"id":"new_video_v1","label":"New video","minimumSeconds":1,"maximumSeconds":20,
                 "promptLimit":10000,"mode":"TEXT","imageFields":[],"audioFields":[],"minimumImages":0,"minimumAudios":0,
                 "resolutions":["720p横(1280*720)","720p竖(720*1280)"],"defaultResolution":"720p","supportsSeed":false}
                """);
        var customSettings = mapper.createObjectNode().put("workflowId", "new_video_v1").put("videoResolution", "720p");
        customSettings.set("workflowDefinition", definition);
        customSettings.putArray("videoResolutions").add("720p");
        customSettings.putObject("pricingByResolution").putObject("720p").put("amount", "0.2").put("currency", "CNY").put("unit", "SECOND");
        var custom = catalog.publishCapability(connection.id(), "New target", AutoDlWorkflows.ADAPTER_ID, customSettings);
        UUID customProject = projects.create(owner, "New target", Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID customArtifact = artifacts.create(owner, customProject, Artifact.Kind.VIDEO, "New target", null).artifact().id();
        UUID customCard = CanvasMediaFixture.place(canvas, owner, customProject, customArtifact);
        var customDraft = drafts.save(owner, customProject, customCard, 0, "Future video", mapper.createObjectNode().put("videoResolution", "720p"),
                18, custom.id(), MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        Task customTask = direct.run(owner, customProject, customArtifact, customCard, customDraft.version(), "new-target-run");
        worker.submitOnce("autodl-custom-submit");
        assertThat(submittedPath).endsWith("/comfyui_workflow/new_video_v1");
        assertThat(receivedBody.path("resolution").asText()).isEqualTo("720p横(1280*720)");
        assertThat(receivedBody.path("duration").asInt()).isEqualTo(18);
        var changedDefinition = definition.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) changedDefinition).put("id", "new_video_v2");
        var changedSettings = customSettings.deepCopy().put("workflowId", "new_video_v2");
        changedSettings.set("workflowDefinition", changedDefinition);
        catalog.updateCapability(connection.id(), custom.id(), custom.version(), "Updated target", true, AutoDlWorkflows.ADAPTER_ID, changedSettings);
        poll(customTask);
        assertThat(tasks.get(owner, customProject, customTask.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(customTask.input().at("/mediaPricing/amount").asText()).isEqualTo("0.2");

        assertThat(jdbc.sql("select credential_ciphertext is not null from media_provider_connection_version where connection_id=:id limit 1")
                .param("id", connection.id()).query(Boolean.class).single()).isTrue();
    }
    private record Fixture(UUID project, UUID artifact, UUID card, long draftVersion,
            List<MediaDraftService.SaveMediaInput> inputs, byte[] imageBytes, byte[] audioBytes) {}
    private Fixture fixture(UUID owner, UUID capability) throws IOException {
        UUID project = projects.create(owner, "AutoDL fixture", Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID imageAsset = ImageAssetFixture.archive(assets, owner, project);
        var image = artifacts.create(owner, project, Artifact.Kind.IMAGE, "Image", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", imageAsset.toString()));
        Path wav = Files.createTempFile("autodl-test-", ".wav");
        byte[] audioBytes;
        try {
            tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "sine=frequency=220:sample_rate=16000", "-t", "1", "-y", wav.toString()));
            audioBytes = Files.readAllBytes(wav);
        } finally { Files.deleteIfExists(wav); }
        var audioAsset = assets.archiveAudio(owner, project, new ByteArrayInputStream(audioBytes));
        var audio = artifacts.create(owner, project, Artifact.Kind.AUDIO, "Audio", mapper.createObjectNode()
                .put("sourceType", "UPLOAD").put("assetId", audioAsset.id().toString()));
        var video = artifacts.create(owner, project, Artifact.Kind.VIDEO, "Video", null).artifact();
        UUID card = CanvasMediaFixture.place(canvas, owner, project, video.id());
        var inputs = List.of(new MediaDraftService.SaveMediaInput(audio.resourceDefaultVersion().id(), MediaDraft.InputRole.AUDIO_REFERENCE, "#67C7F3"),
                new MediaDraftService.SaveMediaInput(image.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#F15CAF"));
        var draft = drafts.save(owner, project, card, 0, "Pinned input", mapper.createObjectNode(), 1,
                capability, MediaDraft.VideoInputMode.GENERAL_REFERENCE, inputs, List.of(), null);
        return new Fixture(project, video.id(), card, draft.version(), inputs, Files.readAllBytes(assets.get(owner, project, imageAsset).path()), audioBytes);
    }
    private Task run(UUID owner, Fixture fixture) { return direct.run(owner, fixture.project(), fixture.artifact(), fixture.card(), fixture.draftVersion(), "autodl-run-"+fixture.card()); }
    private Task current(UUID owner, Fixture fixture, Task task) { return tasks.get(owner, fixture.project(), task.id()); }
    private void poll(Task task) {
        jdbc.sql("update task set next_action_at=now() - interval '1 second' where id=:id").param("id", task.id()).update();
        assertThat(worker.pollOnce("autodl-poll")).isEqualTo(1);
    }
    private byte[] video() throws IOException {
        Path file = Files.createTempFile("autodl-video-", ".mp4");
        try {
            tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=16",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-y", file.toString()));
            return Files.readAllBytes(file);
        } finally { Files.deleteIfExists(file); }
    }
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/comfyui/outputs/")) {
                    assertThat(path).doesNotContain("unused");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                    if (FAIL_DOWNLOAD.getAndSet(false)) {
                        exchange.sendResponseHeaders(500, -1); exchange.close(); return;
                    }
                    if (EXPIRED_DOWNLOAD.getAndSet(false)) {
                        exchange.sendResponseHeaders(403, -1); exchange.close(); return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                    exchange.sendResponseHeaders(200, videoBytes.length);
                    try (var out = exchange.getResponseBody()) { out.write(videoBytes); }
                    return;
                }
                var mapper = new ObjectMapper();
                var data = mapper.createObjectNode();
                if (exchange.getRequestMethod().equals("POST")) {
                    submittedKey = exchange.getRequestHeaders().getFirst("Authorization");
                    submittedPath = path;
                    receivedBody = mapper.readTree(exchange.getRequestBody().readAllBytes());
                    int count = CREATES.incrementAndGet();
                    if (DROP_CREATE.getAndSet(false)) { exchange.close(); return; }
                    data.put("task_id", "autodl-fake-"+count).put("status", "QUEUED");
                } else {
                    queriedKey = exchange.getRequestHeaders().getFirst("Authorization");
                    int count = QUERIES.incrementAndGet();
                    data.put("task_id", path.substring(path.lastIndexOf('/')+1)).put("status", RUNNING.get() ? "RUNNING" : "SUCCESS");
                    if (!RUNNING.get()) {
                        var results = data.putArray("results");
                        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
                        results.addObject().put("type", "video").put("output_type", "temp")
                                .put("url", origin + "/comfyui/outputs/unused-preview.mp4");
                        results.addObject().put("type", "image").put("output_type", "output")
                                .put("url", origin + "/comfyui/outputs/unused-image.png");
                        results.addObject().put("type", "video").put("output_type", "output")
                                .put("url", origin + "/comfyui/outputs/fresh-" + count + ".mp4");
                        results.addObject().put("type", "video").put("output_type", "output")
                                .put("url", origin + "/comfyui/outputs/unused-extra.mp4");
                    }
                }
                byte[] bytes = mapper.createObjectNode().put("code", "Success").set("data", data).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @TestConfiguration static class FakeClient {
        @Bean @Primary AutoDlClient fakeAutoDl(ObjectMapper mapper) {
            return new AutoDlClient(mapper, URI.create("http://127.0.0.1:"+SERVER.getAddress().getPort()));
        }
    }
}

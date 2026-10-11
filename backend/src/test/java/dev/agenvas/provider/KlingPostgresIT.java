package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Full Task/Asset state machine against PostgreSQL and synthetic official-protocol HTTP responses. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, KlingPostgresIT.FakeClient.class})
class KlingPostgresIT {
    @org.junit.jupiter.api.io.TempDir static Path assetsRoot;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String ORIGINAL_KEY = "synthetic-kling-key";
    private static final UUID SYNTHETIC_RELAY_PROFILE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String SYNTHETIC_VIDEO_URL = "https://reference.example.invalid/frozen.mp4";
    private static final HttpServer SERVER = startServer();
    private static final Map<String, RemoteTask> REMOTE = new ConcurrentHashMap<>();
    private static final List<Received> REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean DROP_NEXT_CREATE = new AtomicBoolean();
    private static final AtomicBoolean EXPIRE_NEXT_RESULT = new AtomicBoolean();
    private static final AtomicBoolean FAIL_DOWNLOAD_ONCE = new AtomicBoolean();
    private static final AtomicInteger DOWNLOADS = new AtomicInteger();
    private static byte[] IMAGE_BYTES;
    private static byte[] VIDEO_BYTES;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("agenvas.storage.root", () -> assetsRoot.toString());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

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
    @Autowired private MediaStyleService styles;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MediaRelayService relay;
    private static UUID owner;

    @BeforeEach void initialize() throws Exception {
        // PER_METHOD lets Testcontainers start PostgreSQL before Spring injects this test instance.
        if (owner == null) owner = identities.setup("kling-admin", "kling-password-123").userId();
        if (IMAGE_BYTES == null) IMAGE_BYTES = png(600, 400);
        if (VIDEO_BYTES == null) VIDEO_BYTES = videoWithAudio();
        REMOTE.clear(); REQUESTS.clear(); QUERIES.clear(); DOWNLOADS.set(0);
        DROP_NEXT_CREATE.set(false); EXPIRE_NEXT_RESULT.set(false); FAIL_DOWNLOAD_ONCE.set(false);
        clearInvocations(relay);
    }

    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Test void imageBatchFreezesAutoAndArchivesEverySingleImageTask() throws Exception {
        var connection = connection();
        var ability = catalog.publishCapability(connection, "Kling Image", KlingProtocol.IMAGE_ADAPTER_ID);
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), ability.id());
        Fixture fixture = fixture(Artifact.Kind.IMAGE, ability.id(), null, 1,
                mapper.createObjectNode().put("generationCount", 2).put("resolution", "2K"), null);
        Task primary = accept(fixture);
        List<UUID> batch = jdbc.sql("select id from task where project_id=:id order by created_at,id")
                .param("id", fixture.project().id()).query(UUID.class).list();
        assertThat(batch).hasSize(2).contains(primary.id());
        assertThat(primary.input().at("/mediaInput/providerParameters/aspectRatio").asText()).isEqualTo("16:9");
        var project = projects.get(owner, fixture.project().id());
        projects.update(owner, project.id(), project.version(), null, Project.AspectRatio.PORTRAIT_9_16);
        for (int index = 0; index < batch.size(); index++) assertThat(worker.submitOnce("kling-image-submit-" + index)).isEqualTo(1);
        assertThat(REQUESTS).hasSize(2);
        for (Received received : REQUESTS) {
            assertThat(received.path()).isEqualTo("/v1/images/generations");
            assertThat(received.body().path("model_name").asText()).isEqualTo("kling-v3");
            assertThat(received.body().path("n").asInt()).isEqualTo(1);
            assertThat(received.body().path("resolution").asText()).isEqualTo("2k");
            assertThat(received.body().path("aspect_ratio").asText()).isEqualTo("16:9");
            assertThat(Base64.getDecoder().decode(received.body().path("image").asText())).isEqualTo(IMAGE_BYTES);
            assertThat(received.body().path("image").asText()).doesNotStartWith("data:");
        }
        for (UUID taskId : batch) {
            Task waiting = task(fixture, taskId);
            assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
            assertThat(waiting.providerRequestId()).isNotBlank();
            due(taskId);
            assertThat(worker.pollOnce("kling-image-poll-" + taskId)).isEqualTo(1);
            Task done = task(fixture, taskId);
            assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(done.providerRequestId()).isEqualTo(waiting.providerRequestId());
            assertThat(QUERIES).contains(waiting.providerRequestId());
            assertThat(Files.readAllBytes(archived(fixture, done))).isEqualTo(IMAGE_BYTES);
            assertThat(selected(UUID.fromString(done.input().path("canvasItemId").asText()), fixture.project().id()))
                    .isEqualTo(UUID.fromString(done.output().path("artifactVersionId").asText()));
        }
        assertThat(DOWNLOADS).hasValue(2);
        assertThat(worker.submitOnce("kling-image-no-resubmit")).isZero();
        assertThat(REQUESTS).hasSize(2);
    }

    @Test void videoPinsConnectionAndCapabilityAndPreservesNativeAudio() throws Exception {
        UUID connection = connection();
        var ability = catalog.publishCapability(connection, "Kling Video", KlingProtocol.VIDEO_ADAPTER_ID);
        Fixture fixture = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.START_END, 2,
                mapper.createObjectNode().put("videoResolution", "2160p"), 5);
        Task accepted = accept(fixture);
        assertThat(worker.submitOnce("kling-video-submit")).isEqualTo(1);
        Task waiting = task(fixture, accepted.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        JsonNode body = REQUESTS.getFirst().body();
        assertThat(REQUESTS.getFirst().path()).isEqualTo("/image-to-video/kling-3.0");
        assertThat(body.at("/settings/resolution").asText()).isEqualTo("4k");
        assertThat(body.at("/settings/duration").isIntegralNumber()).isTrue();
        assertThat(body.at("/settings/duration").asInt()).isEqualTo(5);
        assertThat(body.at("/settings/audio").asText()).isEqualTo("native");
        assertThat(body.at("/settings/multi_shot").asBoolean()).isFalse();
        assertThat(body.path("settings").has("aspect_ratio")).isFalse();
        assertThat(body.path("contents").size()).isEqualTo(3);
        assertThat(body.at("/contents/1/type").asText()).isEqualTo("first_frame");
        assertThat(body.at("/contents/2/type").asText()).isEqualTo("last_frame");
        for (int index : List.of(1, 2)) {
            assertThat(body.at("/contents/" + index + "/url").asText()).doesNotStartWith("data:");
            assertThat(Base64.getDecoder().decode(body.at("/contents/" + index + "/url").asText())).isEqualTo(IMAGE_BYTES);
        }
        var oldConnection = catalog.getConnection(connection);
        catalog.updateConnection(connection, oldConnection.version(), oldConnection.name(), true, null, "synthetic-rotated-key");
        catalog.updateCapability(connection, ability.id(), ability.version(), "New defaults", true,
                KlingProtocol.VIDEO_ADAPTER_ID, mapper.readTree("{\"defaultParameters\":{\"videoResolution\":\"720p\"}}"));
        due(accepted.id());
        assertThat(worker.pollOnce("kling-pinned-video-poll")).isEqualTo(1);
        Task done = task(fixture, accepted.id());
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.providerRequestId()).isEqualTo(waiting.providerRequestId());
        assertThat(done.input().at("/mediaInput/parameters/videoResolution").asText()).isEqualTo("2160p");
        assertThat(QUERIES).containsExactly(waiting.providerRequestId());
        JsonNode probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type:format=duration", "-of", "json", archived(fixture, done).toString())));
        assertThat(probe.path("streams").toString()).contains("video", "audio");
        assertThat(probe.at("/format/duration").asDouble()).isBetween(4.5, 5.5);

        Fixture text = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.TEXT, 0,
                mapper.createObjectNode().put("videoResolution", "1080p"), 5);
        Task textTask = accept(text);
        assertThat(textTask.input().at("/mediaInput/providerParameters/aspectRatio").asText()).isEqualTo("16:9");
        var project = projects.get(owner, text.project().id());
        projects.update(owner, project.id(), project.version(), null, Project.AspectRatio.PORTRAIT_9_16);
        assertThat(worker.submitOnce("kling-text-submit")).isEqualTo(1);
        Received textRequest = REQUESTS.getLast();
        assertThat(textRequest.path()).isEqualTo("/text-to-video/kling-3.0");
        assertThat(textRequest.key()).isEqualTo("Bearer synthetic-rotated-key");
        assertThat(textRequest.body().path("prompt").asText()).isEqualTo("A synthetic studio scene");
        assertThat(textRequest.body().at("/settings/aspect_ratio").asText()).isEqualTo("16:9");
        assertThat(textRequest.body().has("contents")).isFalse();
        due(textTask.id()); worker.pollOnce("kling-text-poll");
        assertThat(task(text, textTask.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
    }

    @Test void recoveryNeverResubmitsAndPersistedCancelIntentKeepsLateVideoUnselected() throws Exception {
        UUID connection = connection();
        var ability = catalog.publishCapability(connection, "Kling Video recovery", KlingProtocol.VIDEO_ADAPTER_ID);
        Fixture lost = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.TEXT, 0, mapper.createObjectNode(), 5);
        Task lostTask = accept(lost);
        DROP_NEXT_CREATE.set(true);
        assertThat(worker.submitOnce("kling-lost-create")).isEqualTo(1);
        Task unknown = task(lost, lostTask.id());
        assertThat(unknown.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(unknown.errorCode()).isEqualTo("KLING_CREATE_UNCERTAIN");
        assertThat(tasks.recoverExpiredSubmissions(1)).isZero();
        assertThat(worker.submitOnce("kling-after-lost")).isZero();
        assertThat(REQUESTS).hasSize(1);
        assertThat(QUERIES).isEmpty();

        Fixture refresh = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.START_END, 1, mapper.createObjectNode(), 5);
        Task refreshTask = accept(refresh);
        EXPIRE_NEXT_RESULT.set(true);
        worker.submitOnce("kling-expired-submit");
        String original = task(refresh, refreshTask.id()).providerRequestId();
        due(refreshTask.id());
        assertThat(worker.pollOnce("kling-expired-refresh")).isEqualTo(1);
        assertThat(task(refresh, refreshTask.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(QUERIES).containsExactly(original, original);
        assertThat(REQUESTS).hasSize(2);

        Fixture retry = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.TEXT, 0, mapper.createObjectNode(), 5);
        Task retryTask = accept(retry);
        worker.submitOnce("kling-download-retry-submit");
        String retryId = task(retry, retryTask.id()).providerRequestId();
        FAIL_DOWNLOAD_ONCE.set(true); due(retryTask.id());
        worker.pollOnce("kling-download-retry-first");
        assertThat(task(retry, retryTask.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        due(retryTask.id()); worker.pollOnce("kling-download-retry-second");
        assertThat(task(retry, retryTask.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(QUERIES.subList(2, QUERIES.size())).containsOnly(retryId);
        assertThat(REQUESTS).hasSize(3);

        Fixture queued = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.TEXT, 0, mapper.createObjectNode(), 5);
        Task queuedTask = accept(queued);
        assertThat(directMedia.cancelQueued(owner, queued.project().id(), queuedTask.id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(worker.submitOnce("kling-queued-canceled")).isZero();
        assertThat(REQUESTS).hasSize(3);

        Fixture canceled = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.TEXT, 0, mapper.createObjectNode(), 5);
        Task canceledTask = accept(canceled);
        worker.submitOnce("kling-canceled-submit");
        assertThatThrownBy(() -> directMedia.cancelQueued(owner, canceled.project().id(), canceledTask.id()))
                .isInstanceOf(ApiProblemException.class);
        // Direct cancellation is READY-only. This synthetic persisted intent exercises late-result
        // reconciliation, rather than claiming that submitted direct work has a public cancel API.
        assertThat(jdbc.sql("update task set cancel_requested=true where id=:id")
                .param("id", canceledTask.id()).update()).isEqualTo(1);
        due(canceledTask.id()); worker.pollOnce("kling-canceled-late-poll");
        assertThat(task(canceled, canceledTask.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(selected(canceled.canvasItemId(), canceled.project().id())).isNull();
        JsonNode late = mapper.readTree(jdbc.sql("select output_json::text from task_late_result where task_id=:id")
                .param("id", canceledTask.id()).query(String.class).single());
        assertThat(late.path("selected").asBoolean()).isFalse();
        assertThat(late.path("artifactVersionId").asText()).isNotBlank();
        assertThat(worker.submitOnce("kling-final-no-resubmit")).isZero();
        assertThat(REQUESTS).hasSize(4);
    }

    @Test void unsupportedInputsAndStyleExpandedPromptCreateNoTask() throws Exception {
        UUID connection = connection();
        var image = catalog.publishCapability(connection, "Kling Image validation", KlingProtocol.IMAGE_ADAPTER_ID);
        var video = catalog.publishCapability(connection, "Kling Video validation", KlingProtocol.VIDEO_ADAPTER_ID);
        assertThatThrownBy(() -> catalog.publishCapability(connection, "Fractional duration", KlingProtocol.VIDEO_ADAPTER_ID,
                mapper.createObjectNode().put("defaultDurationSeconds", 5.5))).isInstanceOf(ApiProblemException.class);
        rejected(fixture(Artifact.Kind.IMAGE, image.id(), null, 0, mapper.createObjectNode().put("resolution", "4K"), null));
        rejected(fixture(Artifact.Kind.VIDEO, video.id(), MediaDraft.VideoInputMode.TEXT, 0,
                mapper.createObjectNode().put("videoResolution", "480p"), 5));
        rejected(fixture(Artifact.Kind.VIDEO, video.id(), MediaDraft.VideoInputMode.START_END, 0, mapper.createObjectNode(), 5));
        rejected(fixture(Artifact.Kind.VIDEO, video.id(), MediaDraft.VideoInputMode.TEXT, 0, mapper.createObjectNode(), 2));
        for (String field : List.of("width", "height", "content_type", "byte_size")) {
            Fixture reference = fixture(Artifact.Kind.IMAGE, image.id(), null, 1, mapper.createObjectNode(), null);
            Object value = switch (field) { case "content_type" -> "image/webp"; case "byte_size" -> 10_000_001L; default -> 299; };
            // Synthetic metadata isolates the provider-specific preflight from the upload validator.
            jdbc.sql("update asset set " + field + "=:value where id=:id")
                    .param("value", value).param("id", reference.references().getFirst().assetId()).update();
            rejected(reference);
        }
        Fixture narrow = fixture(Artifact.Kind.VIDEO, video.id(), MediaDraft.VideoInputMode.START_END, 1, mapper.createObjectNode(), 5);
        jdbc.sql("update asset set width=1000,height=300 where id=:id")
                .param("id", narrow.references().getFirst().assetId()).update();
        rejected(narrow);
        var style = styles.create("Long synthetic style", "Test", "s".repeat(2500), true);
        Fixture styled = fixture(Artifact.Kind.IMAGE, image.id(), null, 0, mapper.createObjectNode(), null);
        var styledDraft = drafts.save(owner, styled.project().id(), styled.canvasItemId(), styled.draftVersion(),
                "A synthetic studio scene", mapper.createObjectNode(), null, image.id(), null, List.of(), List.of(), style.id());
        rejected(new Fixture(styled.project(), styled.card(), styled.canvasItemId(), styledDraft.version(), List.of()));
        assertThat(REQUESTS).isEmpty();
        assertThat(QUERIES).isEmpty();
    }

    @Test void everyCurrentProfilePublishesGraphFreeVersionedInputsAndMergesDefaults() {
        UUID connection = connection();
        assertThat(KlingModels.profiles()).hasSize(15);
        for (var profile : KlingModels.profiles()) {
            String adapter = profile.kind() == Task.Kind.IMAGE_GENERATION ? "KLING_IMAGE" : "KLING_VIDEO";
            ObjectNode settings = mapper.createObjectNode().put("model", profile.model()).put("operation", profile.operation().name());
            var ability = catalog.publishCapability(connection, "Native " + profile.operation() + profile.model(), adapter, settings);
            var snapshot = catalog.capabilitySnapshot(ability.id());
            JsonNode published = catalog.administratorSettings(snapshot);
            assertThat(published.path("klingInputs").size()).isEqualTo(KlingModels.inputs(profile).size());
            for (JsonNode field : published.path("klingInputs")) {
                assertThat(field.has("nodeId")).isFalse(); assertThat(field.has("fieldName")).isFalse();
            }
        }
        var settings = mapper.readTree("{\"model\":\"kling-v3\",\"defaultParameters\":{\"dynamicValues\":{\"n\":3,\"resolution\":\"2k\"}}}");
        var ability = catalog.publishCapability(connection, "Native defaults", "KLING_IMAGE", settings);
        var merged = catalog.parameters(catalog.forDraft(ability.id(), Task.Kind.IMAGE_GENERATION),
                mapper.readTree("{\"dynamicValues\":{\"aspect_ratio\":\"1:1\"}}"));
        assertThat(merged.at("/dynamicValues/n").asInt()).isEqualTo(3);
        assertThat(merged.at("/dynamicValues/resolution").asText()).isEqualTo("2k");
        assertThat(merged.at("/dynamicValues/aspect_ratio").asText()).isEqualTo("1:1");
        var voice = catalog.publishCapability(connection, "Native voice defaults", "KLING_VIDEO",
                mapper.readTree("{\"model\":\"kling-2.6\",\"defaultParameters\":{\"dynamicValues\":{\"audio\":\"native\",\"resolution\":\"1080p\"}}}"));
        Fixture partial = fixture(Artifact.Kind.VIDEO, voice.id(), MediaDraft.VideoInputMode.TEXT, 0,
                (ObjectNode) mapper.readTree("{\"dynamicValues\":{\"voiceId1\":\"synthetic-voice\"}}"), 5);
        assertThat(drafts.get(owner, partial.project().id(), partial.canvasItemId()).parameters().at("/dynamicValues/voiceId1").asText()).isEqualTo("synthetic-voice");
        var bounded = catalog.publishCapability(connection, "Native duration boundary", "KLING_VIDEO",
                mapper.readTree("{\"model\":\"kling-3.0\"}"));
        Fixture longDraft = fixture(Artifact.Kind.VIDEO, bounded.id(), MediaDraft.VideoInputMode.TEXT, 0, mapper.createObjectNode(), 60);
        assertThat(drafts.get(owner, longDraft.project().id(), longDraft.canvasItemId()).durationSeconds()).isEqualTo(60);
        rejected(longDraft);
    }

    @Test void nativeImageCountUsesOneTaskAndRecoversCheckpointedMultipleResults() throws Exception {
        UUID connection = connection();
        var ability = catalog.publishCapability(connection, "Native image batch", "KLING_IMAGE",
                mapper.readTree("{\"model\":\"kling-v3\",\"defaultParameters\":{\"dynamicValues\":{\"n\":3,\"resolution\":\"2k\"}},\"pricing\":{\"amount\":\"0.2\",\"currency\":\"CNY\",\"unit\":\"IMAGE\"}}"));
        Fixture fixture = fixture(Artifact.Kind.IMAGE, ability.id(), null, 0, mapper.createObjectNode(), null);
        assertThat(directMedia.preflight(owner, fixture.project().id(), fixture.card().artifact().id(), fixture.canvasItemId(), fixture.draftVersion()).outputCount()).isEqualTo(3);
        Task accepted = accept(fixture);
        assertThat(accepted.input().path("expectedImageCount").asInt()).isEqualTo(3);
        assertThat(worker.submitOnce("native-image-submit")).isEqualTo(1);
        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.getFirst().body().path("n").asInt()).isEqualTo(3);
        var before = task(fixture, accepted.id());
        FAIL_DOWNLOAD_ONCE.set(true); due(accepted.id()); worker.pollOnce("native-image-failed-download");
        assertThat(task(fixture, accepted.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(tasks.providerResultManifest(task(fixture, accepted.id()))).isPresent();
        due(accepted.id()); worker.pollOnce("native-image-recovered-download");
        Task done = task(fixture, accepted.id());
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.output().path("additionalResults").size()).isEqualTo(2);
        assertThat(QUERIES).containsExactly(before.providerRequestId());
        assertThat(REQUESTS).hasSize(1);
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", fixture.project().id()).query(Integer.class).single()).isEqualTo(1);
        JsonNode settlement = mapper.readTree(jdbc.sql("select quantity_json::text from usage_ledger where task_id=:id and entry_type='SETTLEMENT'")
                .param("id", done.id()).query(String.class).single());
        assertThat(settlement.path("imageCount").asInt()).isEqualTo(3);
        assertThat(Files.readAllBytes(archived(fixture, done))).isEqualTo(IMAGE_BYTES);
    }

    @Test void turboUsesItsOwnPathAndSettingsAndOriginalId() throws Exception {
        var ability = catalog.publishCapability(connection(), "Turbo native", "KLING_VIDEO",
                mapper.readTree("{\"model\":\"kling-3.0-turbo\"}"));
        Fixture fixture = fixture(Artifact.Kind.VIDEO, ability.id(), MediaDraft.VideoInputMode.TEXT, 0,
                (ObjectNode) mapper.readTree("{\"dynamicValues\":{\"resolution\":\"1080p\"}}"), 5);
        Task accepted = accept(fixture); worker.submitOnce("native-turbo-submit");
        assertThat(REQUESTS.getFirst().path()).isEqualTo("/text-to-video/kling-3.0-turbo");
        assertThat(REQUESTS.getFirst().body().path("settings").has("audio")).isFalse();
        assertThat(REQUESTS.getFirst().body().path("settings").has("multi_shot")).isFalse();
        assertThat(accepted.input().at("/mediaInput/providerParameters/kling/model").asText()).isEqualTo("kling-3.0-turbo");
        due(accepted.id()); worker.pollOnce("native-turbo-poll");
        assertThat(task(fixture, accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(REQUESTS).hasSize(1);
    }

    @Test void omniStructuredMentionUsesActualImageListPositionAndFrozenSlot() throws Exception {
        var ability = catalog.publishCapability(connection(), "Native Omni references", "KLING_IMAGE",
                mapper.readTree("{\"model\":\"kling-v3-omni\"}"));
        Fixture fixture = fixture(Artifact.Kind.IMAGE, ability.id(), null, 1, mapper.createObjectNode(), null);
        UUID reference = fixture.references().getFirst().versionId();
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("image_5", reference.toString());
        MediaDraft draft = drafts.save(owner, fixture.project().id(), fixture.canvasItemId(), fixture.draftVersion(),
                "Edit \uFFFC", parameters, null, ability.id(), null,
                List.of(new MediaDraftService.SaveMediaInput(reference, MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                List.of(new MediaDraft.PromptMention(reference, MediaDraft.InputRole.REFERENCE)), null);
        Task accepted = accept(new Fixture(fixture.project(), fixture.card(), fixture.canvasItemId(), draft.version(), fixture.references()));
        assertThat(accepted.input().path("prompt").asText()).isEqualTo("Edit <<<image_1>>>");
        assertThat(accepted.input().at("/mediaInput/providerParameters/kling/values/image_5").asText()).isEqualTo(reference.toString());
        assertThat(accepted.input().at("/mediaInput/providerParameters/kling/values/result_type").asText()).isEqualTo("single");
        worker.submitOnce("native-omni-ref-submit");
        Task waiting = task(fixture, accepted.id());
        assertThat(waiting.status()).as("Omni submission errorCode=%s", waiting.errorCode()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(REQUESTS.getFirst().path()).isEqualTo("/v1/images/omni-image");
        assertThat(REQUESTS.getFirst().body().path("result_type").asText()).isEqualTo("single");
        assertThat(REQUESTS.getFirst().body().path("n").asInt()).isEqualTo(1);
        assertThat(REQUESTS.getFirst().body().path("image_list").size()).isEqualTo(1);
        assertThat(Base64.getDecoder().decode(REQUESTS.getFirst().body().at("/image_list/0/image").asText())).isEqualTo(IMAGE_BYTES);
        due(accepted.id()); worker.pollOnce("native-omni-ref-poll");
        Task done = task(fixture, accepted.id());
        assertThat(done.status()).as("Omni reconciliation errorCode=%s", done.errorCode()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.providerRequestId()).isEqualTo(waiting.providerRequestId());
        assertThat(QUERIES).containsExactly(waiting.providerRequestId());
        assertThat(REQUESTS).hasSize(1);
        assertThat(Files.readAllBytes(archived(fixture, done))).isEqualTo(IMAGE_BYTES);
    }

    @Test void motionPinsOriginalMediaAndRelayAndSettlesProbedDurationDespiteStaleDraftDuration() throws Exception {
        var ability = catalog.publishCapability(connection(), "Synthetic Motion generation", KlingModels.VIDEO_ADAPTER_ID,
                mapper.readTree("{\"model\":\"kling-3.0\",\"operation\":\"MOTION_CONTROL\",\"pricing\":{\"amount\":\"0.123456\",\"currency\":\"CNY\",\"unit\":\"SECOND\"}}"));
        Project project = projects.create(owner, "Kling synthetic Motion " + UUID.randomUUID(), Project.AspectRatio.LANDSCAPE_16_9);
        Asset imageAsset = assets.archiveImage(owner, project.id(), new ByteArrayInputStream(IMAGE_BYTES));
        Asset videoAsset = assets.archiveVideo(owner, project.id(), new ByteArrayInputStream(VIDEO_BYTES));
        var image = artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, "Synthetic Motion character",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", imageAsset.id().toString()));
        var video = artifacts.create(owner, project.id(), Artifact.Kind.VIDEO, "Synthetic Motion reference",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", videoAsset.id().toString()));
        UUID imageVersion = image.resourceDefaultVersion().id(), videoVersion = video.resourceDefaultVersion().id();
        var card = artifacts.create(owner, project.id(), Artifact.Kind.VIDEO, "Synthetic Motion target", null);
        UUID canvasItem = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner, project.id(), card.artifact().id());
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject(KlingModels.VALUES_PROPERTY).put("image_1", imageVersion.toString())
                .put("motion_video", videoVersion.toString()).put("character_orientation", "video")
                .put("audio", "original").put("resolution", "720p");
        var saved = drafts.save(owner, project.id(), canvasItem, 0, "A synthetic Motion scene", parameters, 60,
                ability.id(), MediaDraft.VideoInputMode.GENERAL_REFERENCE,
                List.of(new MediaDraftService.SaveMediaInput(imageVersion, MediaDraft.InputRole.REFERENCE, "#7C3AED"),
                        new MediaDraftService.SaveMediaInput(videoVersion, MediaDraft.InputRole.VIDEO_REFERENCE, "#7C3AED")), List.of(), null);
        assertThat(saved.durationSeconds()).isEqualTo(60);
        Fixture fixture = new Fixture(project, card, canvasItem, saved.version(),
                List.of(new Reference(imageAsset.id(), imageVersion), new Reference(videoAsset.id(), videoVersion)));
        Task accepted = accept(fixture);
        assertThat(accepted.input().has("durationSeconds")).isFalse();
        assertThat(accepted.input().at("/mediaInput").has("durationSeconds")).isFalse();
        assertThat(accepted.input().at("/mediaInput/providerParameters/kling/values").has("duration")).isFalse();
        assertThat(accepted.input().at("/mediaInput/providerParameters/kling/values/image_1").asText()).isEqualTo(imageVersion.toString());
        assertThat(accepted.input().at("/mediaInput/providerParameters/kling/values/motion_video").asText()).isEqualTo(videoVersion.toString());
        assertThat(accepted.input().at("/mediaInput/images/0/artifactId").asText()).isEqualTo(image.artifact().id().toString());
        assertThat(accepted.input().at("/mediaInput/images/0/versionId").asText()).isEqualTo(imageVersion.toString());
        assertThat(accepted.input().at("/mediaInput/videos/0/artifactId").asText()).isEqualTo(video.artifact().id().toString());
        assertThat(accepted.input().at("/mediaInput/videos/0/versionId").asText()).isEqualTo(videoVersion.toString());
        assertThat(accepted.input().at("/mediaInput/videos/0/relayProfileId").asText()).isEqualTo(SYNTHETIC_RELAY_PROFILE.toString());
        verify(relay).pinProfile(videoAsset);
        JsonNode reservation = ledger(accepted.id(), "RESERVATION");
        assertThat(reservation.path("quantity").path("videoSeconds").isNull()).isTrue();
        assertThat(reservation.path("estimate").isNull()).isTrue();
        assertThat(reservation.path("costStatus").asText()).isEqualTo("UNKNOWN");

        assertThat(worker.submitOnce("native-motion-submit")).isEqualTo(1);
        Task waiting = task(fixture, accepted.id());
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(REQUESTS).hasSize(1);
        Received received = REQUESTS.getFirst();
        assertThat(received.path()).isEqualTo("/motion-control/kling-3.0");
        assertThat(received.body().at("/settings/character_orientation").asText()).isEqualTo("video");
        assertThat(received.body().at("/settings/audio").asText()).isEqualTo("original");
        assertThat(received.body().path("settings").has("duration")).isFalse();
        assertThat(received.body().path("settings").has("aspect_ratio")).isFalse();
        assertThat(received.body().path("contents").size()).isEqualTo(3);
        assertThat(received.body().at("/contents/1/type").asText()).isEqualTo("image");
        assertThat(Base64.getDecoder().decode(received.body().at("/contents/1/url").asText())).isEqualTo(IMAGE_BYTES);
        assertThat(received.body().at("/contents/2/type").asText()).isEqualTo("video");
        assertThat(received.body().at("/contents/2/url").asText()).isEqualTo(SYNTHETIC_VIDEO_URL);
        verify(relay).signedVideo(owner, project.id(), videoAsset.id(), SYNTHETIC_RELAY_PROFILE);

        due(accepted.id());
        assertThat(worker.pollOnce("native-motion-poll")).isEqualTo(1);
        Task done = task(fixture, accepted.id());
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.providerRequestId()).isEqualTo(waiting.providerRequestId());
        assertThat(QUERIES).containsExactly(waiting.providerRequestId());
        assertThat(REQUESTS).hasSize(1);
        assertThat(worker.submitOnce("native-motion-no-resubmit")).isZero();
        assertThat(Files.readAllBytes(archived(fixture, done))).isEqualTo(VIDEO_BYTES);
        UUID archivedVersion = UUID.fromString(done.output().path("artifactVersionId").asText());
        JsonNode archivedContent = artifacts.requireVersion(owner, project.id(), card.artifact().id(), archivedVersion).content();
        Asset archivedAsset = assets.requireReadyMedia(owner, project.id(),
                UUID.fromString(archivedContent.path("assetId").asText()), Asset.MediaKind.VIDEO);
        BigDecimal seconds = BigDecimal.valueOf(archivedAsset.durationMs(), 3);
        assertThat(seconds).isBetween(new BigDecimal("4.5"), new BigDecimal("5.5"));
        assertThat(archivedContent.at("/parameters/providerResultDurationMs").asInt()).isEqualTo(archivedAsset.durationMs());
        JsonNode settlement = ledger(done.id(), "SETTLEMENT");
        assertThat(new BigDecimal(settlement.path("quantity").path("videoSeconds").asText())).isEqualByComparingTo(seconds);
        assertThat(settlement.path("estimate").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.123456").multiply(seconds).setScale(6, RoundingMode.HALF_UP));
        assertThat(settlement.path("actual").isNull()).isTrue();
        assertThat(settlement.path("costStatus").asText()).isEqualTo("ESTIMATED");
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", project.id()).query(Integer.class).single()).isEqualTo(1);
    }

    private JsonNode ledger(UUID taskId, String entryType) {
        return mapper.readTree(jdbc.sql("select jsonb_build_object('quantity',quantity_json,'estimate',estimated_cost,'actual',actual_cost,'costStatus',cost_status)::text from usage_ledger where task_id=:id and entry_type=:type")
                .param("id", taskId).param("type", entryType).query(String.class).single());
    }

    private UUID connection() {
        var connection = catalog.createConnection("kling-" + UUID.randomUUID(), "Kling synthetic", "KLING", null, ORIGINAL_KEY);
        assertThat(catalog.getConnectionVersion(connection.id(), connection.currentVersion()).orElseThrow().origin())
                .isEqualTo(KlingClient.ORIGIN);
        return connection.id();
    }

    private Fixture fixture(Artifact.Kind kind, UUID capability, MediaDraft.VideoInputMode mode,
            int referenceCount, ObjectNode parameters, Integer duration) {
        Project project = projects.create(owner, "Kling synthetic " + UUID.randomUUID(), Project.AspectRatio.LANDSCAPE_16_9);
        var references = new java.util.ArrayList<Reference>();
        var inputs = new java.util.ArrayList<MediaDraftService.SaveMediaInput>();
        for (int index = 0; index < referenceCount; index++) {
            UUID assetId = assets.archiveImage(owner, project.id(), new ByteArrayInputStream(IMAGE_BYTES)).id();
            var reference = artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, "Synthetic reference " + index,
                    mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()));
            references.add(new Reference(assetId, reference.resourceDefaultVersion().id()));
            inputs.add(new MediaDraftService.SaveMediaInput(reference.resourceDefaultVersion().id(),
                    kind == Artifact.Kind.IMAGE ? MediaDraft.InputRole.REFERENCE
                            : index == 0 ? MediaDraft.InputRole.START_FRAME : MediaDraft.InputRole.END_FRAME, "#7C3AED"));
        }
        var card = artifacts.create(owner, project.id(), kind, "Synthetic target", null);
        UUID canvasItem = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner, project.id(), card.artifact().id());
        long version = drafts.save(owner, project.id(), canvasItem, 0, "A synthetic studio scene", parameters,
                duration, capability, mode, inputs, List.of(), null).version();
        return new Fixture(project, card, canvasItem, version, List.copyOf(references));
    }

    private Task accept(Fixture fixture) {
        return directMedia.run(owner, fixture.project().id(), fixture.card().artifact().id(), fixture.canvasItemId(),
                fixture.draftVersion(), "kling-command-" + UUID.randomUUID());
    }
    private Task task(Fixture fixture, UUID id) { return tasks.get(owner, fixture.project().id(), id); }
    private void rejected(Fixture fixture) {
        assertThatThrownBy(() -> accept(fixture)).isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", fixture.project().id())
                .query(Integer.class).single()).isZero();
    }
    private void due(UUID id) { jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=:id").param("id", id).update(); }
    private UUID selected(UUID canvasItem, UUID project) {
        return canvas.list(owner, project).stream().filter(entry -> entry.item().id().equals(canvasItem))
                .findFirst().orElseThrow().item().selectedVersionId();
    }
    private Path archived(Fixture fixture, Task done) {
        UUID version = UUID.fromString(done.output().path("artifactVersionId").asText());
        UUID asset = UUID.fromString(artifacts.requireVersion(owner, fixture.project().id(), fixture.card().artifact().id(), version)
                .content().path("assetId").asText());
        return assets.get(owner, fixture.project().id(), asset).path();
    }
    private static byte[] png(int width, int height) throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
    private byte[] videoWithAudio() throws Exception {
        Path output = Files.createTempFile("kling-synthetic-video-", ".mp4");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "color=c=blue:s=600x400:r=16", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "5", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "-y", output.toString()));
            return Files.readAllBytes(output);
        } finally { Files.deleteIfExists(output); }
    }

    private static URI origin() { return URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort()); }
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/result/") || path.startsWith("/expired/")) {
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                    DOWNLOADS.incrementAndGet();
                    if (path.startsWith("/expired/")) { exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
                    if (FAIL_DOWNLOAD_ONCE.getAndSet(false)) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
                    boolean video = path.endsWith(".mp4");
                    byte[] bytes = video ? VIDEO_BYTES : IMAGE_BYTES;
                    exchange.getResponseHeaders().set("Content-Type", video ? "video/mp4" : "image/png");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                    return;
                }
                ObjectMapper json = new ObjectMapper();
                if (exchange.getRequestMethod().equals("POST")) {
                    JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
                    String key = exchange.getRequestHeaders().getFirst("Authorization");
                    REQUESTS.add(new Received(path, body, key));
                    String id = "synthetic-kling-task-" + REQUESTS.size();
                    boolean video = !path.startsWith("/v1/");
                    String resultType = path.equals("/v1/images/omni-image") ? body.path("result_type").asText("single") : null;
                    int count = "series".equals(resultType) ? body.path("series_amount").asInt(4) : body.path("n").asInt(1);
                    REMOTE.put(id, new RemoteTask(video, key, EXPIRE_NEXT_RESULT.getAndSet(false), new AtomicInteger(), count, resultType));
                    if (DROP_NEXT_CREATE.getAndSet(false)) { exchange.close(); return; }
                    ObjectNode response = json.createObjectNode().put("code", 0);
                    if (video) response.putObject("data").put("id", id).put("status", "submitted");
                    else response.putObject("data").put("task_id", id).put("task_status", "submitted");
                    respond(exchange, response.toString());
                    return;
                }
                String id = path.equals("/tasks") ? exchange.getRequestURI().getRawQuery().substring("task_ids=".length())
                        : path.substring(path.lastIndexOf('/') + 1);
                RemoteTask remote = REMOTE.get(id);
                assertThat(remote).isNotNull();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(remote.key());
                QUERIES.add(id);
                int query = remote.queries().incrementAndGet();
                String url = "http://127.0.0.1:" + server.getAddress().getPort()
                        + (remote.expireFirst() && query == 1 ? "/expired/" : "/result/") + id + (remote.video() ? ".mp4" : ".png");
                ObjectNode response = json.createObjectNode().put("code", 0);
                if (remote.video()) {
                    var data = response.putArray("data").addObject().put("id", id).put("status", "succeeded");
                    data.putArray("outputs").addObject().put("type", "video").put("id", "video-0").put("url", url).put("duration", "5");
                } else {
                    var data = response.putObject("data").put("task_id", id).put("task_status", "succeed");
                    var result = data.putObject("task_result");
                    // Omni returns the selected branch explicitly, unlike ordinary image generations.
                    if (remote.resultType() != null) result.put("result_type", remote.resultType());
                    var images = result.putArray("series".equals(remote.resultType()) ? "series_images" : "images");
                    for (int index = 0; index < remote.count(); index++) images.addObject().put("index", index).put("url", url + (remote.count() == 1 ? "" : "?output=" + index));
                }
                respond(exchange, response.toString());
            });
            server.start(); return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    @TestConfiguration static class FakeClient {
        @Bean @Primary KlingClient fakeKlingClient(ObjectMapper mapper) { return new KlingClient(mapper, origin()); }
        @Bean @Primary KlingMediaDownloadPolicy fakeKlingDownloadPolicy() { return KlingMediaDownloadPolicy.forLoopbackTest(origin()); }
        /** Synthetic URL signing only; this test neither uploads private data nor verifies an object-store connection. */
        @Bean @Primary MediaRelayService fakeMediaRelayService() {
            MediaRelayService relay = mock(MediaRelayService.class);
            when(relay.pinProfile(any(Asset.class))).thenReturn(SYNTHETIC_RELAY_PROFILE);
            when(relay.signedVideo(any(UUID.class), any(UUID.class), any(UUID.class), eq(SYNTHETIC_RELAY_PROFILE)))
                    .thenAnswer(call -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                        return SYNTHETIC_VIDEO_URL;
                    });
            return relay;
        }
    }
    private record Fixture(Project project, ArtifactService.ArtifactView card, UUID canvasItemId,
            long draftVersion, List<Reference> references) {}
    private record Reference(UUID assetId, UUID versionId) {}
    private record Received(String path, JsonNode body, String key) {}
    private record RemoteTask(boolean video, String key, boolean expireFirst, AtomicInteger queries, int count, String resultType) {}
}

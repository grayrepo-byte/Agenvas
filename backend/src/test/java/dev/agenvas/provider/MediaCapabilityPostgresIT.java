package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.skill.worker-enabled=false",
        "agenvas.library.worker-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class MediaCapabilityPostgresIT {
    private static final long LOCK_WAIT_SECONDS = 5;
    private static final long LOCK_POLL_MILLIS = 10;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        // Synthetic AES key for catalog fixtures; no real provider credentials are used.
        properties.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired private MediaCapabilityService catalog;
    @Autowired private ObjectMapper mapper;
    @Autowired private JooqMediaCapabilityRepository repository;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcClient jdbc;

    @AfterEach
    void restoreMockDefaults() {
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION),
                UUID.fromString("00000000-0000-4000-8000-000000000102"));
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION),
                UUID.fromString("00000000-0000-4000-8000-000000000103"));
    }

    @Test
    void mockBootstrapAndTwoCapabilitiesOnOneConnection() {
        assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).adapterId())
                .isEqualTo("MOCK_IMAGE");
        assertThat(catalog.defaultFor(Task.Kind.VIDEO_GENERATION).adapterId())
                .isEqualTo("MOCK_VIDEO");

        UUID comfyConnection = catalog.createConnection("Local ComfyUI", "http://127.0.0.1:8188").id();
        UUID comfyImage = catalog.publishCapability(comfyConnection, "Image", "COMFY_IMAGE_V1",
                imageSettings()).id();
        UUID comfyVideo = catalog.publishCapability(comfyConnection, "Video", "COMFY_VIDEO_V1",
                videoSettings()).id();
        assertThat(catalog.resolve(comfyImage, Task.Kind.IMAGE_GENERATION, 3).connectionId())
                .isEqualTo(comfyConnection);
        assertThat(catalog.resolve(comfyVideo, Task.Kind.VIDEO_GENERATION, 5).connectionId())
                .isEqualTo(comfyConnection);
        assertThatThrownBy(() -> catalog.resolve(comfyVideo, Task.Kind.VIDEO_GENERATION, 6))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void defaultsUseCasAndDisablingRetainsHistoricalVersions() {
        UUID first = catalog.createConnection("First ComfyUI", "http://127.0.0.1:8288").id();
        UUID second = catalog.createConnection("Second ComfyUI", "http://127.0.0.1:8388").id();
        UUID image = catalog.publishCapability(first, "Image", "COMFY_IMAGE_V1",
                imageSettings()).id();
        UUID video = catalog.publishCapability(second, "Video", "COMFY_VIDEO_V1",
                videoSettings()).id();

        long imageVersion = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, imageVersion, image);
        catalog.setDefault(Task.Kind.VIDEO_GENERATION,
                catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), video);
        assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).capabilityId()).isEqualTo(image);
        assertThat(catalog.defaultFor(Task.Kind.VIDEO_GENERATION).capabilityId()).isEqualTo(video);
        assertThatThrownBy(() -> catalog.setDefault(Task.Kind.IMAGE_GENERATION, imageVersion, image))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));

        var connection = catalog.getConnection(first);
        catalog.setConnectionEnabled(first, connection.version(), false);
        assertThatThrownBy(() -> catalog.resolve(image, Task.Kind.IMAGE_GENERATION, 3))
                .isInstanceOf(ApiProblemException.class);
        assertThat(catalog.getConnectionVersion(first, 1)).isPresent();
    }

    @Test
    void uninstalledAdapterCannotBePublished() {
        UUID connection = catalog.createConnection("Another ComfyUI", "http://127.0.0.1:8488").id();
        assertThatThrownBy(() -> catalog.publishCapability(connection, "Arbitrary", "DYNAMIC_SCRIPT"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_UNSUPPORTED_CAPABILITY"));
    }

    @Test
    void localImageProcessorIsSystemManagedAndExcludedFromGenerationCatalog() {
        UUID connectionId = UUID.fromString("00000000-0000-4000-8000-000000000201");
        UUID capabilityId = UUID.fromString("00000000-0000-4000-8000-000000000202");
        var connection = catalog.getConnection(connectionId);

        assertThat(catalog.publishedCandidates()).noneMatch(candidate ->
                candidate.binding().capabilityId().equals(capabilityId));
        assertThat(catalog.candidates(Task.Kind.IMAGE_GENERATION, 0)).noneMatch(candidate ->
                candidate.binding().capabilityId().equals(capabilityId));
        assertThatThrownBy(() -> catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capabilityId))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.setConnectionEnabled(connectionId,
                connection.version(), false)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.publishCapability(connectionId, "Duplicate",
                "LOCAL_IMAGE_PROCESSOR")).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void workflowOutputTypeChangesCreateHistoryAndClearOnlyThePreviousDefault() {
        var connection = catalog.createConnection(UUID.randomUUID().toString(), "Synthetic RunningHub",
                "RUNNINGHUB", "https://runninghub.example.com", "synthetic-runninghub-key");
        var settings = mapper.readTree("""
            {"runningHub":{"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
              "fields":[],"outputs":[{"nodeId":"8","kind":"IMAGE","primary":true,"maxCount":1}]}}
            """);
        var capability = catalog.publishCapability(connection.id(), "Synthetic workflow", "RUNNINGHUB_IMAGE", settings);
        var original = catalog.resolve(capability.id(), Task.Kind.IMAGE_GENERATION, 0);
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability.id());
        long previousDefaultVersion = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        var videoDefault = catalog.defaultCapabilityId(Task.Kind.VIDEO_GENERATION);
        long videoDefaultVersion = catalog.defaultVersion(Task.Kind.VIDEO_GENERATION);
        var replacement = settings.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) replacement.path("runningHub").path("outputs").get(0)).put("kind", "VIDEO");

        assertThatThrownBy(() -> catalog.updateCapability(connection.id(), capability.id(), capability.version(),
                "Invalid output", true, "RUNNINGHUB_IMAGE", replacement)).isInstanceOf(ApiProblemException.class);
        assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isEqualTo(capability.id());
        var changed = catalog.updateCapability(connection.id(), capability.id(), capability.version(),
                "Video workflow", true, "RUNNINGHUB_VIDEO", replacement);
        assertThat(changed.currentVersion()).isEqualTo(capability.currentVersion() + 1);
        assertThat(catalog.resolve(capability.id(), Task.Kind.VIDEO_GENERATION, 5).adapterId()).isEqualTo("RUNNINGHUB_VIDEO");
        assertThat(catalog.pinnedSnapshot(original).adapterId()).isEqualTo("RUNNINGHUB_IMAGE");
        assertThat(catalog.settings(original).path("runningHub").path("outputs").get(0).path("kind").asText()).isEqualTo("IMAGE");
        assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isNull();
        assertThat(catalog.defaultVersion(Task.Kind.IMAGE_GENERATION)).isEqualTo(previousDefaultVersion + 1);
        assertThat(catalog.defaultCapabilityId(Task.Kind.VIDEO_GENERATION)).isEqualTo(videoDefault);
        assertThat(catalog.defaultVersion(Task.Kind.VIDEO_GENERATION)).isEqualTo(videoDefaultVersion);
        assertThatThrownBy(() -> catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability.id())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.updateCapability(connection.id(), capability.id(), capability.version(),
                "Stale save", true, "RUNNINGHUB_VIDEO", replacement)).isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));

        ((tools.jackson.databind.node.ObjectNode) replacement.path("runningHub").path("outputs").get(0)).put("kind", "AUDIO");
        var audio = catalog.updateCapability(connection.id(), capability.id(), changed.version(),
                "Audio workflow", true, "RUNNINGHUB_AUDIO", replacement);
        assertThat(audio.currentVersion()).isEqualTo(changed.currentVersion() + 1);
        assertThat(catalog.resolve(capability.id(), Task.Kind.AUDIO_GENERATION, 0).adapterId()).isEqualTo("RUNNINGHUB_AUDIO");
        assertThat(catalog.defaultCapabilityId(Task.Kind.VIDEO_GENERATION)).isEqualTo(videoDefault);
    }

    @Test
    void concurrentDefaultSelectionReadsTheChangedTypeAfterTheCapabilityLockIsReleased() throws Exception {
        var connection = catalog.createConnection(UUID.randomUUID().toString(), "Synthetic concurrent workflow",
                "RUNNINGHUB", "https://runninghub.example.com", "synthetic-runninghub-key");
        var image = mapper.readTree("""
            {"runningHub":{"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
              "fields":[],"outputs":[{"kind":"IMAGE","primary":true,"maxCount":1}]}}
            """);
        var capability = catalog.publishCapability(connection.id(), "Synthetic workflow", "RUNNINGHUB_IMAGE", image);
        long defaultVersion = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        var previousDefault = catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION);
        var video = image.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) video.path("runningHub").path("outputs").get(0)).put("kind", "VIDEO");
        var selection = new AtomicReference<Future<?>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                repository.lockCapability(capability.id());
                // Prime statistics before the other thread starts, covering an initially empty observation.
                assertThat(capabilitySelectionBlocked()).isFalse();
                selection.set(executor.submit(() -> catalog.setDefault(Task.Kind.IMAGE_GENERATION, defaultVersion, capability.id())));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_SECONDS);
                boolean blocked = false;
                while (!blocked && System.nanoTime() < deadline) {
                    // Statistics are cached per transaction; refresh them without releasing the capability lock.
                    jdbc.sql("select pg_stat_clear_snapshot()").query().listOfRows();
                    blocked = capabilitySelectionBlocked();
                    if (!blocked) {
                        try { Thread.sleep(LOCK_POLL_MILLIS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    }
                }
                assertThat(blocked).isTrue();
                catalog.updateCapability(connection.id(), capability.id(), capability.version(),
                        "Changed while selection waits", true, "RUNNINGHUB_VIDEO", video);
            });
            assertThatThrownBy(() -> selection.get().get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiProblemException.class);
        }
        assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isEqualTo(previousDefault);
        assertThat(catalog.defaultVersion(Task.Kind.IMAGE_GENERATION)).isEqualTo(defaultVersion);
    }

    @Test
    void fixedComfyAdaptersStillRejectOutputTypeChanges() {
        var connection = catalog.createConnection("Synthetic fixed ComfyUI", "http://127.0.0.1:8588");
        var capability = catalog.publishCapability(connection.id(), "Image", "COMFY_IMAGE_V1", imageSettings());
        assertThatThrownBy(() -> catalog.updateCapability(connection.id(), capability.id(), capability.version(),
                "Video", true, "COMFY_VIDEO_V1", videoSettings())).isInstanceOf(ApiProblemException.class);
        assertThat(catalog.capabilitySnapshot(capability.id()).adapterId()).isEqualTo("COMFY_IMAGE_V1");
    }

    private boolean capabilitySelectionBlocked() {
        return jdbc.sql("""
            select exists(select 1 from pg_stat_activity
              where pid <> pg_backend_pid() and wait_event_type = 'Lock'
                and query like '%media_capability%' and query like '%for update%')
            """).query(Boolean.class).single();
    }

    private tools.jackson.databind.JsonNode imageSettings() {
        return mapper.readTree("{\"checkpoint\":\"image.safetensors\"}");
    }

    private tools.jackson.databind.JsonNode videoSettings() {
        return mapper.readTree("{\"diffusionModel\":\"video.safetensors\","
                + "\"textEncoder\":\"text.safetensors\",\"vae\":\"vae.safetensors\","
                + "\"clipVision\":\"vision.safetensors\"}");
    }
}

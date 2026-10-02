package dev.agenvas.provider.application;

import static dev.agenvas.db.Tables.COMFYUI_CONFIG_VERSION;
import static dev.agenvas.db.Tables.MEDIA_LEGACY_IMPORT_MARKER;
import static dev.agenvas.db.Tables.MEDIA_LEGACY_ORIGIN_MAP;
import static dev.agenvas.db.Tables.PROJECT;
import static dev.agenvas.db.Tables.PROVIDER_ATTEMPT;
import static dev.agenvas.db.Tables.TASK;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.provider.application.ProviderProperties;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Imports legacy origins once, then fences unmapped work before any media scheduler may claim it. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LegacyMediaImportService implements ApplicationRunner {
    private static final UUID MOCK_CONNECTION = UUID.fromString(
            "00000000-0000-4000-8000-000000000101");
    private static final UUID MOCK_IMAGE = UUID.fromString(
            "00000000-0000-4000-8000-000000000102");
    private static final UUID MOCK_VIDEO = UUID.fromString(
            "00000000-0000-4000-8000-000000000103");
    /** media_legacy_import_marker 是单行标记表，固定主键为 1。 */
    private static final short IMPORT_MARKER_ID = 1;
    private final DSLContext dsl;
    private final JooqMediaCapabilityRepository repository;
    private final MediaCapabilityService catalog;
    private final ProjectEventService events;
    private final ProviderProperties provider;
    private final ComfyUiImageProperties image;
    private final ComfyUiVideoProperties video;
    private final ObjectProvider<ComfyUiClientRegistry> oldRegistry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final boolean recoveryMode;
    private volatile boolean ready;

    public LegacyMediaImportService(DSLContext dsl, JooqMediaCapabilityRepository repository,
            MediaCapabilityService catalog, ProjectEventService events,
            ProviderProperties provider, ComfyUiImageProperties image,
            ComfyUiVideoProperties video, ObjectProvider<ComfyUiClientRegistry> oldRegistry,
            ObjectMapper mapper, Clock clock, TransactionTemplate transactions,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) {
        this.dsl = dsl;
        this.repository = repository;
        this.catalog = catalog;
        this.events = events;
        this.provider = provider;
        this.image = image;
        this.video = video;
        this.oldRegistry = oldRegistry;
        this.mapper = mapper;
        this.clock = clock;
        this.transactions = transactions;
        this.recoveryMode = recoveryMode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!recoveryMode) importBeforeWorkers();
    }

    public boolean ready() { return ready; }

    /** A row lock makes two application instances share one atomic import decision. */
    public void importBeforeWorkers() {
        transactions.executeWithoutResult(ignored -> importInTransaction());
        ready = true;
    }

    private void importInTransaction() {
        // 单行标记表上的行锁串行化跨实例的一次性导入决定；completed_at 非空即已完成。
        Boolean completed = dsl.select(MEDIA_LEGACY_IMPORT_MARKER.COMPLETED_AT)
                .from(MEDIA_LEGACY_IMPORT_MARKER)
                .where(MEDIA_LEGACY_IMPORT_MARKER.ID.eq(IMPORT_MARKER_ID))
                .forUpdate()
                .fetchSingle(MEDIA_LEGACY_IMPORT_MARKER.COMPLETED_AT) != null;
        if (completed) {
            return;
        }
        // Register the active legacy address only during the one-time import.
        if ("comfyui".equalsIgnoreCase(provider.mode())) {
            ComfyUiClientRegistry registry = oldRegistry.getIfAvailable();
            if (registry != null) registry.registerActive();
        }
        List<OldOrigin> origins = dsl.select(COMFYUI_CONFIG_VERSION.CONFIG_VERSION,
                        COMFYUI_CONFIG_VERSION.ORIGIN, COMFYUI_CONFIG_VERSION.ORIGIN_SHA256)
                .from(COMFYUI_CONFIG_VERSION)
                .orderBy(COMFYUI_CONFIG_VERSION.CONFIG_VERSION.desc())
                .fetch(row -> new OldOrigin(row.value1(), row.value2(), row.value3()));
        Imported imported = origins.isEmpty() ? null : importComfy(origins);
        fenceLegacyTasks(imported);
        dsl.update(MEDIA_LEGACY_IMPORT_MARKER)
                .set(MEDIA_LEGACY_IMPORT_MARKER.COMPLETED_AT,
                        clock.instant().atOffset(ZoneOffset.UTC))
                .set(MEDIA_LEGACY_IMPORT_MARKER.IMPORTED_CONNECTION_ID,
                        imported == null ? null : imported.connectionId())
                .where(MEDIA_LEGACY_IMPORT_MARKER.ID.eq(IMPORT_MARKER_ID))
                .execute();
    }

    private Imported importComfy(List<OldOrigin> origins) {
        OldOrigin latest = origins.getFirst();
        UUID connectionId = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insertConnection(connectionId, "Legacy ComfyUI", MediaPlatform.COMFYUI,
                latest.origin(), latest.sha256(), null, null, null, null, now);
        Map<Integer, Integer> versions = new HashMap<>();
        Map<String, Integer> byIdentity = new HashMap<>();
        byIdentity.put(latest.origin() + "\u0000" + latest.sha256(), 1);
        int next = 2;
        for (OldOrigin origin : origins) {
            String identity = origin.origin() + "\u0000" + origin.sha256();
            Integer version = byIdentity.get(identity);
            if (version == null) {
                version = next++;
                repository.insertConnectionVersion(connectionId, version, origin.origin(),
                        origin.sha256(), null, null, null, null, now);
                byIdentity.put(identity, version);
            }
            versions.put(origin.configVersion(), version);
            dsl.insertInto(MEDIA_LEGACY_ORIGIN_MAP)
                    .set(MEDIA_LEGACY_ORIGIN_MAP.CONFIG_VERSION, origin.configVersion())
                    .set(MEDIA_LEGACY_ORIGIN_MAP.CONNECTION_ID, connectionId)
                    .set(MEDIA_LEGACY_ORIGIN_MAP.CONNECTION_VERSION, version)
                    .set(MEDIA_LEGACY_ORIGIN_MAP.ORIGIN_SHA256, origin.sha256())
                    .execute();
        }
        ObjectNode imageSettings = imageSettings();
        ObjectNode videoSettings = videoSettings();
        UUID imageId = importCapability(connectionId, "Legacy ComfyUI image",
                "COMFY_IMAGE_V1", imageSettings);
        UUID videoId = importCapability(connectionId, "Legacy ComfyUI video",
                "COMFY_VIDEO_V1", videoSettings);
        // Environment settings only choose initial defaults once; subsequent starts use the DB.
        if ("comfyui".equalsIgnoreCase(provider.mode())) {
            if (imageSettings != null) catalog.setDefault(dev.agenvas.task.domain.Task.Kind.IMAGE_GENERATION,
                    catalog.defaultVersion(dev.agenvas.task.domain.Task.Kind.IMAGE_GENERATION), imageId);
            if (video.enabled() && videoSettings != null) catalog.setDefault(
                    dev.agenvas.task.domain.Task.Kind.VIDEO_GENERATION,
                    catalog.defaultVersion(dev.agenvas.task.domain.Task.Kind.VIDEO_GENERATION), videoId);
        }
        return new Imported(connectionId, imageId, videoId, versions);
    }

    private UUID importCapability(UUID connectionId, String name, String adapterId,
            ObjectNode settings) {
        if (settings == null) {
            // Polling needs the pinned protocol and origin, never historical model file names.
            UUID id = UUID.randomUUID();
            String spec = "{\"schemaVersion\":1,\"legacyHistoricalOnly\":true,\"settings\":{}}";
            repository.insertCapability(id, connectionId, name, adapterId,
                    Sha256.hex(adapterId + ":legacy:v1"), spec, clock.instant());
            repository.updateCapability(id, 0, name, false, 1, clock.instant());
            return id;
        }
        return catalog.publishCapability(connectionId, name, adapterId, settings).id();
    }

    private ObjectNode imageSettings() {
        if (!modelName(image.checkpoint())) return null;
        ObjectNode settings = mapper.createObjectNode();
        settings.put("checkpoint", image.checkpoint());
        return settings;
    }

    private ObjectNode videoSettings() {
        if (!modelName(video.diffusionModel()) || !modelName(video.textEncoder())
                || !modelName(video.vae()) || !modelName(video.clipVision())) return null;
        ObjectNode settings = mapper.createObjectNode();
        settings.put("diffusionModel", video.diffusionModel());
        settings.put("textEncoder", video.textEncoder());
        settings.put("vae", video.vae());
        settings.put("clipVision", video.clipVision());
        return settings;
    }

    private static boolean modelName(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                && !value.contains("..") && value.endsWith(".safetensors");
    }

    private void fenceLegacyTasks(Imported imported) {
        List<OldTask> old = dsl.select(TASK.ID, TASK.PROJECT_ID, PROJECT.OWNER_ID, TASK.KIND,
                        TASK.STATUS, TASK.PROVIDER_REQUEST_ID, TASK.INPUT_JSON, TASK.VERSION)
                .from(TASK)
                .join(PROJECT).on(PROJECT.ID.eq(TASK.PROJECT_ID))
                .where(TASK.KIND.in(Task.Kind.IMAGE_GENERATION.name(), Task.Kind.VIDEO_GENERATION.name()))
                .and(TASK.CAPABILITY_ID.isNull())
                .and(TASK.STATUS.notIn(Task.Status.SUCCEEDED.name(), Task.Status.FAILED.name(),
                        Task.Status.CANCELED.name(), Task.Status.BLOCKED.name()))
                .fetch(row -> new OldTask(row.value1(), row.value2(), row.value3(),
                        Task.Kind.valueOf(row.value4()), Task.Status.valueOf(row.value5()),
                        row.value6(), row.value7().data(), row.value8()));
        for (OldTask task : old) {
            OldAttempt attempt = latestAttempt(task.id());
            var input = mapper.readTree(task.inputJson());
            int oldVersion = input.path("providerConfigVersion").asInt(-1);
            String workflow = input.path("workflowVersion").asText();
            if ((task.kind() == Task.Kind.IMAGE_GENERATION
                    && "mock-image-v1".equals(workflow)
                    || task.kind() == Task.Kind.VIDEO_GENERATION
                    && "mock-video-v1".equals(workflow))
                    && (task.status() == Task.Status.PENDING || task.status() == Task.Status.READY
                    || task.status() == Task.Status.RUNNING && task.providerRequestId() == null)) {
                bindLegacyMock(task);
                continue;
            }
            boolean supported = task.kind() == Task.Kind.IMAGE_GENERATION
                    ? ComfyUiImageWorkflow.supportsHistoricalVersion(workflow)
                    : ComfyUiVideoWorkflow.supportsHistoricalVersion(workflow);
            Integer connectionVersion = imported == null ? null : imported.versions().get(oldVersion);
            OldOrigin origin = oldVersion < 1 ? null : originFor(oldVersion);
            boolean exact = supported && origin != null && attempt != null
                    && origin.sha256().equals(attempt.originSha256())
                    && (task.providerRequestId() == null || task.providerRequestId().equals(
                            attempt.providerRequestId()))
                    && attempt.candidateRequestId() != null
                    && (task.providerRequestId() == null || task.providerRequestId().equals(
                            attempt.candidateRequestId().toString()));
            if (exact && connectionVersion != null
                    && (task.status() == Task.Status.WAITING_PROVIDER
                            || task.status() == Task.Status.UNKNOWN
                            || task.status() == Task.Status.SUBMITTING
                            || task.status() == Task.Status.RUNNING
                                    && task.providerRequestId() != null)) {
                UUID capabilityId = task.kind() == Task.Kind.IMAGE_GENERATION
                        ? imported.imageId() : imported.videoId();
                bindHistoricalTask(task, attempt, imported.connectionId(),
                        connectionVersion, capabilityId);
            } else if (task.status() == Task.Status.PENDING || task.status() == Task.Status.READY
                    || task.status() == Task.Status.RUNNING
                    || task.status() == Task.Status.WAITING_PROVIDER) {
                blockUnresolved(task);
            } else if (task.status() == Task.Status.UNKNOWN || task.status() == Task.Status.SUBMITTING) {
                dsl.update(TASK)
                        .set(TASK.ERROR_CODE, "LEGACY_UNRESOLVED")
                        .where(TASK.ID.eq(task.id()))
                        .and(TASK.STATUS.eq(task.status().name()))
                        .execute();
            }
        }
    }

    private void bindLegacyMock(OldTask task) {
        dsl.update(TASK)
                .set(TASK.CONNECTION_ID, MOCK_CONNECTION)
                .set(TASK.CONNECTION_VERSION, 1)
                .set(TASK.CAPABILITY_ID, task.kind() == Task.Kind.IMAGE_GENERATION
                        ? MOCK_IMAGE : MOCK_VIDEO)
                .set(TASK.CAPABILITY_VERSION, 1)
                .where(TASK.ID.eq(task.id()))
                .and(TASK.CAPABILITY_ID.isNull())
                .execute();
    }

    private void bindHistoricalTask(OldTask task, OldAttempt attempt, UUID connectionId,
            int connectionVersion, UUID capabilityId) {
        dsl.update(TASK)
                .set(TASK.CONNECTION_ID, connectionId)
                .set(TASK.CONNECTION_VERSION, connectionVersion)
                .set(TASK.CAPABILITY_ID, capabilityId)
                .set(TASK.CAPABILITY_VERSION, 1)
                .where(TASK.ID.eq(task.id()))
                .and(TASK.CAPABILITY_ID.isNull())
                .execute();
        dsl.update(PROVIDER_ATTEMPT)
                .set(PROVIDER_ATTEMPT.CONNECTION_ID, connectionId)
                .set(PROVIDER_ATTEMPT.CONNECTION_VERSION, connectionVersion)
                .set(PROVIDER_ATTEMPT.CAPABILITY_ID, capabilityId)
                .set(PROVIDER_ATTEMPT.CAPABILITY_VERSION, 1)
                .where(PROVIDER_ATTEMPT.ID.eq(attempt.id()))
                .and(PROVIDER_ATTEMPT.CAPABILITY_ID.isNull())
                .execute();
    }

    private void blockUnresolved(OldTask task) {
        events.recordChange(task.ownerId(), task.projectId(), () -> {
            int changed = dsl.update(TASK)
                    .set(TASK.STATUS, Task.Status.BLOCKED.name())
                    .set(TASK.ERROR_CODE, "LEGACY_UNRESOLVED")
                    .set(TASK.LEASE_OWNER, (String) null)
                    .set(TASK.LEASE_UNTIL, (java.time.OffsetDateTime) null)
                    .set(TASK.VERSION, TASK.VERSION.plus(1))
                    .set(TASK.UPDATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                    .where(TASK.ID.eq(task.id()))
                    .and(TASK.VERSION.eq(task.version()))
                    .and(TASK.STATUS.eq(task.status().name()))
                    .execute();
            if (changed == 0) return ProjectEventService.Change.unchanged(false);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("status", Task.Status.BLOCKED.name());
            payload.put("cancelRequested", false);
            payload.put("possibleExternalCost", task.providerRequestId() != null);
            return ProjectEventService.Change.changed(true,
                    new ProjectEventService.EventDraft("task.status.changed", 1,
                            task.id(), task.version() + 1, payload));
        });
    }

    private OldAttempt latestAttempt(UUID taskId) {
        return dsl.select(PROVIDER_ATTEMPT.ID, PROVIDER_ATTEMPT.CANDIDATE_REQUEST_ID,
                        PROVIDER_ATTEMPT.CANDIDATE_ORIGIN_SHA256,
                        PROVIDER_ATTEMPT.PROVIDER_REQUEST_ID)
                .from(PROVIDER_ATTEMPT)
                .where(PROVIDER_ATTEMPT.TASK_ID.eq(taskId))
                .orderBy(PROVIDER_ATTEMPT.CREATED_AT.desc(), PROVIDER_ATTEMPT.ID.desc())
                .limit(1)
                .fetchOptional(row -> new OldAttempt(row.value1(), row.value2(),
                        row.value3(), row.value4()))
                .orElse(null);
    }

    private OldOrigin originFor(int configVersion) {
        return dsl.select(COMFYUI_CONFIG_VERSION.CONFIG_VERSION,
                        COMFYUI_CONFIG_VERSION.ORIGIN, COMFYUI_CONFIG_VERSION.ORIGIN_SHA256)
                .from(COMFYUI_CONFIG_VERSION)
                .where(COMFYUI_CONFIG_VERSION.CONFIG_VERSION.eq(configVersion))
                .fetchOptional(row -> new OldOrigin(row.value1(), row.value2(), row.value3()))
                .orElse(null);
    }

    private record OldOrigin(int configVersion, String origin, String sha256) {}
    private record Imported(UUID connectionId, UUID imageId, UUID videoId,
            Map<Integer, Integer> versions) {}
    private record OldTask(UUID id, UUID projectId, UUID ownerId, Task.Kind kind,
            Task.Status status, String providerRequestId, String inputJson, long version) {}
    private record OldAttempt(UUID id, UUID candidateRequestId, String originSha256,
            String providerRequestId) {}
}

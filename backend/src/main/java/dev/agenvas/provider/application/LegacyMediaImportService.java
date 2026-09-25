package dev.agenvas.provider.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.plan.application.PlanProviderProperties;
import dev.agenvas.provider.infrastructure.ComfyUiClientRegistry;
import dev.agenvas.provider.infrastructure.ComfyUiImageProperties;
import dev.agenvas.provider.infrastructure.ComfyUiImageWorkflow;
import dev.agenvas.provider.infrastructure.ComfyUiVideoProperties;
import dev.agenvas.provider.infrastructure.ComfyUiVideoWorkflow;
import dev.agenvas.provider.infrastructure.JdbcMediaCapabilityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    private final JdbcClient jdbc;
    private final JdbcMediaCapabilityRepository repository;
    private final MediaCapabilityService catalog;
    private final ProjectEventService events;
    private final PlanProviderProperties provider;
    private final ComfyUiImageProperties image;
    private final ComfyUiVideoProperties video;
    private final ObjectProvider<ComfyUiClientRegistry> oldRegistry;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final boolean recoveryMode;
    private volatile boolean ready;

    public LegacyMediaImportService(JdbcClient jdbc, JdbcMediaCapabilityRepository repository,
            MediaCapabilityService catalog, ProjectEventService events,
            PlanProviderProperties provider, ComfyUiImageProperties image,
            ComfyUiVideoProperties video, ObjectProvider<ComfyUiClientRegistry> oldRegistry,
            ObjectMapper mapper, Clock clock, TransactionTemplate transactions,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) {
        this.jdbc = jdbc;
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
        Boolean completed = jdbc.sql("select completed_at is not null from "
                        + "media_legacy_import_marker where id=1 for update")
                .query(Boolean.class).single();
        if (completed) {
            return;
        }
        // Register the active legacy address only during the one-time import.
        if ("comfyui".equalsIgnoreCase(provider.mode())) {
            ComfyUiClientRegistry registry = oldRegistry.getIfAvailable();
            if (registry != null) registry.registerActive();
        }
        List<OldOrigin> origins = jdbc.sql("select config_version,origin,origin_sha256 "
                        + "from comfyui_config_version order by config_version desc")
                .query((row, index) -> new OldOrigin(row.getInt("config_version"),
                        row.getString("origin"), row.getString("origin_sha256"))).list();
        Imported imported = origins.isEmpty() ? null : importComfy(origins);
        staleUnapprovedPlans();
        fenceLegacyTasks(imported);
        jdbc.sql("update media_legacy_import_marker set completed_at=:now,"
                        + "imported_connection_id=:connectionId where id=1")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("connectionId", imported == null ? null : imported.connectionId())
                .update();
    }

    private Imported importComfy(List<OldOrigin> origins) {
        OldOrigin latest = origins.getFirst();
        UUID connectionId = UUID.randomUUID();
        Instant now = clock.instant();
        repository.insertConnection(connectionId, "Legacy ComfyUI", "COMFYUI",
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
            jdbc.sql("insert into media_legacy_origin_map "
                            + "(config_version,connection_id,connection_version,origin_sha256) "
                            + "values (:configVersion,:connectionId,:connectionVersion,:sha)")
                    .param("configVersion", origin.configVersion())
                    .param("connectionId", connectionId)
                    .param("connectionVersion", version)
                    .param("sha", origin.sha256()).update();
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
                    sha256(adapterId + ":legacy:v1"), spec, clock.instant());
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

    private void staleUnapprovedPlans() {
        List<OldPlan> plans = jdbc.sql("select ep.id,ep.project_id,ep.run_id,ep.revision,"
                        + "ep.stage,p.owner_id from execution_plan ep "
                        + "join project p on p.id=ep.project_id "
                        + "where ep.status in ('PENDING','NEEDS_INPUT') "
                        + "and exists (select 1 from plan_step ps where ps.plan_id=ep.id "
                        + "and ps.capability_id is null)")
                .query((row, index) -> new OldPlan(row.getObject("id", UUID.class),
                        row.getObject("project_id", UUID.class),
                        row.getObject("run_id", UUID.class), row.getInt("revision"),
                        row.getString("stage"), row.getObject("owner_id", UUID.class))).list();
        for (OldPlan plan : plans) {
            events.recordChange(plan.ownerId(), plan.projectId(), () -> {
                int changed = jdbc.sql("update execution_plan set status='STALE',"
                                + "updated_at=:now where id=:id "
                                + "and status in ('PENDING','NEEDS_INPUT')")
                        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                        .param("id", plan.id()).update();
                if (changed == 0) return ProjectEventService.Change.unchanged(false);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("planId", plan.id().toString());
                payload.put("runId", plan.runId().toString());
                payload.put("stage", plan.stage());
                payload.put("status", "STALE");
                return ProjectEventService.Change.changed(true,
                        new ProjectEventService.EventDraft("plan.stale", 1,
                                plan.id(), plan.revision(), payload));
            });
        }
    }

    private void fenceLegacyTasks(Imported imported) {
        List<OldTask> old = jdbc.sql("select t.id,t.project_id,p.owner_id,t.kind,t.status,"
                        + "t.provider_request_id,t.input_json::text as input_json,t.version "
                        + "from task t join project p on p.id=t.project_id "
                        + "where t.kind in ('IMAGE_GENERATION','VIDEO_GENERATION') "
                        + "and t.capability_id is null and t.status not in "
                        + "('SUCCEEDED','FAILED','CANCELED','BLOCKED')")
                .query((row, index) -> new OldTask(row.getObject("id", UUID.class),
                        row.getObject("project_id", UUID.class),
                        row.getObject("owner_id", UUID.class), row.getString("kind"),
                        row.getString("status"), row.getString("provider_request_id"),
                        row.getString("input_json"), row.getLong("version"))).list();
        for (OldTask task : old) {
            OldAttempt attempt = latestAttempt(task.id());
            var input = mapper.readTree(task.inputJson());
            int oldVersion = input.path("providerConfigVersion").asInt(-1);
            String workflow = input.path("workflowVersion").asText();
            if (("IMAGE_GENERATION".equals(task.kind())
                    && "mock-image-v1".equals(workflow)
                    || "VIDEO_GENERATION".equals(task.kind())
                    && "mock-video-v1".equals(workflow))
                    && ("PENDING".equals(task.status()) || "READY".equals(task.status())
                    || "RUNNING".equals(task.status()) && task.providerRequestId() == null)) {
                bindLegacyMock(task);
                continue;
            }
            boolean supported = "IMAGE_GENERATION".equals(task.kind())
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
                    && ("WAITING_PROVIDER".equals(task.status())
                            || "UNKNOWN".equals(task.status())
                            || "SUBMITTING".equals(task.status())
                            || "RUNNING".equals(task.status())
                                    && task.providerRequestId() != null)) {
                UUID capabilityId = "IMAGE_GENERATION".equals(task.kind())
                        ? imported.imageId() : imported.videoId();
                bindHistoricalTask(task, attempt, imported.connectionId(),
                        connectionVersion, capabilityId);
            } else if ("PENDING".equals(task.status()) || "READY".equals(task.status())
                    || "RUNNING".equals(task.status())
                    || "WAITING_PROVIDER".equals(task.status())) {
                blockUnresolved(task);
            } else if ("UNKNOWN".equals(task.status()) || "SUBMITTING".equals(task.status())) {
                jdbc.sql("update task set error_code='LEGACY_UNRESOLVED' "
                                + "where id=:id and status=:status")
                        .param("id", task.id()).param("status", task.status()).update();
            }
        }
    }

    private void bindLegacyMock(OldTask task) {
        jdbc.sql("update task set connection_id=:connectionId,connection_version=1,"
                        + "capability_id=:capabilityId,capability_version=1 "
                        + "where id=:id and capability_id is null")
                .param("connectionId", MOCK_CONNECTION)
                .param("capabilityId", "IMAGE_GENERATION".equals(task.kind())
                        ? MOCK_IMAGE : MOCK_VIDEO)
                .param("id", task.id()).update();
    }

    private void bindHistoricalTask(OldTask task, OldAttempt attempt, UUID connectionId,
            int connectionVersion, UUID capabilityId) {
        jdbc.sql("update task set connection_id=:connectionId,"
                        + "connection_version=:connectionVersion,capability_id=:capabilityId,"
                        + "capability_version=1 where id=:id and capability_id is null")
                .param("connectionId", connectionId)
                .param("connectionVersion", connectionVersion)
                .param("capabilityId", capabilityId).param("id", task.id()).update();
        jdbc.sql("update provider_attempt set connection_id=:connectionId,"
                        + "connection_version=:connectionVersion,capability_id=:capabilityId,"
                        + "capability_version=1 where id=:id and capability_id is null")
                .param("connectionId", connectionId)
                .param("connectionVersion", connectionVersion)
                .param("capabilityId", capabilityId).param("id", attempt.id()).update();
    }

    private void blockUnresolved(OldTask task) {
        events.recordChange(task.ownerId(), task.projectId(), () -> {
            int changed = jdbc.sql("update task set status='BLOCKED',"
                            + "error_code='LEGACY_UNRESOLVED',lease_owner=null,lease_until=null,"
                            + "version=version+1,updated_at=:now where id=:id "
                            + "and version=:version and status=:status")
                    .param("id", task.id()).param("version", task.version())
                    .param("status", task.status())
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
            if (changed == 0) return ProjectEventService.Change.unchanged(false);
            ObjectNode payload = mapper.createObjectNode();
            payload.put("taskId", task.id().toString());
            payload.put("status", "BLOCKED");
            payload.put("cancelRequested", false);
            payload.put("possibleExternalCost", task.providerRequestId() != null);
            return ProjectEventService.Change.changed(true,
                    new ProjectEventService.EventDraft("task.status.changed", 1,
                            task.id(), task.version() + 1, payload));
        });
    }

    private OldAttempt latestAttempt(UUID taskId) {
        return jdbc.sql("select id,candidate_request_id,candidate_origin_sha256,"
                        + "provider_request_id from provider_attempt where task_id=:id "
                        + "order by created_at desc,id desc limit 1")
                .param("id", taskId).query((row, index) -> new OldAttempt(
                        row.getObject("id", UUID.class),
                        row.getObject("candidate_request_id", UUID.class),
                        row.getString("candidate_origin_sha256"),
                        row.getString("provider_request_id"))).optional().orElse(null);
    }

    private OldOrigin originFor(int configVersion) {
        return jdbc.sql("select config_version,origin,origin_sha256 "
                        + "from comfyui_config_version where config_version=:version")
                .param("version", configVersion)
                .query((row, index) -> new OldOrigin(row.getInt("config_version"),
                        row.getString("origin"), row.getString("origin_sha256")))
                .optional().orElse(null);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record OldOrigin(int configVersion, String origin, String sha256) {}
    private record Imported(UUID connectionId, UUID imageId, UUID videoId,
            Map<Integer, Integer> versions) {}
    private record OldPlan(UUID id, UUID projectId, UUID runId, int revision,
            String stage, UUID ownerId) {}
    private record OldTask(UUID id, UUID projectId, UUID ownerId, String kind,
            String status, String providerRequestId, String inputJson, long version) {}
    private record OldAttempt(UUID id, UUID candidateRequestId, String originSha256,
            String providerRequestId) {}
}

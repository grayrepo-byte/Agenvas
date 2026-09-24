package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Coordinates owner checks, immutable revisions, typed references, and current selection. */
@Service
public class ArtifactService {

    private static final int SCHEMA_VERSION = 1;
    private static final Duration CREATE_KEY_RETENTION = Duration.ofHours(24);

    private final ProjectService projects;
    private final ArtifactRepository artifacts;
    private final ArtifactContentValidator contentValidator;
    private final AssetService assets;
    private final ProjectEventService events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ArtifactService(
            ProjectService projects,
            ArtifactRepository artifacts,
            ArtifactContentValidator contentValidator,
            AssetService assets,
            ProjectEventService events,
            ObjectMapper objectMapper,
            Clock clock) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.contentValidator = contentValidator;
        this.assets = assets;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Creates a stable identity and its first immutable user-authored version atomically. */
    @Transactional
    public ArtifactView create(
            UUID ownerId,
            UUID projectId,
            Artifact.Kind kind,
            String requestedTitle,
            JsonNode content) {
        return events.recordChange(ownerId, projectId, () -> {
                    ArtifactView created = createLocked(ownerId, projectId, kind, requestedTitle,
                            content, ArtifactVersion.CreatedByKind.USER, null, false);
                    return ProjectEventService.Change.changed(
                            created, artifactEvent("artifact.created", created));
                })
                .value();
    }

    /** Atomically creates or replays one manual Artifact without repeating its first version. */
    @Transactional
    public CreateResult createIdempotent(UUID ownerId, UUID projectId,
            Artifact.Kind kind, String requestedTitle, JsonNode content,
            String requestedKey) {
        String key = requestedKey == null ? "" : requestedKey.trim();
        if (key.isEmpty() || key.length() > 200) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "幂等键无效", "Idempotency-Key 必须为 1 至 200 个字符。", false);
        }
        String title = validateTitle(requestedTitle);
        if (kind == null || content == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "产物内容无效", "必须提供产物类型和完整内容。", false);
        }
        String scope = "project:" + projectId + ":create-artifact";
        String requestHash = sha256(kind.name() + "\n" + title + "\n" + content);
        Instant now = clock.instant();
        if (!artifacts.reserveCreateKey(ownerId, scope, key, requestHash,
                now.plus(CREATE_KEY_RETENTION), now)) {
            ArtifactRepository.CreateKey existing = artifacts.findCreateKey(ownerId, scope, key)
                    .orElseThrow(this::createInProgress);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "幂等键已用于不同请求", "请为不同的产物内容使用新的 Idempotency-Key。", false);
            }
            if (!"COMPLETED".equals(existing.state()) || existing.artifactId() == null
                    || existing.responseJson() == null) {
                throw createInProgress();
            }
            // Re-authorize the nested resource, but return the original response snapshot.
            get(ownerId, projectId, existing.artifactId());
            return new CreateResult(objectMapper.readValue(existing.responseJson(),
                    ArtifactView.class), true);
        }
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView created = createLocked(ownerId, projectId, kind, title, content,
                    ArtifactVersion.CreatedByKind.USER, null, false);
            if (!artifacts.completeCreateKey(ownerId, scope, key, requestHash,
                    created.artifact().id(), objectMapper.writeValueAsString(created), now)) {
                throw new IllegalStateException("Failed to complete Artifact creation key");
            }
            return ProjectEventService.Change.changed(new CreateResult(created, false),
                    artifactEvent("artifact.created", created));
        }).value();
    }

    private ApiProblemException createInProgress() {
        return new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                "相同请求正在处理", "请稍后使用相同 Idempotency-Key 重试。", true);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }

    /** Creates an Agent-authored artifact through the same validation and event transaction. */
    @Transactional
    public ArtifactView createFromAgent(UUID ownerId, UUID projectId, UUID runId,
            Artifact.Kind kind, String requestedTitle, JsonNode content) {
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView created = createLocked(ownerId, projectId, kind, requestedTitle,
                    content, ArtifactVersion.CreatedByKind.AGENT, runId, false);
            return ProjectEventService.Change.changed(created,
                    artifactEvent("artifact.created", created));
        }).value();
    }

    /** Materializes a Task output inside its caller's locked project-change transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ArtifactView createFromTaskWithinChange(UUID ownerId, UUID projectId, UUID runId,
            Artifact.Kind kind, String requestedTitle, JsonNode content) {
        return createLocked(ownerId, projectId, kind, requestedTitle, content,
                ArtifactVersion.CreatedByKind.TASK, runId, true);
    }

    private ArtifactView createLocked(
            UUID ownerId,
            UUID projectId,
            Artifact.Kind kind,
            String requestedTitle,
            JsonNode content,
            ArtifactVersion.CreatedByKind createdByKind,
            UUID runId,
            boolean taskOutput) {
        if (taskOutput) {
            // Accepted external work must remain archival even if the user archived the project.
            projects.get(ownerId, projectId);
        } else {
            projects.requireActiveProject(ownerId, projectId);
        }
        String title = validateTitle(requestedTitle);
        List<ArtifactVersion.InputReference> references =
                contentValidator.validate(kind, content);
        requireUploadAuthorship(kind, content, createdByKind);
        validateReferences(projectId, references);
        validateMediaAsset(ownerId, projectId, kind, content);
        Instant now = clock.instant();
        UUID artifactId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Artifact artifact = new Artifact(
                artifactId,
                projectId,
                kind,
                title,
                null,
                null,
                0,
                now,
                now);
        ArtifactVersion version = new ArtifactVersion(
                versionId,
                projectId,
                artifactId,
                1,
                SCHEMA_VERSION,
                content.deepCopy(),
                references,
                createdByKind,
                runId,
                now);
        artifacts.createArtifact(artifact);
        artifacts.appendVersion(version);
        artifacts.setInitialCurrentVersion(artifactId, versionId, now);
        return new ArtifactView(
                requireArtifact(ownerId, projectId, artifactId), version);
    }

    /** Returns an artifact and its currently selected immutable revision. */
    @Transactional(readOnly = true)
    public ArtifactView get(UUID ownerId, UUID projectId, UUID artifactId) {
        projects.get(ownerId, projectId);
        Artifact artifact = requireArtifact(ownerId, projectId, artifactId);
        ArtifactVersion current = artifacts.findVersion(
                        projectId, artifactId, artifact.currentVersionId())
                .orElseThrow(() -> new IllegalStateException("Artifact current version is missing"));
        return new ArtifactView(artifact, current);
    }

    /** Appends a complete revision and atomically selects it when the caller version matches. */
    @Transactional
    public ArtifactView revise(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            long expectedArtifactVersion,
            String requestedTitle,
            JsonNode content) {
        return events.recordChange(ownerId, projectId, () -> {
                    ArtifactView revised = reviseLocked(
                            ownerId,
                            projectId,
                            artifactId,
                            expectedArtifactVersion,
                            requestedTitle,
                            content, ArtifactVersion.CreatedByKind.USER, null, null);
                    return ProjectEventService.Change.changed(
                            revised, artifactEvent("artifact.version.created", revised));
                })
                .value();
    }

    /** Revises only a Run-visible creative artifact; the model cannot select another project. */
    @Transactional
    public ArtifactView reviseFromAgent(UUID ownerId, UUID projectId, UUID runId,
            JsonNode contextSnapshot, UUID artifactId, long expectedArtifactVersion,
            String requestedTitle, JsonNode content) {
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView revised = reviseLocked(ownerId, projectId, artifactId,
                    expectedArtifactVersion, requestedTitle, content,
                    ArtifactVersion.CreatedByKind.AGENT, runId, contextSnapshot);
            return ProjectEventService.Change.changed(revised,
                    artifactEvent("artifact.version.created", revised));
        }).value();
    }

    private ArtifactView reviseLocked(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            long expectedArtifactVersion,
            String requestedTitle,
            JsonNode content, ArtifactVersion.CreatedByKind author,
            UUID runId, JsonNode contextSnapshot) {
        projects.requireActiveProject(ownerId, projectId);
        Artifact current = artifacts.findForUpdate(ownerId, projectId, artifactId)
                .orElseThrow(this::notFound);
        if (author == ArtifactVersion.CreatedByKind.AGENT) {
            ArtifactVersion visible = requireAgentVisibleVersion(ownerId, projectId, runId,
                    current.currentVersionId(), contextSnapshot);
            if (!runId.equals(visible.runId())) {
                boolean unchangedBinding = false;
                JsonNode bindings = contextSnapshot == null ? null
                        : contextSnapshot.path("bindings");
                if (bindings != null && bindings.isArray()) {
                    for (JsonNode binding : bindings) {
                        if (artifactId.toString().equals(binding.path("artifactId").asText())
                                && current.currentVersionId().toString().equals(
                                        binding.path("selectedVersionId").asText())
                                && binding.path("expectedVersion").canConvertToLong()
                                && binding.path("expectedVersion").longValue()
                                        == current.version()) {
                            unchangedBinding = true;
                            break;
                        }
                    }
                }
                if (!unchangedBinding) {
                    throw versionConflict();
                }
            }
            if (current.kind() == Artifact.Kind.IMAGE || current.kind() == Artifact.Kind.VIDEO) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                        "工具参数无效", "Agent 不能修改已归档媒体内容。", false);
            }
            if (current.kind() == Artifact.Kind.SHOT) {
                ArtifactVersion selected = artifacts.findVersion(projectId, artifactId,
                        current.currentVersionId()).orElseThrow(this::notFound);
                if (selected.content().has("selectedImageVersionId")
                        || selected.content().has("selectedVideoVersionId")
                        || (content != null && (content.has("selectedImageVersionId")
                                || content.has("selectedVideoVersionId")))) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST,
                            "TOOL_ARGUMENT_INVALID", "工具参数无效",
                            "Agent 不能改写人工选定的镜头媒体；请使用局部重做流程。", false);
                }
            }
        }
        requireEditable(current);
        if (current.version() != expectedArtifactVersion) {
            throw versionConflict();
        }
        String title = requestedTitle == null ? current.title() : validateTitle(requestedTitle);
        List<ArtifactVersion.InputReference> references =
                contentValidator.validate(current.kind(), content);
        requireUploadAuthorship(current.kind(), content, author);
        validateReferences(projectId, references);
        if (author == ArtifactVersion.CreatedByKind.AGENT) {
            for (ArtifactVersion.InputReference reference : references) {
                requireAgentVisibleVersion(ownerId, projectId, runId,
                        reference.versionId(), contextSnapshot);
            }
        }
        validateMediaAsset(ownerId, projectId, current.kind(), content);
        Instant now = clock.instant();
        ArtifactVersion revision = new ArtifactVersion(
                UUID.randomUUID(),
                projectId,
                artifactId,
                artifacts.nextVersionNo(projectId, artifactId),
                SCHEMA_VERSION,
                content.deepCopy(),
                references,
                author,
                runId,
                now);
        artifacts.appendVersion(revision);
        if (!artifacts.selectVersion(
                ownerId,
                projectId,
                artifactId,
                expectedArtifactVersion,
                revision.id(),
                title,
                now)) {
            throw versionConflict();
        }
        return new ArtifactView(
                requireArtifact(ownerId, projectId, artifactId), revision);
    }

    /** Lists all immutable versions after checking the nested resource owner boundary. */
    @Transactional(readOnly = true)
    public List<ArtifactVersion> listVersions(
            UUID ownerId, UUID projectId, UUID artifactId) {
        projects.get(ownerId, projectId);
        requireArtifact(ownerId, projectId, artifactId);
        return artifacts.listVersions(projectId, artifactId);
    }

    /** Owner-scoped bulk reads for the redacted project manifest transaction. */
    @Transactional(readOnly = true)
    public ProjectExportVersions listProjectExport(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return new ProjectExportVersions(
                artifacts.listProjectArtifacts(ownerId, projectId),
                artifacts.listProjectVersions(projectId));
    }

    /** Stable identities and immutable versions; callers must explicitly whitelist content. */
    public record ProjectExportVersions(List<Artifact> artifacts,
            List<ArtifactVersion> versions) {}

    /** Resolves an exact owner-scoped historical version for binding and task snapshots. */
    @Transactional(readOnly = true)
    public ArtifactVersion requireVersion(
            UUID ownerId, UUID projectId, UUID artifactId, UUID versionId) {
        projects.get(ownerId, projectId);
        requireArtifact(ownerId, projectId, artifactId);
        return artifacts.findVersion(projectId, artifactId, versionId)
                .orElseThrow(this::notFound);
    }

    /** Resolves only an already pinned, same-project image version for a trusted media Task. */
    @Transactional(readOnly = true)
    public ArtifactVersion requireImageVersionForTask(UUID ownerId, UUID projectId,
            UUID versionId) {
        projects.get(ownerId, projectId);
        ArtifactRepository.VersionTarget target = artifacts.findVersionTarget(projectId, versionId)
                .orElseThrow(this::notFound);
        if (target.kind() != Artifact.Kind.IMAGE) throw notFound();
        return artifacts.findVersion(projectId, target.artifactId(), versionId)
                .orElseThrow(this::notFound);
    }

    /** Limits Agent references to pinned explicit inputs or output versions from this Run. */
    @Transactional(readOnly = true)
    public ArtifactVersion requireAgentVisibleVersion(UUID ownerId, UUID projectId,
            UUID runId, UUID versionId, JsonNode contextSnapshot) {
        projects.get(ownerId, projectId);
        ArtifactRepository.VersionTarget target = artifacts.findVersionTarget(projectId, versionId)
                .orElseThrow(this::notFound);
        ArtifactVersion version = artifacts.findVersion(projectId, target.artifactId(), versionId)
                .orElseThrow(this::notFound);
        boolean createdInRun = runId.equals(version.runId())
                && version.createdByKind() != ArtifactVersion.CreatedByKind.USER;
        JsonNode bindings = contextSnapshot == null ? null : contextSnapshot.get("bindings");
        boolean explicitlyBound = false;
        if (bindings != null && bindings.isArray()) {
            for (JsonNode binding : bindings) {
                if (versionId.toString().equals(binding.path("selectedVersionId").asText())
                        && target.artifactId().toString()
                                .equals(binding.path("artifactId").asText())) {
                    explicitlyBound = true;
                    break;
                }
            }
        }
        if (!createdInRun && !explicitlyBound) {
            throw new ApiProblemException(HttpStatus.FORBIDDEN, "INPUT_SCOPE_DENIED",
                    "输入超出授权范围", "Agent 只能引用本轮输出或开始时显式绑定的版本。", false);
        }
        return version;
    }

    /** Selects an existing historical version with optimistic concurrency and replay safety. */
    @Transactional
    public ArtifactView selectVersion(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            UUID versionId,
            long expectedArtifactVersion) {
        return events.recordChange(ownerId, projectId, () -> {
                    ArtifactView before = get(ownerId, projectId, artifactId);
                    ArtifactView selected = selectVersionLocked(
                            ownerId, projectId, artifactId, versionId, expectedArtifactVersion);
                    if (before.artifact().version() == selected.artifact().version()) {
                        return ProjectEventService.Change.unchanged(selected);
                    }
                    return ProjectEventService.Change.changed(
                            selected,
                            artifactEvent("artifact.current_version.changed", selected));
                })
                .value();
    }

    /**
     * Appends one Task-owned immutable version while the caller holds the project event lock.
     * Selection is conditional on both the pinned current-version ID and Artifact CAS version;
     * a canceled Run or intervening edit leaves the new version in history only.
     */
    public TaskVersionResult appendTaskVersionWithinChange(UUID ownerId, UUID projectId,
            UUID artifactId, UUID runId, UUID expectedCurrentVersionId,
            long expectedArtifactVersion, JsonNode content, boolean allowSelection) {
        Artifact current = artifacts.findForUpdate(ownerId, projectId, artifactId)
                .orElseThrow(this::notFound);
        List<ArtifactVersion.InputReference> references =
                contentValidator.validate(current.kind(), content);
        requireUploadAuthorship(current.kind(), content, ArtifactVersion.CreatedByKind.TASK);
        validateReferences(projectId, references);
        validateMediaAsset(ownerId, projectId, current.kind(), content);
        Instant now = clock.instant();
        ArtifactVersion revision = new ArtifactVersion(UUID.randomUUID(), projectId,
                artifactId, artifacts.nextVersionNo(projectId, artifactId), SCHEMA_VERSION,
                content.deepCopy(), references, ArtifactVersion.CreatedByKind.TASK, runId, now);
        artifacts.appendVersion(revision);
        boolean selected = allowSelection
                && current.archivedAt() == null
                && current.version() == expectedArtifactVersion
                && current.currentVersionId().equals(expectedCurrentVersionId)
                && artifacts.selectVersion(ownerId, projectId, artifactId,
                        expectedArtifactVersion, revision.id(), current.title(), now);
        return new TaskVersionResult(revision.id(), selected);
    }

    /** Pins the approved keyframe and completed video on a still-current shot as one new version. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ArtifactView selectTaskVideoOnShotWithinChange(UUID ownerId, UUID projectId,
            UUID runId, UUID shotId, UUID expectedShotVersionId,
            UUID imageVersionId, UUID videoVersionId) {
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView current = get(ownerId, projectId, shotId);
            if (current.artifact().kind() != Artifact.Kind.SHOT
                    || current.artifact().archivedAt() != null
                    || !current.currentVersion().id().equals(expectedShotVersionId)
                    || !(current.currentVersion().content() instanceof ObjectNode shotContent)) {
                throw versionConflict();
            }
            ObjectNode selected = shotContent.deepCopy();
            selected.put("selectedImageVersionId", imageVersionId.toString());
            selected.put("selectedVideoVersionId", videoVersionId.toString());
            ArtifactView revised = reviseLocked(ownerId, projectId, shotId,
                    current.artifact().version(), null, selected,
                    ArtifactVersion.CreatedByKind.TASK, runId, null);
            return ProjectEventService.Change.changed(revised,
                    artifactEvent("artifact.version.created", revised));
        }).value();
    }

    /** Outcome of a generated immutable revision and its conditional current selection. */
    public record TaskVersionResult(UUID versionId, boolean selected) {}

    private ArtifactView selectVersionLocked(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            UUID versionId,
            long expectedArtifactVersion) {
        projects.requireActiveProject(ownerId, projectId);
        Artifact current = artifacts.findForUpdate(ownerId, projectId, artifactId)
                .orElseThrow(this::notFound);
        requireEditable(current);
        ArtifactVersion target = artifacts.findVersion(projectId, artifactId, versionId)
                .orElseThrow(this::notFound);
        if (current.currentVersionId().equals(versionId)
                && current.version() == expectedArtifactVersion) {
            return new ArtifactView(current, target);
        }
        if (current.currentVersionId().equals(versionId)
                && current.version() == expectedArtifactVersion + 1) {
            return new ArtifactView(current, target);
        }
        if (current.version() != expectedArtifactVersion
                || !artifacts.selectVersion(
                        ownerId,
                        projectId,
                        artifactId,
                        expectedArtifactVersion,
                        versionId,
                        current.title(),
                        clock.instant())) {
            throw versionConflict();
        }
        return new ArtifactView(
                requireArtifact(ownerId, projectId, artifactId), target);
    }

    private ProjectEventService.EventDraft artifactEvent(String type, ArtifactView view) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("artifactId", view.artifact().id().toString());
        payload.put("currentVersionId", view.currentVersion().id().toString());
        payload.put("kind", view.artifact().kind().name());
        return new ProjectEventService.EventDraft(
                type, 1, view.artifact().id(), view.artifact().version(), payload);
    }

    private void validateReferences(
            UUID projectId, List<ArtifactVersion.InputReference> references) {
        Set<UUID> ids = new LinkedHashSet<>();
        references.forEach(reference -> ids.add(reference.versionId()));
        Map<UUID, ArtifactRepository.VersionTarget> targets =
                artifacts.findVersionTargets(projectId, ids);
        for (ArtifactVersion.InputReference reference : references) {
            ArtifactRepository.VersionTarget target = targets.get(reference.versionId());
            if (target == null || target.kind() != reference.expectedKind()) {
                throw new ApiProblemException(
                        HttpStatus.BAD_REQUEST,
                        "ARTIFACT_REFERENCE_INVALID",
                        "产物引用无效",
                        "引用版本不存在、属于其他项目或类型不匹配。",
                        false);
            }
        }
    }

    /** Schema checks shape; this check binds media identity to real, private project bytes. */
    private void validateMediaAsset(UUID ownerId, UUID projectId, Artifact.Kind kind,
            JsonNode content) {
        if (kind == Artifact.Kind.IMAGE || kind == Artifact.Kind.VIDEO) {
            Asset.MediaKind mediaKind = kind == Artifact.Kind.IMAGE
                    ? Asset.MediaKind.IMAGE : Asset.MediaKind.VIDEO;
            assets.requireReadyMedia(ownerId, projectId,
                    UUID.fromString(content.path("assetId").asText()), mediaKind);
        }
    }

    /** User-upload provenance cannot be asserted by a model or generation Task. */
    private void requireUploadAuthorship(Artifact.Kind kind, JsonNode content,
            ArtifactVersion.CreatedByKind author) {
        if (kind == Artifact.Kind.IMAGE
                && "UPLOAD".equals(content.path("sourceType").asText())
                && author != ArtifactVersion.CreatedByKind.USER) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ARTIFACT_ORIGIN_INVALID", "图片来源无效",
                    "用户上传图片只能由用户操作创建版本。", false);
        }
    }

    private Artifact requireArtifact(UUID ownerId, UUID projectId, UUID artifactId) {
        return artifacts.find(ownerId, projectId, artifactId).orElseThrow(this::notFound);
    }

    private void requireEditable(Artifact artifact) {
        if (artifact.archivedAt() != null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ARTIFACT_ARCHIVED",
                    "产物已归档",
                    "归档产物不能创建或选择新版本。",
                    false);
        }
    }

    private String validateTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "请求参数无效",
                    "产物标题必须为 1 至 160 个字符。",
                    false);
        }
        return normalized;
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "产物不存在",
                "产物、版本或项目不存在，或当前用户无权访问。",
                false);
    }

    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ARTIFACT_VERSION_CONFLICT",
                "内容已更新",
                "目标产物已被修改，请读取最新版本后重新提交。",
                false);
    }

    /** Stable identity paired with the selected immutable content revision. */
    public record ArtifactView(Artifact artifact, ArtifactVersion currentVersion) {}

    /** Manual create outcome; replay uses the original response snapshot. */
    public record CreateResult(ArtifactView view, boolean replayed) {}
}

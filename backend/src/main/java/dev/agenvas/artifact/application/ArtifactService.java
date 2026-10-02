package dev.agenvas.artifact.application;

import dev.agenvas.shared.crypto.Sha256;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.idempotency.IdempotencyState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

/** 校验产物权限、正文引用和媒体资产，并协调不可变版本与当前版本选择。 */
@Service
public class ArtifactService {

    /** 所有保留产物类型都从第一版正文开始。 */
    private static final int INITIAL_SCHEMA_VERSION = 1;
    /** 手工创建产物的幂等命令保留时长。 */
    private static final Duration CREATE_KEY_RETENTION = Duration.ofHours(24);

    /** 与每次 Run 的精确输入上限一致；显式输入在合并时优先。 */
    private static final int MAX_CONVERSATION_INPUTS = 40;

    /** 确认项目归属、活跃状态和资源可见性。 */
    private final ProjectService projects;
    /** 保存稳定产物身份、版本和当前选择。 */
    private final ArtifactRepository artifacts;
    /** 按产物类型校验正文并提取版本引用。 */
    private final ArtifactContentValidator contentValidator;
    /** 确认媒体正文引用的是当前项目内已就绪文件。 */
    private final AssetService assets;
    /** 将产物及版本变化与项目事件同事务提交。 */
    private final ProjectEventService events;
    /** 构造不可变正文副本和安全事件负载。 */
    private final ObjectMapper objectMapper;
    /** 生成版本创建及幂等过期时间。 */
    private final Clock clock;

    /** 组装产物校验、媒体引用鉴权、不可变版本写入与事件事务。
     * @param projects 校验所有者和项目状态
     * @param artifacts 读取并创建产物及内容版本
     * @param contentValidator 验证内容结构与媒体引用
     * @param assets 校验素材归属并读取已归档媒体
     * @param events 与产物变更同事务追加事件
     * @param objectMapper 规范化内容并生成事件负载
     * @param clock 提供幂等过期时间和版本创建时间
     */
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

    /**
     * 原子创建产物身份和首个用户版本；正文 Schema、引用版本及媒体文件均需通过校验。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 产物所属项目
     * @param kind 正文必须符合的产物类型
     * @param requestedTitle 展示标题，去除首尾空白后限 160 字符
     * @param content 首个用户版本的完整 JSON 正文
     * @return 新产物和其当前首个版本
     */
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

    /** Trusted library boundary. Ordinary artifact writes cannot assert this provenance. */
    @Transactional
    public ArtifactView createLibraryImport(UUID ownerId, UUID projectId, Artifact.Kind kind,
            String title, JsonNode textContent, UUID assetId) {
        JsonNode content = kind == Artifact.Kind.TEXT ? textContent
                : objectMapper.createObjectNode().put("sourceType", ArtifactVersion.MediaSourceType.LIBRARY_IMPORT.name())
                        .put("assetId", assetId.toString());
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView created = createLocked(ownerId, projectId, kind, title, content,
                    ArtifactVersion.CreatedByKind.USER, null, true);
            return ProjectEventService.Change.changed(created, artifactEvent("artifact.created", created));
        }).value();
    }

    /** Trusted Skill installation only; ordinary writes cannot forge this origin. */
    @Transactional
    public ArtifactView createSkillImport(UUID ownerId, UUID projectId, String title, UUID assetId) {
        JsonNode content = objectMapper.createObjectNode().put("sourceType", ArtifactVersion.MediaSourceType.SKILL_IMPORT.name())
                .put("assetId", assetId.toString());
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView created = createLocked(ownerId, projectId, Artifact.Kind.IMAGE, title, content,
                    ArtifactVersion.CreatedByKind.USER, null, true);
            return ProjectEventService.Change.changed(created, artifactEvent("artifact.created", created));
        }).value();
    }

    /**
     * 使用项目内幂等键创建手工产物；同键同载荷返回原响应快照，同键异载荷返回冲突。
     * 重放时仍重新鉴权原产物，不会再次追加首个版本。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 产物所属项目
     * @param kind 产物类型
     * @param requestedTitle 用户提交的标题
     * @param content 用户提交的完整正文
     * @param requestedKey 客户端 Idempotency-Key，限 1 至 200 字符
     * @return 原创建结果，并标明是否重放
     */
    @Transactional
    public CreateResult createIdempotent(UUID ownerId, UUID projectId,
            Artifact.Kind kind, String requestedTitle, JsonNode content,
            String requestedKey) {
        JsonNode normalizedContent = content != null && content.isNull() ? null : content;
        String key = requestedKey == null ? "" : requestedKey.trim();
        if (key.isEmpty() || key.length() > 200) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.artifact-service.idempotent-key-is-invalid"), ApiMessage.of("api.artifact-service.idempotency-key-must-be-1-to-200-characters"), false);
        }
        String title = validateTitle(requestedTitle);
        if (kind == null || (normalizedContent == null && !isMediaKind(kind))) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.artifact-content-validator.product-content-is-invalid"), ApiMessage.of("api.artifact-service.product-type-and-complete-content-must-be-provided"), false);
        }
        String scope = "project:" + projectId + ":create-artifact";
        String requestHash = Sha256.hex(kind.name() + "\n" + title + "\n" + normalizedContent);
        Instant now = clock.instant();
        if (!artifacts.reserveCreateKey(ownerId, scope, key, requestHash,
                now.plus(CREATE_KEY_RETENTION), now)) {
            ArtifactRepository.CreateKey existing = artifacts.findCreateKey(ownerId, scope, key)
                    .orElseThrow(this::createInProgress);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        ApiMessage.of("api.artifact-service.idempotent-keys-have-been-used-for-different-requests"), ApiMessage.of("api.artifact-service.please-use-new-idempotency-key-for-different-product-content"), false);
            }
            if (existing.state() != IdempotencyState.COMPLETED || existing.artifactId() == null
                    || existing.responseJson() == null) {
                throw createInProgress();
            }
            // 重放仍重新鉴权嵌套资源，但返回首次提交时的响应快照。
            get(ownerId, projectId, existing.artifactId());
            return new CreateResult(objectMapper.readValue(existing.responseJson(),
                    ArtifactView.class), true);
        }
        return events.recordChange(ownerId, projectId, () -> {
            ArtifactView created = createLocked(ownerId, projectId, kind, title, normalizedContent,
                    ArtifactVersion.CreatedByKind.USER, null, false);
            String responseJson = objectMapper.writeValueAsString(created);
            if (!artifacts.completeCreateKey(ownerId, scope, key, requestHash,
                    created.artifact().id(), responseJson, now)) {
                throw new IllegalStateException("Failed to complete Artifact creation key");
            }
            // PostgreSQL JSONB normalizes object field order. Read the persisted snapshot back so
            // the first response is byte-semantically equivalent to every later replay.
            ArtifactView snapshot = artifacts.findCreateKey(ownerId, scope, key)
                    .map(ArtifactRepository.CreateKey::responseJson)
                    .map(json -> objectMapper.readValue(json, ArtifactView.class))
                    .orElseThrow(() -> new IllegalStateException(
                            "Completed Artifact creation key is missing"));
            return ProjectEventService.Change.changed(new CreateResult(snapshot, false),
                    artifactEvent("artifact.created", snapshot));
        }).value();
    }

    /** 同一创建键已预留但尚未完成时返回可重试冲突。 */
    private ApiProblemException createInProgress() {
        return new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                ApiMessage.of("api.artifact-service.the-same-request-is-being-processed"), ApiMessage.of("api.artifact-service.please-try-again-later-with-the-same-idempotency-key"), true);
    }

    /** Agent 通过已鉴权 Run 创建新产物；复用用户创建路径的正文、引用和媒体校验。 */
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

    /** 统一创建边界；只允许在活动项目内创建产物。 */
    private ArtifactView createLocked(UUID ownerId, UUID projectId, Artifact.Kind kind,
            String requestedTitle, JsonNode content, ArtifactVersion.CreatedByKind createdByKind,
            UUID runId, boolean trustedImport) {
        if (content != null && content.isNull()) content = null;
        projects.requireActiveProject(ownerId, projectId);
        String title = validateTitle(requestedTitle);
        if (content == null && isMediaKind(kind)) {
            if (createdByKind != ArtifactVersion.CreatedByKind.USER) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        ApiMessage.of("api.artifact-service.media-text-is-invalid"), ApiMessage.of("api.artifact-service.media-artifacts-created-by-tasks-and-agents-must-contain-archived"), false);
            }
            Instant now = clock.instant();
            Artifact empty = new Artifact(UUID.randomUUID(), projectId, kind, title,
                    null, null, 0, now, now);
            artifacts.createArtifact(empty);
            return new ArtifactView(empty, null);
        }
        List<ArtifactVersion.InputReference> references =
                contentValidator.validate(kind, content);
        if (!trustedImport) requireUploadAuthorship(kind, content, createdByKind);
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
                INITIAL_SCHEMA_VERSION,
                null,
                null,
                content.deepCopy(),
                references,
                createdByKind,
                runId,
                now);
        artifacts.createArtifact(artifact);
        artifacts.appendVersion(version);
        artifacts.setInitialResourceDefaultVersion(artifactId, versionId, now);
        return new ArtifactView(
                requireArtifact(ownerId, projectId, artifactId), version);
    }

    /** 读取稳定产物身份及其资源默认不可变版本。 */
    @Transactional(readOnly = true)
    public ArtifactView get(UUID ownerId, UUID projectId, UUID artifactId) {
        projects.get(ownerId, projectId);
        Artifact artifact = requireArtifact(ownerId, projectId, artifactId);
        if (artifact.resourceDefaultVersionId() == null && isMediaKind(artifact.kind())) {
            return new ArtifactView(artifact, null);
        }
        ArtifactVersion current = artifacts.findVersion(
                        projectId, artifactId, artifact.resourceDefaultVersionId())
                .orElseThrow(() -> new IllegalStateException("Artifact resource default is missing"));
        return new ArtifactView(artifact, current);
    }

    /** List stable project resources, including media cards removed from the canvas. */
    @Transactional(readOnly = true)
    public List<ArtifactView> listProject(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return artifacts.listProjectArtifacts(ownerId, projectId).stream()
                .filter(artifact -> artifact.archivedAt() == null)
                .map(artifact -> get(ownerId, projectId, artifact.id()))
                .toList();
    }

    /**
     * 追加完整的新版本，并以产物 CAS 版本原子切换当前选择；旧版本永不覆盖。
     *
     * @param ownerId 经认证的项目所有者
     * @param projectId 产物所属项目
     * @param artifactId 要追加版本的稳定产物 ID
     * @param expectedArtifactVersion 调用方读取到的产物版本，防止覆盖并发编辑
     * @param requestedTitle 新标题；仅为 null 时保留当前标题，空白文本会校验失败
     * @param content 新版本的完整正文
     * @return 已追加并选中的新版本
     */
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

    /** Agent 只能改写本 Run 显式绑定或本轮创建的产物，引用版本也须处于可见范围。 */
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

    /** 追加版本前核对 Run 可见范围、人工媒体选择和预期产物版本。 */
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
                    current.resourceDefaultVersionId(), contextSnapshot);
            if (!runId.equals(visible.runId())) {
                boolean unchangedBinding = false;
                JsonNode bindings = contextSnapshot == null ? null
                        : contextSnapshot.path("bindings");
                if (bindings != null && bindings.isArray()) {
                    for (JsonNode binding : bindings) {
                        if (artifactId.toString().equals(binding.path("artifactId").asText())
                                && current.resourceDefaultVersionId().toString().equals(
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
            if (current.kind() != Artifact.Kind.TEXT) {
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                        ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), ApiMessage.of("api.artifact-service.agents-cannot-modify-archived-media-content"), false);
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
                INITIAL_SCHEMA_VERSION,
                current.resourceDefaultVersionId(),
                null,
                content.deepCopy(),
                references,
                author,
                runId,
                now);
        artifacts.appendVersion(revision);
        if (!artifacts.setResourceDefaultVersion(
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

    /** 先核验产物所属项目，再列出该产物所有不可变历史版本。 */
    @Transactional(readOnly = true)
    public List<ArtifactVersion> listVersions(
            UUID ownerId, UUID projectId, UUID artifactId) {
        projects.get(ownerId, projectId);
        requireArtifact(ownerId, projectId, artifactId);
        return artifacts.listVersions(projectId, artifactId);
    }

    /** 为项目导出清单读取已鉴权项目的产物和版本；导出层仍须显式筛选正文。 */
    @Transactional(readOnly = true)
    public ProjectExportVersions listProjectExport(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return new ProjectExportVersions(
                artifacts.listProjectArtifacts(ownerId, projectId),
                artifacts.listProjectVersions(projectId));
    }

    /**
     * 项目导出所需的稳定身份与不可变版本集合；调用方必须按白名单选择内容。
     *
     * @param artifacts 项目内已鉴权的稳定产物身份
     * @param versions 对应的不可变版本正文及输入引用
     */
    public record ProjectExportVersions(List<Artifact> artifacts,
            List<ArtifactVersion> versions) {}

    /** 解析所有者可访问的精确历史版本，供 Agent 绑定和任务输入快照使用。 */
    @Transactional(readOnly = true)
    public ArtifactVersion requireVersion(
            UUID ownerId, UUID projectId, UUID artifactId, UUID versionId) {
        projects.get(ownerId, projectId);
        requireArtifact(ownerId, projectId, artifactId);
        return artifacts.findVersion(projectId, artifactId, versionId)
                .orElseThrow(this::notFound);
    }

    /** Resolves an exact media version within the authenticated project, never a mutable default. */
    @Transactional(readOnly = true)
    public ArtifactVersion requireMediaVersionForTask(UUID ownerId, UUID projectId, UUID versionId, Artifact.Kind kind) {
        projects.get(ownerId, projectId);
        var target = artifacts.findVersionTarget(projectId, versionId).orElseThrow(this::notFound);
        if (target.kind() != kind || kind == Artifact.Kind.TEXT) throw notFound();
        return artifacts.findVersion(projectId, target.artifactId(), versionId).orElseThrow(this::notFound);
    }

    /** 只解析同项目图片版本，供可信媒体任务读取已固定的参考图。 */
    public ArtifactVersion requireImageVersionForTask(UUID ownerId, UUID projectId,
            UUID versionId) {
        return requireMediaVersionForTask(ownerId, projectId, versionId, Artifact.Kind.IMAGE);
    }

    /**
     * 将同会话已终结 Run 的当前选用输出投影为下一轮的精确输入。
     * 调用方须先按会话筛选历史 Run；此方法不接受来自模型或客户端的 Run 集合。
     * 之后任何人工修订都会由既有版本/CAS 校验拒绝，历史文字本身不授予修改权。
     */
    @Transactional(readOnly = true)
    public List<ConversationInput> conversationInputs(UUID ownerId, UUID projectId,
            List<UUID> authorizedPriorRunIds) {
        projects.get(ownerId, projectId);
        return artifacts.listSelectedRunOutputs(ownerId, projectId,
                authorizedPriorRunIds, MAX_CONVERSATION_INPUTS).stream()
                .map(artifact -> new ConversationInput(artifact.id(), artifact.resourceDefaultVersionId(),
                        artifact.kind(), artifact.title(), artifact.version()))
                .toList();
    }

    /** 会话继承的不可变版本及冻结时的 CAS 版本；不包含媒体字节。 */
    public record ConversationInput(UUID artifactId, UUID selectedVersionId,
            Artifact.Kind kind, String title, Long expectedVersion) {}

    /** 限制 Agent 只能读取创建 Run 时绑定的精确版本（含会话继承）或本轮输出。 */
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
                    ApiMessage.of("api.artifact-service.input-exceeds-authorization-range"), ApiMessage.of("api.artifact-service.agents-can-only-reference-the-output-of-this-round-or"), false);
        }
        return version;
    }

    /** 明确选择资源库默认版本；CanvasItem 的局部选择不经过此入口。 */
    @Transactional
    public ArtifactView setResourceDefaultVersion(
            UUID ownerId,
            UUID projectId,
            UUID artifactId,
            UUID versionId,
            long expectedArtifactVersion) {
        return events.recordChange(ownerId, projectId, () -> {
                    ArtifactView before = get(ownerId, projectId, artifactId);
                    ArtifactView selected = setResourceDefaultVersionLocked(
                            ownerId, projectId, artifactId, versionId, expectedArtifactVersion);
                    if (before.artifact().version() == selected.artifact().version()) {
                        return ProjectEventService.Change.unchanged(selected);
                    }
                    return ProjectEventService.Change.changed(
                            selected,
                            artifactEvent("artifact.resource_default_version.changed", selected));
                })
                .value();
    }

    /** Identity for a mixed-kind result of an already accepted task, including late archived-project results. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Artifact createTaskMediaIdentityWithinChange(UUID ownerId, UUID projectId, Artifact.Kind kind, String title) {
        projects.get(ownerId, projectId);
        if (kind == Artifact.Kind.TEXT) throw new IllegalArgumentException("Media identity required");
        Instant now = clock.instant();
        Artifact artifact = new Artifact(UUID.randomUUID(), projectId, kind, validateTitle(title), null, null, 0, now, now);
        artifacts.createArtifact(artifact);
        events.append(ownerId, projectId, artifactEvent("artifact.created", new ArtifactView(artifact, null)));
        return artifact;
    }

    /**
     * 在调用方项目事件事务中追加任务产物版本。只有任务固定的当前版本 ID 和 CAS 版本都未变化，
     * 且调用方允许选用时才切换当前版本；取消后的结果或用户编辑后的旧输入只保留在历史。
     */
    public TaskVersionResult appendTaskVersionWithinChange(UUID ownerId, UUID projectId,
            UUID artifactId, UUID runId, UUID expectedCurrentVersionId,
            long expectedArtifactVersion, JsonNode content, JsonNode frozenInput,
            boolean allowSelection) {
        Artifact current = artifacts.findForUpdate(ownerId, projectId, artifactId)
                .orElseThrow(this::notFound);
        List<ArtifactVersion.InputReference> references = new java.util.ArrayList<>(
                contentValidator.validate(current.kind(), content));
        if (frozenInput != null) {
            int index = 0;
            for (JsonNode image : frozenInput.path("images")) {
                references.add(new ArtifactVersion.InputReference(
                        UUID.fromString(image.path("versionId").asText()),
                        image.path("role").asText(), index++, Artifact.Kind.IMAGE));
            }
        }
        if (frozenInput != null) for (JsonNode audio : frozenInput.path("audios")) {
            references.add(new ArtifactVersion.InputReference(
                    UUID.fromString(audio.path("versionId").asText()), "AUDIO_REFERENCE",
                    references.size(), Artifact.Kind.AUDIO));
        }
        if (frozenInput != null) for (JsonNode video : frozenInput.path("videos")) {
            references.add(new ArtifactVersion.InputReference(UUID.fromString(video.path("versionId").asText()),
                    "VIDEO_REFERENCE", references.size(), Artifact.Kind.VIDEO));
        }
        requireUploadAuthorship(current.kind(), content, ArtifactVersion.CreatedByKind.TASK);
        validateReferences(projectId, references);
        validateMediaAsset(ownerId, projectId, current.kind(), content);
        Instant now = clock.instant();
        ArtifactVersion revision = new ArtifactVersion(UUID.randomUUID(), projectId,
                artifactId, artifacts.nextVersionNo(projectId, artifactId),
                INITIAL_SCHEMA_VERSION,
                expectedCurrentVersionId,
                frozenInput == null ? null : frozenInput.deepCopy(),
                content.deepCopy(), List.copyOf(references),
                ArtifactVersion.CreatedByKind.TASK, runId, now);
        artifacts.appendVersion(revision);
        boolean selected = allowSelection
                && current.archivedAt() == null
                && current.version() == expectedArtifactVersion
                && java.util.Objects.equals(current.resourceDefaultVersionId(), expectedCurrentVersionId)
                && artifacts.setResourceDefaultVersion(ownerId, projectId, artifactId,
                        expectedArtifactVersion, revision.id(), current.title(), now);
        return new TaskVersionResult(revision.id(), selected);
    }

    /** Appends one user-uploaded media version without changing the resource-library default. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ArtifactVersion appendUserMediaVersionWithinChange(UUID ownerId, UUID projectId,
            UUID artifactId, JsonNode content, UUID baseVersionId, JsonNode frozenInput) {
        projects.requireActiveProject(ownerId, projectId);
        Artifact current = artifacts.findForUpdate(ownerId, projectId, artifactId)
                .orElseThrow(this::notFound);
        requireEditable(current);
        if (current.kind() == Artifact.Kind.TEXT) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.identity-service.invalid-request"), ApiMessage.of("api.canvas-service.only-image-and-video-cards-can-be-uploaded-with-additional"), false);
        }
        List<ArtifactVersion.InputReference> references =
                contentValidator.validate(current.kind(), content);
        requireUploadAuthorship(current.kind(), content, ArtifactVersion.CreatedByKind.USER);
        validateReferences(projectId, references);
        validateMediaAsset(ownerId, projectId, current.kind(), content);
        if (baseVersionId != null) requireVersion(ownerId, projectId, artifactId, baseVersionId);
        Instant now = clock.instant();
        ArtifactVersion revision = new ArtifactVersion(UUID.randomUUID(), projectId,
                artifactId, artifacts.nextVersionNo(projectId, artifactId),
                INITIAL_SCHEMA_VERSION, baseVersionId,
                frozenInput == null ? null : frozenInput.deepCopy(), content.deepCopy(), references,
                ArtifactVersion.CreatedByKind.USER, null, now);
        artifacts.appendVersion(revision);
        return revision;
    }

    /**
     * 任务版本归档结果及其是否通过并发前提成为当前选用版本。
     *
     * @param versionId 新追加的不可变版本 ID
     * @param selected 是否已将该版本设为产物当前选用版本
     */
    public record TaskVersionResult(UUID versionId, boolean selected) {}

    /** 调用方持有项目事件锁时执行的版本选择；接受同一版本的安全幂等重放。 */
    private ArtifactView setResourceDefaultVersionLocked(
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
        if (java.util.Objects.equals(current.resourceDefaultVersionId(), versionId)
                && current.version() == expectedArtifactVersion) {
            return new ArtifactView(current, target);
        }
        if (java.util.Objects.equals(current.resourceDefaultVersionId(), versionId)
                && current.version() == expectedArtifactVersion + 1) {
            return new ArtifactView(current, target);
        }
        if (current.version() != expectedArtifactVersion
                || !artifacts.setResourceDefaultVersion(
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

    /** 事件仅携带产物 ID、资源默认版本 ID 和类型，不含正文或媒体地址。 */
    private ProjectEventService.EventDraft artifactEvent(String type, ArtifactView view) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("artifactId", view.artifact().id().toString());
        if (view.resourceDefaultVersion() == null) {
            payload.putNull("resourceDefaultVersionId");
        } else {
            payload.put("resourceDefaultVersionId", view.resourceDefaultVersion().id().toString());
        }
        payload.put("kind", view.artifact().kind().name());
        return new ProjectEventService.EventDraft(
                type, 1, view.artifact().id(), view.artifact().version(), payload);
    }

    /** 批量核对正文引用版本存在于同一项目且其产物类型符合 Schema 声明。 */
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
                        ApiMessage.of("api.artifact-service.product-reference-is-invalid"),
                        ApiMessage.of("api.artifact-service.the-referenced-version-does-not-exist-belongs-to-another-project"),
                        false);
            }
        }
    }

    /** Schema 只校验字段形状；此处还要把媒体身份绑定到项目内真实且已就绪的私有文件。 */
    private void validateMediaAsset(UUID ownerId, UUID projectId, Artifact.Kind kind,
            JsonNode content) {
        if (kind != Artifact.Kind.TEXT) {
            Asset.MediaKind mediaKind = Asset.MediaKind.valueOf(kind.name());
            assets.requireReadyMedia(ownerId, projectId,
                    UUID.fromString(content.path("assetId").asText()), mediaKind);
        }
    }

    /** 禁止 Agent 或生成任务伪造用户上传来源。 */
    private void requireUploadAuthorship(Artifact.Kind kind, JsonNode content,
            ArtifactVersion.CreatedByKind author) {
        if (Set.of(ArtifactVersion.MediaSourceType.LIBRARY_IMPORT.name(),
                ArtifactVersion.MediaSourceType.SKILL_IMPORT.name()).contains(content.path("sourceType").asText())) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "ARTIFACT_ORIGIN_INVALID",
                    ApiMessage.of("api.artifact-service.invalid-source"), ApiMessage.of("api.skill-asset-archive.server-only-origin"), false);
        }
        if (kind != Artifact.Kind.TEXT
                && ArtifactVersion.MediaSourceType.UPLOAD.name().equals(content.path("sourceType").asText())
                && author != ArtifactVersion.CreatedByKind.USER) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ARTIFACT_ORIGIN_INVALID", ApiMessage.of("api.artifact-service.invalid-image-source"),
                    ApiMessage.of("api.artifact-service.images-uploaded-by-users-can-only-create-versions-by-user"), false);
        }
    }

    /** 以所有者和项目范围读取产物，越权与不存在使用相同错误。 */
    private Artifact requireArtifact(UUID ownerId, UUID projectId, UUID artifactId) {
        return artifacts.find(ownerId, projectId, artifactId).orElseThrow(this::notFound);
    }

    /** 归档产物仍可读取历史，但不能追加或选择新版本。 */
    private void requireEditable(Artifact artifact) {
        if (artifact.archivedAt() != null) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ARTIFACT_ARCHIVED",
                    ApiMessage.of("api.artifact-service.product-archived"),
                    ApiMessage.of("api.artifact-service.archived-products-cannot-create-or-select-new-versions"),
                    false);
        }
    }

    /** 去除标题首尾空白，并限制为 1 至 160 字符。 */
    private String validateTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty() || normalized.length() > 160) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    ApiMessage.of("api.identity-service.invalid-request"),
                    ApiMessage.of("api.artifact-service.product-title-must-be-1-to-160-characters"),
                    false);
        }
        return normalized;
    }

    /** 将产物、版本不存在与越权统一映射为 404。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.artifact-service.product-does-not-exist"),
                ApiMessage.of("api.artifact-service.the-product-version-or-project-does-not-exist-or-the"),
                false);
    }

    /** CAS 失败时拒绝切换当前版本或覆盖较新编辑。 */
    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ARTIFACT_VERSION_CONFLICT",
                ApiMessage.of("api.artifact-service.content-has-been-updated"),
                ApiMessage.of("api.artifact-service.the-target-product-has-been-modified-please-read-the-latest"),
                false);
    }

    /**
     * 稳定产物身份及其资源库默认的不可变正文版本。
     *
     * @param artifact 产物身份、资源默认版本指针和项目状态
     * @param resourceDefaultVersion 资源默认指针对应的完整不可变正文
     */
    public record ArtifactView(Artifact artifact, ArtifactVersion resourceDefaultVersion) {}

    private static boolean isMediaKind(Artifact.Kind kind) {
        return kind != Artifact.Kind.TEXT;
    }

    /**
     * 手工创建结果；幂等重放使用首次保存的响应快照。
     *
     * @param view 首次创建时固定的产物响应
     * @param replayed 是否从已完成的幂等记录读取
     */
    public record CreateResult(ArtifactView view, boolean replayed) {}
}

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

/** 校验产物权限、正文引用和媒体资产，并协调不可变版本与当前版本选择。 */
@Service
public class ArtifactService {

    /** 镜头时长从第二版起用整数秒；其他产物仍维持各自的第一版正文。 */
    private static int schemaVersion(Artifact.Kind kind) {
        return kind == Artifact.Kind.SHOT ? 2 : 1;
    }
    /** 手工创建产物的幂等命令保留时长。 */
    private static final Duration CREATE_KEY_RETENTION = Duration.ofHours(24);

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
            // 重放仍重新鉴权嵌套资源，但返回首次提交时的响应快照。
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

    /** 同一创建键已预留但尚未完成时返回可重试冲突。 */
    private ApiProblemException createInProgress() {
        return new ApiProblemException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                "相同请求正在处理", "请稍后使用相同 Idempotency-Key 重试。", true);
    }

    /** 对类型、规范化标题及正文文本计算幂等请求摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
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

    /** 在调用方已持有项目事件锁的事务内物化任务输出，禁止脱离对应任务状态提交。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ArtifactView createFromTaskWithinChange(UUID ownerId, UUID projectId, UUID runId,
            Artifact.Kind kind, String requestedTitle, JsonNode content) {
        return createLocked(ownerId, projectId, kind, requestedTitle, content,
                ArtifactVersion.CreatedByKind.TASK, runId, true);
    }

    /** 统一创建边界；任务晚到结果可归档到已归档项目，其他来源只允许活动项目。 */
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
            // 已受理外部请求的晚到结果仍需归档，即使用户期间归档了项目。
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
                schemaVersion(kind),
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

    /** 读取稳定产物身份及其当前选用的不可变版本。 */
    @Transactional(readOnly = true)
    public ArtifactView get(UUID ownerId, UUID projectId, UUID artifactId) {
        projects.get(ownerId, projectId);
        Artifact artifact = requireArtifact(ownerId, projectId, artifactId);
        ArtifactVersion current = artifacts.findVersion(
                        projectId, artifactId, artifact.currentVersionId())
                .orElseThrow(() -> new IllegalStateException("Artifact current version is missing"));
        return new ArtifactView(artifact, current);
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
                schemaVersion(current.kind()),
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

    /** 只解析同项目图片版本，供可信媒体任务读取已固定的参考图。 */
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

    /** 限制 Agent 只能读取创建 Run 时显式绑定的版本或本轮自身产出的版本。 */
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

    /** 选择已有历史版本；按预期产物版本保护并发修改，重复选择保持幂等。 */
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
     * 在调用方项目事件事务中追加任务产物版本。只有任务固定的当前版本 ID 和 CAS 版本都未变化，
     * 且调用方允许选用时才切换当前版本；取消后的结果或用户编辑后的旧输入只保留在历史。
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
                artifactId, artifacts.nextVersionNo(projectId, artifactId),
                schemaVersion(current.kind()),
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

    /** 将批准的关键帧和已完成视频写入仍为预期版本的镜头，形成一个新的镜头版本。 */
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

    /**
     * 任务版本归档结果及其是否通过并发前提成为当前选用版本。
     *
     * @param versionId 新追加的不可变版本 ID
     * @param selected 是否已将该版本设为产物当前选用版本
     */
    public record TaskVersionResult(UUID versionId, boolean selected) {}

    /** 调用方持有项目事件锁时执行的版本选择；接受同一版本的安全幂等重放。 */
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

    /** 事件仅携带产物 ID、当前版本 ID 和类型，不含正文或媒体地址。 */
    private ProjectEventService.EventDraft artifactEvent(String type, ArtifactView view) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("artifactId", view.artifact().id().toString());
        payload.put("currentVersionId", view.currentVersion().id().toString());
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
                        "产物引用无效",
                        "引用版本不存在、属于其他项目或类型不匹配。",
                        false);
            }
        }
    }

    /** Schema 只校验字段形状；此处还要把媒体身份绑定到项目内真实且已就绪的私有文件。 */
    private void validateMediaAsset(UUID ownerId, UUID projectId, Artifact.Kind kind,
            JsonNode content) {
        if (kind == Artifact.Kind.IMAGE || kind == Artifact.Kind.VIDEO) {
            Asset.MediaKind mediaKind = kind == Artifact.Kind.IMAGE
                    ? Asset.MediaKind.IMAGE : Asset.MediaKind.VIDEO;
            assets.requireReadyMedia(ownerId, projectId,
                    UUID.fromString(content.path("assetId").asText()), mediaKind);
        }
    }

    /** 禁止 Agent 或生成任务伪造用户上传来源。 */
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
                    "产物已归档",
                    "归档产物不能创建或选择新版本。",
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
                    "请求参数无效",
                    "产物标题必须为 1 至 160 个字符。",
                    false);
        }
        return normalized;
    }

    /** 将产物、版本不存在与越权统一映射为 404。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "产物不存在",
                "产物、版本或项目不存在，或当前用户无权访问。",
                false);
    }

    /** CAS 失败时拒绝切换当前版本或覆盖较新编辑。 */
    private ApiProblemException versionConflict() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ARTIFACT_VERSION_CONFLICT",
                "内容已更新",
                "目标产物已被修改，请读取最新版本后重新提交。",
                false);
    }

    /**
     * 稳定产物身份及其当前选用的不可变正文版本。
     *
     * @param artifact 产物身份、当前版本指针和项目状态
     * @param currentVersion 当前指针所对应的完整不可变正文
     */
    public record ArtifactView(Artifact artifact, ArtifactVersion currentVersion) {}

    /**
     * 手工创建结果；幂等重放使用首次保存的响应快照。
     *
     * @param view 首次创建时固定的产物响应
     * @param replayed 是否从已完成的幂等记录读取
     */
    public record CreateResult(ArtifactView view, boolean replayed) {}
}

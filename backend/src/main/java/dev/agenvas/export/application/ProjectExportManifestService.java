package dev.agenvas.export.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.settings.application.MediaStyleService;
import dev.agenvas.skill.application.SkillRunService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 按所有者读取项目，并只导出明确允许的非密钥配置、产物历史及媒体元数据。 */
@Service
public class ProjectExportManifestService {
    private static final int MANIFEST_SCHEMA_VERSION = 6;

    /** 校验项目所有者并读取一致性快照中的项目版本。 */
    private final ProjectService projects;
    /** 读取项目产物目录及不可变版本历史。 */
    private final ArtifactService artifacts;
    /** 列出项目媒体资产的非私密元数据。 */
    private final AssetService assets;
    /** Reads persisted spatial cards and their independent displayed versions. */
    private final CanvasService canvas;
    /** Reads each media card's complete editable generation branch. */
    private final MediaDraftService mediaDrafts;
    /** Reads persisted card topology without deriving editable lines from provenance. */
    private final CanvasConnectionService connections;
    /** 构造经过字段白名单过滤的内容 JSON。 */
    private final ObjectMapper mapper;
    /** 标记清单生成时间。 */
    private final Clock clock;
    private final MediaStyleService styles;
    private final SkillRunService skills;

    /** 组装项目清单所需的权限、产物、资产和时间服务。 */
    public ProjectExportManifestService(ProjectService projects, ArtifactService artifacts,
            AssetService assets, CanvasService canvas, MediaDraftService mediaDrafts,
            CanvasConnectionService connections, ObjectMapper mapper, Clock clock,
            MediaStyleService styles, SkillRunService skills) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.assets = assets;
        this.canvas = canvas;
        this.mediaDrafts = mediaDrafts;
        this.connections = connections;
        this.mapper = mapper;
        this.clock = clock;
        this.styles = styles;
        this.skills = skills;
    }

    /** 在一个可重复读快照中生成清单，保留资产 ID 但不包含 URL 或存储密钥。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Manifest build(UUID ownerId, UUID projectId) {
        Project project = projects.get(ownerId, projectId);
        ArtifactService.ProjectExportVersions catalog =
                artifacts.listProjectExport(ownerId, projectId);
        Map<UUID, Artifact.Kind> kinds = catalog.artifacts().stream()
                .collect(Collectors.toMap(Artifact::id, Artifact::kind));
        Map<UUID, List<VersionEntry>> versions = catalog.versions().stream()
                .collect(Collectors.groupingBy(ArtifactVersion::artifactId,
                        Collectors.mapping(version -> versionEntry(version,
                                kinds.get(version.artifactId())), Collectors.toList())));
        List<CanvasItemEntry> canvasItems = canvas.list(ownerId, projectId).stream()
                .map(entry -> canvasItemEntry(ownerId, projectId, entry))
                .toList();
        List<ConnectionEntry> connectionEntries = connections.list(ownerId, projectId).stream()
                .map(this::connectionEntry).toList();
        return new Manifest(MANIFEST_SCHEMA_VERSION, clock.instant(), project.eventSeq(),
                new ProjectEntry(project.id(), project.name(), project.aspectRatio(),
                        project.status(), project.createdAt()),
                catalog.artifacts().stream().map(artifact -> artifactEntry(
                        artifact, versions.getOrDefault(artifact.id(), List.of()))).toList(),
                assets.listProjectAssets(ownerId, projectId).stream()
                        .map(this::assetEntry).toList(), canvasItems, connectionEntries, skills.exportProject(ownerId, projectId));
    }

    /** 将产物资源库默认指针、归档状态和历史版本摘要组装为清单条目。 */
    private ArtifactEntry artifactEntry(Artifact artifact, List<VersionEntry> versions) {
        return new ArtifactEntry(artifact.id(), artifact.kind(), artifact.title(),
                artifact.resourceDefaultVersionId(), artifact.archivedAt(), versions);
    }

    /** 仅复制该产物类型允许导出的内容字段，排除任意媒体参数与任务、Provider 内部数据。 */
    private VersionEntry versionEntry(ArtifactVersion version, Artifact.Kind kind) {
        ObjectNode safe = mapper.createObjectNode();
        String[] fields = allowedFields(kind);
        for (String field : fields) {
            JsonNode value = version.content().get(field);
            if (value != null) safe.set(field, value.deepCopy());
        }
        return new VersionEntry(version.id(), version.versionNo(), version.schemaVersion(),
                version.baseVersionId(), copy(version.frozenInput()), version.createdAt(), safe);
    }

    private JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    /** Exports the exact card layout, selected result and card-owned media working branch. */
    private CanvasItemEntry canvasItemEntry(UUID ownerId, UUID projectId,
            CanvasService.CanvasEntry entry) {
        CanvasItem item = entry.item();
        MediaDraftEntry draft = null;
        List<UUID> mediaVersionIds = List.of();
        if (entry.artifact() != null
                && entry.artifact().artifact().kind() != Artifact.Kind.TEXT) {
            draft = mediaDraftEntry(mediaDrafts.get(ownerId, projectId, item.id()));
            mediaVersionIds = canvas.listMediaVersions(ownerId, projectId, item.id()).stream()
                    .map(ArtifactVersion::id).toList();
        }
        return new CanvasItemEntry(item.id(), item.subjectType(), item.subjectId(),
                item.selectedVersionId(), item.title(), item.x(), item.y(), item.width(),
                item.height(), item.zIndex(), item.groupId(), item.locked(), item.version(),
                mediaVersionIds, draft);
    }

    private MediaDraftEntry mediaDraftEntry(MediaDraft draft) {
        return new MediaDraftEntry(draft.prompt(), copy(draft.parameters()),
                draft.durationSeconds(), draft.capabilityId(), draft.styleId(), styles.snapshotForExport(draft.styleId()), draft.videoInputMode(),
                draft.mediaInputs(), draft.mentions(), draft.displayMode(), draft.version());
    }

    private ConnectionEntry connectionEntry(CanvasConnection connection) {
        return new ConnectionEntry(connection.id(), connection.sourceCanvasItemId(),
                connection.targetCanvasItemId(), connection.relationType(),
                connection.sourceArtifactVersionId(), connection.version());
    }

    /** 按领域产物类型返回清单字段白名单，不读取 Provider JSON 中的类型提示。 */
    private String[] allowedFields(Artifact.Kind kind) {
        // The artifact kind is obtained from the validated catalog, not provider JSON.
        return switch (kind) {
            case TEXT -> new String[] {"format", "text"};
            case IMAGE -> new String[] {"assetId", "prompt", "negativePrompt",
                    "providerConfigVersion", "workflowVersion"};
            case VIDEO, AUDIO -> new String[] {"assetId", "prompt", "negativePrompt",
                    "providerConfigVersion", "workflowVersion"};
        };
    }

    /** 仅导出资产摘要与尺寸、时长等元数据，不暴露私有对象键。 */
    private AssetEntry assetEntry(Asset asset) {
        return new AssetEntry(asset.id(), asset.mediaKind(), asset.contentType(),
                asset.byteSize(), asset.sha256(), asset.width(), asset.height(),
                asset.durationMs(), asset.thumbnailSha256(), asset.createdAt());
    }

    /** 项目级导出清单，不包含所有者 ID、会话或 Provider 配置。
     * @param schemaVersion 清单 JSON 结构版本
     * @param generatedAt 生成清单的时刻
     * @param snapshotSeq 同一数据库快照中的项目事件水位
     * @param project 非密钥项目摘要
     * @param artifacts 项目内产物及其版本历史
     * @param assets 项目媒体资产的安全元数据
     */
    public record Manifest(int schemaVersion, Instant generatedAt, long snapshotSeq,
            ProjectEntry project, List<ArtifactEntry> artifacts, List<AssetEntry> assets,
            List<CanvasItemEntry> canvasItems, List<ConnectionEntry> connections, List<JsonNode> creativeSkills) {}

    /** 不含所有者或凭证的项目身份摘要。
     * @param id 项目 ID
     * @param name 项目名称
     * @param aspectRatio 项目画幅
     * @param status 项目状态
     * @param createdAt 项目创建时间
     */
    public record ProjectEntry(UUID id, String name, Project.AspectRatio aspectRatio,
            Project.Status status, Instant createdAt) {}

    /** 一个稳定产物身份及其全部不可变版本历史。
     * @param id 产物 ID
     * @param kind 产物类型
     * @param title 当前产物标题
     * @param resourceDefaultVersionId 资源库默认内容版本 ID；空媒体资源可为空
     * @param archivedAt 归档时间；未归档时为空
     * @param versions 已保存的历史版本
     */
    public record ArtifactEntry(UUID id, Artifact.Kind kind, String title,
            UUID resourceDefaultVersionId, Instant archivedAt, List<VersionEntry> versions) {}

    /** 只包含按产物类型白名单筛选的内容。
     * @param id 不可变内容版本 ID
     * @param versionNo 该产物内的版本序号
     * @param schemaVersion 正文结构版本
     * @param createdAt 版本创建时间
     * @param content 经过类型字段白名单筛选的 JSON 正文
     */
    public record VersionEntry(UUID id, int versionNo, int schemaVersion,
            UUID baseVersionId, JsonNode frozenInput, Instant createdAt, JsonNode content) {}

    /** Persisted spatial state plus the independent media branch owned by that card. */
    public record CanvasItemEntry(UUID id, CanvasItem.SubjectType subjectType, UUID subjectId,
            UUID selectedVersionId, String title, java.math.BigDecimal x,
            java.math.BigDecimal y, java.math.BigDecimal width, java.math.BigDecimal height,
            int zIndex, UUID groupId, boolean locked, long version,
            List<UUID> mediaVersionIds, MediaDraftEntry mediaDraft) {}

    /** Complete safe generation state for one image or video card. */
    public record MediaDraftEntry(String prompt, JsonNode parameters, Integer durationSeconds,
            UUID capabilityId, UUID styleId, MediaStyleService.Snapshot style, MediaDraft.VideoInputMode videoInputMode,
            List<MediaDraft.MediaInput> mediaInputs, List<MediaDraft.PromptMention> mentions,
            MediaDraft.DisplayMode displayMode, long version) {}

    /** One persisted editable topology edge with its frozen source image version. */
    public record ConnectionEntry(UUID id, UUID sourceCanvasItemId, UUID targetCanvasItemId,
            CanvasConnection.RelationType relationType, UUID sourceArtifactVersionId,
            long version) {}

    /** 媒体资产元数据，不包含私有对象键或可复用下载地址。
     * @param id 资产 ID
     * @param mediaKind 媒体类别
     * @param contentType 已校验的媒体类型
     * @param byteSize 归档文件字节数
     * @param sha256 原始媒体内容摘要
     * @param width 媒体宽度；非图像时为空
     * @param height 媒体高度；非图像时为空
     * @param durationMs 视频时长；非视频时为空
     * @param thumbnailSha256 缩略图摘要；无缩略图时为空
     * @param createdAt 资产归档时间
     */
    public record AssetEntry(UUID id, Asset.MediaKind mediaKind, String contentType,
            long byteSize, String sha256, Integer width, Integer height,
            Integer durationMs, String thumbnailSha256, Instant createdAt) {}
}

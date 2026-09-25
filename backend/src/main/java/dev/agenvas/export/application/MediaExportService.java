package dev.agenvas.export.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 校验精确视频版本与裁剪范围，生成无声导出任务使用的不可变输入快照。 */
@Service
public class MediaExportService {

    /** 导出幂等键允许的字符和长度，禁止空白及路径分隔符。 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    /** 检查项目状态和画幅设置，并创建项目级导出任务。 */
    private final ProjectService projects;
    /** 确认视频产物与指定历史版本的归属和类型。 */
    private final ArtifactService artifacts;
    /** 确认输入视频资产已归档且时长可验证。 */
    private final AssetService assets;
    /** 查找幂等任务并持久化项目导出任务。 */
    private final TaskService tasks;
    /** 构造固定字段的导出输入快照。 */
    private final ObjectMapper mapper;

    /** 组合项目设置、产物版本、归档资产和任务幂等服务。 */
    public MediaExportService(ProjectService projects, ArtifactService artifacts,
            AssetService assets, TaskService tasks, ObjectMapper mapper) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.assets = assets;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    /** 将用户指定的片段顺序、版本、归档字节摘要和裁剪范围冻结到单个幂等任务。 */
    public Task create(UUID ownerId, UUID projectId, String idempotencyKey,
            List<SegmentRequest> segments) {
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw invalid("Idempotency-Key 必须为 1 至 120 个安全字符。");
        }
        requireSegmentCount(segments);
        String stepKey = "export-" + idempotencyKey;
        Task replay = tasks.findProjectExportByStepKey(ownerId, projectId, stepKey);
        if (replay != null) {
            JsonNode previous = replay.input().path("segments");
            boolean same = previous.isArray() && previous.size() == segments.size();
            for (int index = 0; same && index < segments.size(); index++) {
                SegmentRequest requested = segments.get(index);
                JsonNode saved = previous.get(index);
                same = requested != null
                        && requested.videoArtifactId() != null
                        && requested.videoVersionId() != null
                        && requested.videoArtifactId().toString().equals(
                                saved.path("videoArtifactId").asText())
                        && requested.videoVersionId().toString().equals(
                                saved.path("videoVersionId").asText())
                        && requested.startSeconds() == saved.path("startSeconds").asInt(-1)
                        && requested.endSeconds() == saved.path("endSeconds").asInt(-1);
            }
            if (!same) {
                throw new ApiProblemException(HttpStatus.CONFLICT,
                        "IDEMPOTENCY_CONFLICT", "导出请求冲突",
                        "相同幂等键已用于不同的导出输入。", false);
            }
            return replay;
        }
        ExportPreview preview = preview(ownerId, projectId, segments);
        return tasks.createProjectExport(ownerId, projectId,
                stepKey, preview.inputSnapshot(), preview.projectVersion());
    }

    /** 预览并校验片段顺序但不创建任务，因此不会授权或启动 FFmpeg。 */
    public ExportPreview preview(UUID ownerId, UUID projectId,
            List<SegmentRequest> segments) {
        requireSegmentCount(segments);
        Project project = projects.requireActiveProject(ownerId, projectId);
        ObjectNode input = mapper.createObjectNode();
        input.put("schemaVersion", 2);
        input.put("aspectRatio", project.aspectRatio().name());
        input.put("outputFormat", "SILENT_MP4_720P_24FPS");
        ArrayNode pinned = input.putArray("segments");
        int durationSeconds = 0;
        for (SegmentRequest segment : segments) {
            if (segment == null || segment.videoArtifactId() == null
                    || segment.videoVersionId() == null || segment.startSeconds() < 0
                    || segment.endSeconds() <= segment.startSeconds()
                    || segment.endSeconds() > 60) {
                throw invalid("视频版本或裁剪区间无效。");
            }
            durationSeconds += segment.endSeconds() - segment.startSeconds();
            if (durationSeconds > 60) {
                throw invalid("导出总时长不能超过 60 秒。");
            }
            ArtifactService.ArtifactView video = artifacts.get(ownerId, projectId,
                    segment.videoArtifactId());
            if (video.artifact().kind() != Artifact.Kind.VIDEO
                    || video.artifact().archivedAt() != null) {
                throw invalid("只能导出当前项目中未归档的视频产物。");
            }
            ArtifactVersion version = artifacts.requireVersion(ownerId, projectId,
                    segment.videoArtifactId(), segment.videoVersionId());
            UUID assetId;
            try {
                assetId = UUID.fromString(version.content().path("assetId").asText());
            } catch (IllegalArgumentException exception) {
                throw invalid("视频版本缺少有效的归档文件。");
            }
            Asset asset = assets.requireReadyMedia(ownerId, projectId, assetId,
                    Asset.MediaKind.VIDEO);
            if (asset.durationMs() == null
                    || Math.multiplyExact(segment.endSeconds(), 1_000) > asset.durationMs()) {
                throw invalid("裁剪终点超过已归档视频时长，或旧素材缺少可验证时长。");
            }
            ObjectNode item = pinned.addObject();
            item.put("videoArtifactId", segment.videoArtifactId().toString());
            item.put("videoVersionId", segment.videoVersionId().toString());
            item.put("assetId", asset.id().toString());
            item.put("assetSha256", asset.sha256());
            item.put("startSeconds", segment.startSeconds());
            item.put("endSeconds", segment.endSeconds());
        }
        input.put("durationSeconds", durationSeconds);
        return new ExportPreview(input, project.version());
    }

    /** 限制片段数量为一至六个，避免无界输入扩大探测和编码负载。 */
    private void requireSegmentCount(List<SegmentRequest> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > 6) {
            throw invalid("导出必须包含 1 至 6 个视频片段。");
        }
    }

    /** 查询项目级导出任务摘要，不依赖 Agent Run 是否仍在运行。 */
    public List<Task> list(UUID ownerId, UUID projectId) {
        return tasks.listProjectExports(ownerId, projectId);
    }

    /** 取消项目本地导出编排，不尝试停止或修改 Provider 媒体生成。 */
    public Task cancel(UUID ownerId, UUID projectId, UUID taskId) {
        return tasks.cancelProjectExport(ownerId, projectId, taskId);
    }

    /** 构造导出素材、版本或区间无效时返回的 400 响应。 */
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "EXPORT_INPUT_INVALID",
                "导出输入无效", detail, false);
    }

    /** 一段明确指定的历史视频版本及其裁剪区间。
     * @param videoArtifactId 视频产物 ID
     * @param videoVersionId 要导出的不可变内容版本 ID
     * @param startSeconds 裁剪起点，单位整数秒，包含该位置
     * @param endSeconds 裁剪终点，单位整数秒，不包含该位置
     */
    public record SegmentRequest(UUID videoArtifactId, UUID videoVersionId,
            int startSeconds, int endSeconds) {}

    /** 服务端生成的固定导出输入及校验时的项目设置版本。
     * @param inputSnapshot 含资产摘要、裁剪范围和输出规格的任务输入
     * @param projectVersion 预览时读取的项目乐观版本
     */
    public record ExportPreview(JsonNode inputSnapshot, long projectVersion) {}
}

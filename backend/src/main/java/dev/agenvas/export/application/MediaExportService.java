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

/** Validates exact video versions and snapshots an ordered silent export before queueing work. */
@Service
public class MediaExportService {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final TaskService tasks;
    private final ObjectMapper mapper;

    public MediaExportService(ProjectService projects, ArtifactService artifacts,
            AssetService assets, TaskService tasks, ObjectMapper mapper) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.assets = assets;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    /** Freezes caller-selected order, versions, archived bytes and trim ranges in one Task. */
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
                        && requested.startMs() == saved.path("startMs").asInt(-1)
                        && requested.endMs() == saved.path("endMs").asInt(-1);
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

    /** Validates a proposed order without creating a Task or authorizing FFmpeg execution. */
    public ExportPreview preview(UUID ownerId, UUID projectId,
            List<SegmentRequest> segments) {
        requireSegmentCount(segments);
        Project project = projects.requireActiveProject(ownerId, projectId);
        ObjectNode input = mapper.createObjectNode();
        input.put("schemaVersion", 1);
        input.put("aspectRatio", project.aspectRatio().name());
        input.put("outputFormat", "SILENT_MP4_720P_24FPS");
        ArrayNode pinned = input.putArray("segments");
        long durationMs = 0;
        for (SegmentRequest segment : segments) {
            if (segment == null || segment.videoArtifactId() == null
                    || segment.videoVersionId() == null || segment.startMs() < 0
                    || segment.endMs() <= segment.startMs()
                    || segment.endMs() > 60_000) {
                throw invalid("视频版本或裁剪区间无效。");
            }
            durationMs += segment.endMs() - segment.startMs();
            if (durationMs > 60_000) {
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
            ObjectNode item = pinned.addObject();
            item.put("videoArtifactId", segment.videoArtifactId().toString());
            item.put("videoVersionId", segment.videoVersionId().toString());
            item.put("assetId", asset.id().toString());
            item.put("assetSha256", asset.sha256());
            item.put("startMs", segment.startMs());
            item.put("endMs", segment.endMs());
        }
        input.put("durationMs", durationMs);
        return new ExportPreview(input, project.version());
    }

    private void requireSegmentCount(List<SegmentRequest> segments) {
        if (segments == null || segments.isEmpty() || segments.size() > 6) {
            throw invalid("导出必须包含 1 至 6 个视频片段。");
        }
    }

    /** Existing export summaries stay visible after the Agent Run ends. */
    public List<Task> list(UUID ownerId, UUID projectId) {
        return tasks.listProjectExports(ownerId, projectId);
    }

    /** Export cancellation only affects this project's local work. */
    public Task cancel(UUID ownerId, UUID projectId, UUID taskId) {
        return tasks.cancelProjectExport(ownerId, projectId, taskId);
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "EXPORT_INPUT_INVALID",
                "导出输入无效", detail, false);
    }

    /** One explicit historical version and its selected millisecond interval. */
    public record SegmentRequest(UUID videoArtifactId, UUID videoVersionId,
            int startMs, int endMs) {}

    /** Server-owned export input plus the project settings version used to validate it. */
    public record ExportPreview(JsonNode inputSnapshot, long projectVersion) {}
}

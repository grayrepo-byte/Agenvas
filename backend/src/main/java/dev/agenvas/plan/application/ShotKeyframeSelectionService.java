package dev.agenvas.plan.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 持久化用户明确选择的镜头关键帧，并要求图片任务在同一 Run 中成功完成。 */
@Service
public class ShotKeyframeSelectionService {

    /** 检查项目归属与是否仍允许编辑。 */
    private final ProjectService projects;
    /** 验证 Run 所有者，并读取当前 Run 状态。 */
    private final AgentRunService runs;
    /** 检查镜头、图片和当前内容版本。 */
    private final ArtifactService artifacts;
    /** 验证图片来源任务，并在关键帧选择后推进等待中的视频任务。 */
    private final TaskService tasks;
    /** 以镜头为键保存当前关键帧选择及其 CAS 版本。 */
    private final ShotKeyframeSelectionRepository selections;
    /** 在项目事件序号锁内提交选择变更事件。 */
    private final ProjectEventService events;
    /** 构造只包含镜头与版本标识的事件载荷。 */
    private final ObjectMapper mapper;
    /** 为选择记录创建和更新时间。 */
    private final Clock clock;

    /** 组合 Run、素材和任务校验，选择写入通过项目事件事务保持原子性。 */
    public ShotKeyframeSelectionService(ProjectService projects, AgentRunService runs,
            ArtifactService artifacts, TaskService tasks,
            ShotKeyframeSelectionRepository selections, ProjectEventService events,
            ObjectMapper mapper, Clock clock) {
        this.projects = projects;
        this.runs = runs;
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.selections = selections;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 返回已保存的选择；不存在时明确报错，不把空选择解释为用户已确认。 */
    @Transactional(readOnly = true)
    public ShotKeyframeSelection get(UUID ownerId, UUID projectId, UUID runId, UUID shotId) {
        runs.get(ownerId, projectId, runId);
        return selections.find(projectId, shotId).orElseThrow(this::notFound);
    }

    /** 在项目锁内执行选择 CAS，使关键帧变更与计划提案看到同一有序状态。 */
    @Transactional
    public ShotKeyframeSelection select(UUID ownerId, UUID projectId, UUID runId,
            UUID shotId, UUID shotVersionId, UUID imageId, UUID imageVersionId,
            Long expectedVersion) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            AgentRun run = runs.get(ownerId, projectId, runId);
            if (run.status() != AgentRun.Status.WAITING_TASKS
                    && run.status() != AgentRun.Status.RUNNING) {
                throw conflict("Run is not accepting a keyframe choice");
            }
            ArtifactService.ArtifactView shot = artifacts.get(ownerId, projectId, shotId);
            ArtifactService.ArtifactView image = artifacts.get(ownerId, projectId, imageId);
            if (shot.artifact().kind() != Artifact.Kind.SHOT
                    || image.artifact().kind() != Artifact.Kind.IMAGE
                    || shot.artifact().archivedAt() != null
                    || image.artifact().archivedAt() != null
                    || !shot.currentVersion().id().equals(shotVersionId)
                    || !image.currentVersion().id().equals(imageVersionId)) {
                throw conflict("Shot or image selection changed");
            }
            ArtifactVersion version = image.currentVersion();
            UUID sourceTaskId = sourceTaskId(version);
            Task source = tasks.get(ownerId, projectId, sourceTaskId);
            if (version.createdByKind() != ArtifactVersion.CreatedByKind.TASK
                    || !runId.equals(version.runId())
                    || !runId.equals(source.runId())
                    || source.kind() != Task.Kind.IMAGE_GENERATION
                    || source.status() != Task.Status.SUCCEEDED
                    || source.planId() == null
                    || !shotId.toString().equals(source.input().path("shotArtifactId").asText())
                    || !shotVersionId.toString().equals(source.input().path("shotVersionId").asText())
                    || source.output() == null
                    || !imageId.toString().equals(source.output().path("artifactId").asText())
                    || !imageVersionId.toString().equals(source.output().path("artifactVersionId").asText())) {
                throw conflict("Image is not a completed keyframe for this Run and shot");
            }
            ShotKeyframeSelection existing = selections.find(projectId, shotId).orElse(null);
            if (existing == null && expectedVersion != null
                    || existing != null && (expectedVersion == null
                            || existing.version() != expectedVersion)) {
                throw conflict("Keyframe selection version changed");
            }
            if (existing != null && shotVersionId.equals(existing.shotVersionId())
                    && imageVersionId.equals(existing.imageVersionId())) {
                return ProjectEventService.Change.unchanged(existing);
            }
            Instant now = clock.instant();
            ShotKeyframeSelection next = new ShotKeyframeSelection(projectId, shotId,
                    shotVersionId, imageId, imageVersionId, sourceTaskId, ownerId,
                    existing == null ? 0 : existing.version() + 1,
                    existing == null ? now : existing.createdAt(), now);
            boolean saved = existing == null ? selections.insert(next)
                    : selections.update(next, existing.version());
            if (!saved) {
                throw conflict("Keyframe selection changed concurrently");
            }
            tasks.promoteAfterKeyframeSelection(projectId, runId);
            return ProjectEventService.Change.changed(next, selectionEvent(next));
        }).value();
    }

    /** 从生成图片版本的内容中提取来源任务 ID；格式错误时拒绝作为关键帧使用。 */
    private UUID sourceTaskId(ArtifactVersion imageVersion) {
        try {
            return UUID.fromString(imageVersion.content().path("sourceTaskId").asText());
        } catch (IllegalArgumentException exception) {
            throw conflict("Image has no valid source Task");
        }
    }

    /** 为镜头当前选择生成稳定聚合 ID 的事件，不携带图片字节或描述正文。 */
    private ProjectEventService.EventDraft selectionEvent(ShotKeyframeSelection selection) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("shotArtifactId", selection.shotArtifactId().toString());
        payload.put("shotVersionId", selection.shotVersionId().toString());
        payload.put("imageArtifactId", selection.imageArtifactId().toString());
        payload.put("imageVersionId", selection.imageVersionId().toString());
        UUID aggregateId = UUID.nameUUIDFromBytes(("keyframe:" + selection.projectId()
                + ":" + selection.shotArtifactId()).getBytes(StandardCharsets.UTF_8));
        return new ProjectEventService.EventDraft("shot.keyframe.selected", 1,
                aggregateId, selection.version(), payload);
    }

    /** 构造镜头尚无选择或用户无权读取时使用的 404 响应。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "关键帧未选择", "镜头尚未选择关键帧或当前用户无权访问。", false);
    }

    /** 构造 Run 状态、素材版本或选择 CAS 不匹配时使用的 409 响应。 */
    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "KEYFRAME_SELECTION_CONFLICT",
                "关键帧选择冲突", detail, false);
    }
}

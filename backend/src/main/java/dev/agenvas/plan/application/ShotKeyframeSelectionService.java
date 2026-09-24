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

/** Records an explicit human keyframe choice only for a successful image from this Run and shot. */
@Service
public class ShotKeyframeSelectionService {

    private final ProjectService projects;
    private final AgentRunService runs;
    private final ArtifactService artifacts;
    private final TaskService tasks;
    private final ShotKeyframeSelectionRepository selections;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

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

    /** Returns the stored selection without treating an absent selection as an implicit choice. */
    @Transactional(readOnly = true)
    public ShotKeyframeSelection get(UUID ownerId, UUID projectId, UUID runId, UUID shotId) {
        runs.get(ownerId, projectId, runId);
        return selections.find(projectId, shotId).orElseThrow(this::notFound);
    }

    /** Uses the project lock so selection CAS and plan proposal observe one ordered state. */
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

    private UUID sourceTaskId(ArtifactVersion imageVersion) {
        try {
            return UUID.fromString(imageVersion.content().path("sourceTaskId").asText());
        } catch (IllegalArgumentException exception) {
            throw conflict("Image has no valid source Task");
        }
    }

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

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "关键帧未选择", "镜头尚未选择关键帧或当前用户无权访问。", false);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "KEYFRAME_SELECTION_CONFLICT",
                "关键帧选择冲突", detail, false);
    }
}

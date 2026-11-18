package dev.agenvas.task.application;

import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Reads trusted archived outputs without exposing task persistence to the artifact or Agent modules. */
@Service
public class TaskOutputReadAccessService {
    private final TaskRepository tasks;
    private final CanvasItemQueryService items;

    public TaskOutputReadAccessService(TaskRepository tasks, CanvasItemQueryService items) {
        this.tasks = tasks;
        this.items = items;
    }

    /** Model-returned IDs are hints only: the owned task must independently identify this exact archived version. */
    @Transactional(readOnly = true)
    public boolean allows(UUID ownerId, UUID projectId, UUID trustedRunId, UUID taskId,
            UUID artifactId, UUID versionId) {
        if (trustedRunId == null)
            return false;
        Task task = tasks.find(ownerId, projectId, taskId).orElse(null);
        if (task == null || !projectId.equals(task.projectId()) || !trustedRunId.equals(task.runId())
                || !task.approvedMedia()
                || !task.input().path(Task.REQUIRE_EXPLICIT_SELECTION_PROPERTY).asBoolean(false))
            return false;
        return archivedOutputs(ownerId, projectId, task).stream().map(ArchivedTaskResults::read)
                .anyMatch(results -> matches(ownerId, projectId, results.primary(),
                        task.input().path("canvasItemId").asText(), artifactId, versionId)
                        || results.additional().stream().anyMatch(extra -> extra.canvasItemId() != null
                                && matches(ownerId, projectId, extra, extra.canvasItemId(),
                                        artifactId, versionId)));
    }

    /** Late archives are separate immutable rows; reading them must never rewrite the frozen approval outcome. */
    @Transactional(readOnly = true)
    public List<JsonNode> archivedOutputs(UUID ownerId, UUID projectId, UUID taskId) {
        return tasks.find(ownerId, projectId, taskId).map(task -> archivedOutputs(ownerId, projectId, task))
                .orElseGet(List::of);
    }

    private List<JsonNode> archivedOutputs(UUID ownerId, UUID projectId, Task task) {
        List<JsonNode> outputs = new ArrayList<>();
        if (task.output() != null && task.output().isObject())
            outputs.add(task.output());
        outputs.addAll(tasks.lateResults(ownerId, projectId, task.id()));
        return List.copyOf(outputs);
    }

    private boolean matches(UUID ownerId, UUID projectId, ArchivedTaskResults.Identity output, String canvasItemId,
            UUID artifactId, UUID versionId) {
        return output != null && artifactId.toString().equals(output.artifactId())
                && versionId.toString().equals(output.artifactVersionId())
                && items.artifactItems(ownerId, projectId, artifactId).stream()
                        .anyMatch(item -> item.id().toString().equals(canvasItemId));
    }
}

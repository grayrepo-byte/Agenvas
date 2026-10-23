package dev.agenvas.task.application;

import dev.agenvas.project.application.ProjectService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Narrow read boundary for undo safety; never exposes task inputs, provider receipts or secrets. */
@Service
public class TaskCanvasOwnershipReadService {
    private final TaskRepository tasks;
    private final ProjectService projects;

    public TaskCanvasOwnershipReadService(TaskRepository tasks, ProjectService projects) {
        this.tasks = tasks; this.projects = projects;
    }

    @Transactional(readOnly = true)
    public boolean hasOwnership(UUID ownerId, UUID projectId, UUID canvasItemId, UUID textArtifactId) {
        projects.get(ownerId, projectId);
        return tasks.hasCanvasOwnership(ownerId, projectId, canvasItemId, textArtifactId);
    }
}

package dev.agenvas.task.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Synthetic task fixture for lease, submission and archive tests. Production media requests
 * enter through direct generation or a user-approved batch; this fixture does not call a provider.
 */
public final class TaskMediaFixture {
    private TaskMediaFixture() {}

    public static Task create(TaskService tasks, TaskRepository repository, ArtifactService artifacts,
            UUID ownerId, UUID projectId, UUID runId, String stepKey, Task.Kind kind,
            JsonNode input, UUID artifactId) {
        Artifact target = artifacts.get(ownerId, projectId, artifactId).artifact();
        ObjectNode frozenInput = (ObjectNode) input.deepCopy();
        frozenInput.put("schemaVersion", 2);
        frozenInput.put("artifactId", artifactId.toString());
        Task task = tasks.create(ownerId, projectId, runId, stepKey, kind, frozenInput, 1);
        repository.createArtifactTarget(new TaskRepository.ArtifactTarget(task.id(), projectId,
                target.id(), target.resourceDefaultVersionId(), target.version(), null));
        return task;
    }
}

package dev.agenvas.task.application;

import dev.agenvas.event.application.ProjectEventService.EventDraft;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public Task status projection. The application caller supplies the already interpreted cost hint. */
final class TaskStatusEvents {
    private TaskStatusEvents() {
    }

    static EventDraft statusChanged(ObjectMapper mapper, Task task, boolean possibleExternalCost) {
        return statusChanged(task, payload(mapper, task, possibleExternalCost));
    }

    static EventDraft mediaOutcome(ObjectMapper mapper, Task task, boolean possibleExternalCost,
            UUID artifactId, UUID artifactVersionId, boolean selected) {
        return statusChanged(task, payload(mapper, task, possibleExternalCost)
                .put("artifactId", artifactId.toString())
                .put("artifactVersionId", artifactVersionId.toString())
                .put("selected", selected));
    }

    private static ObjectNode payload(ObjectMapper mapper, Task task, boolean possibleExternalCost) {
        ObjectNode payload = mapper.createObjectNode()
                .put("taskId", task.id().toString())
                .put("status", task.status().name());
        if (task.kind() == Task.Kind.SUBTITLE_TRANSCRIPTION)
            payload.put("subtitleId", task.input().path("subtitleId").asText());
        return payload.put("cancelRequested", task.cancelRequested())
                .put("possibleExternalCost", possibleExternalCost);
    }

    private static EventDraft statusChanged(Task task, ObjectNode payload) {
        return new EventDraft("task.status.changed", 1, task.id(), task.version(), payload);
    }
}

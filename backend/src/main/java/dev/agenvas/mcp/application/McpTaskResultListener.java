package dev.agenvas.mcp.application;

import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.task.application.TaskService;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Records every archived media version in the same transaction as the Task outcome.
 * No Run or director conversation is created or resumed by an external command.
 */
@Component
public class McpTaskResultListener {
    private final McpOperationRepository operations;
    private final TaskService tasks;
    public McpTaskResultListener(McpOperationRepository operations, TaskService tasks) {
        this.operations = operations; this.tasks = tasks;
    }
    @EventListener
    public void recorded(ProjectEventRecorded recorded) {
        if (!"task.status.changed".equals(recorded.event().type())
                || !recorded.event().payload().hasNonNull("artifactVersionId")) return;
        UUID taskId = recorded.event().aggregateId();
        operations.operationForTask(taskId).ifPresent(operation -> {
            var task = tasks.get(recorded.ownerId(), recorded.event().projectId(), taskId);
            if (task.output() == null) throw new IllegalStateException("Archived MCP task has no result");
            link(operation, task.output());
            for (JsonNode extra : task.output().path("additionalResults")) link(operation, extra);
        });
    }
    private void link(UUID operation, JsonNode output) {
        if (output.path("artifactVersionId").isString())
            operations.linkVersion(operation, UUID.fromString(output.path("artifactVersionId").asText()));
    }
}

package dev.agenvas.llm.application;

import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.task.domain.Task;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Safe exact-output mapping shared by immutable model outcomes and the live archive projection. */
final class AgentMediaResultProjection {
    private static final List<String> OUTPUT_FIELDS = List.of("artifactId", "artifactVersionId", "selected", "additionalResults");

    private AgentMediaResultProjection() {}

    static ObjectNode task(ObjectMapper mapper, AgentMediaApproval approval, Task task) {
        return task(mapper, approval, task, task.output());
    }

    static ObjectNode task(ObjectMapper mapper, AgentMediaApproval approval, Task task, JsonNode archivedOutput) {
        int outputIndex = approval.taskIds().indexOf(task.id());
        if (outputIndex < 0 || outputIndex >= approval.targets().path("outputs").size())
            throw new IllegalStateException("Media task is not a frozen approval output");
        JsonNode target = approval.targets().path("outputs").get(outputIndex);
        var item = mapper.createObjectNode().put("outputIndex", outputIndex)
                .put("canvasItemId", target.path("canvasItemId").asText())
                .put("taskId", task.id().toString()).put("kind", task.kind().name()).put("status", task.status().name());
        if (target.path("storyboard").isObject()) item.set("storyboard", target.path("storyboard").deepCopy());
        if (task.errorCode() != null) item.put("errorCode", task.errorCode());
        // Provider receipts, credentials, URLs and billing payloads are never exposed here.
        if (archivedOutput != null) {
            for (String field : OUTPUT_FIELDS) if (archivedOutput.has(field)) item.set(field, archivedOutput.get(field).deepCopy());
        }
        item.put("possibleExternalCost", task.status() == Task.Status.UNKNOWN
                || task.providerRequestId() != null || task.status() == Task.Status.SUBMITTING);
        return item;
    }
}

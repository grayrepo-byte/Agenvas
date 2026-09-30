package dev.agenvas.provider.application;

import dev.agenvas.task.domain.Task;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Same immutable result provenance for mock and real speech adapters. */
final class AudioResult {
    private AudioResult() {}
    static ObjectNode content(ObjectMapper mapper, Task task, String assetId, boolean mock) {
        ObjectNode content = mapper.createObjectNode().put("assetId", assetId)
                .put("prompt", task.input().path("prompt").asText())
                .put("providerConfigVersion", task.input().path("providerConfigVersion").asInt())
                .put("workflowVersion", task.input().path("workflowVersion").asText())
                .put("sourceTaskId", task.id().toString());
        ObjectNode parameters = (ObjectNode) task.input().path("mediaInput").path("parameters").deepCopy();
        parameters.put("mock", mock);
        if (mock) parameters.put("displayLabel", "演示音频（非语音合成）");
        content.set("parameters", parameters);
        return content;
    }
}

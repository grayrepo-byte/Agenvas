package dev.agenvas.provider.application;

import dev.agenvas.task.domain.Task;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 构造媒体结果的共同来源字段；各执行路径继续决定参数快照和 Provider 元数据。 */
final class MediaResult {
    private MediaResult() {}

    static ObjectNode content(ObjectMapper mapper, Task task, UUID assetId, String prompt) {
        ObjectNode content = mapper.createObjectNode().put("assetId", assetId.toString())
                .put("prompt", task.input().path("mediaInput").path("userRenderedPrompt").asText(prompt));
        if (task.input().has("negativePrompt")) {
            content.put("negativePrompt", task.input().path("negativePrompt").asText());
        }
        content.put("providerConfigVersion", task.input().path("providerConfigVersion").asInt());
        content.put("workflowVersion", task.input().path("workflowVersion").asText());
        content.put("sourceTaskId", task.id().toString());
        content.putObject("parameters");
        return content;
    }

    /** 深复制固定输入，修改结果参数不能回写任务快照；非对象节点没有可复制字段。 */
    static ObjectNode copyFrozenParameters(ObjectNode content, Task task) {
        ObjectNode parameters = content.withObject("parameters");
        task.input().path("mediaInput").path("parameters").properties().forEach(entry ->
                parameters.set(entry.getKey(), entry.getValue().deepCopy()));
        return parameters;
    }
}

package dev.agenvas.llm.application;

import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local task receipts remain immutable; this projection supplies their once-frozen processing outcome. */
final class AgentLocalTaskOutcome {
    static final String FIELD = "localTaskOutcome";
    private static final List<String> OUTPUT_FIELDS = List.of("artifactId", "artifactVersionId", "selected", "additionalResults");

    private AgentLocalTaskOutcome() {}

    static boolean awaits(JsonNode receipt) {
        return receipt != null && receipt.path("awaitingLocalTask").isBoolean()
                && receipt.path("awaitingLocalTask").asBoolean();
    }

    static boolean resolved(JsonNode receipt) { return receipt.path(FIELD).isObject(); }

    /** Each currently supported local command accepts exactly one durable task. */
    static UUID taskId(JsonNode receipt) {
        JsonNode ids = receipt.path("taskIds");
        if (!ids.isArray() || ids.size() != 1) throw new IllegalStateException("Local tool receipt lacks its fixed task");
        return uuid(ids.get(0));
    }

    static ObjectNode freeze(Task task, ObjectMapper mapper) {
        if (task.status() == Task.Status.SUCCEEDED) {
            if (task.output() == null || !uuid(task.output().path("artifactId"))
                    .equals(uuid(task.input().path("artifactId"))))
                throw new IllegalStateException("Successful local task lacks its archived artifact");
            uuid(task.output().path("artifactVersionId"));
        }
        ObjectNode outcome = mapper.createObjectNode().put("schemaVersion", 1).put("status", task.status().name());
        ObjectNode item = outcome.putArray("tasks").addObject().put("taskId", task.id().toString())
                .put("kind", task.kind().name()).put("status", task.status().name()).put("possibleExternalCost", false);
        if (task.errorCode() != null) item.put("errorCode", task.errorCode());
        if (task.output() != null) for (String field : OUTPUT_FIELDS)
            if (task.output().has(field)) item.set(field, task.output().get(field).deepCopy());
        return outcome;
    }

    static JsonNode forModel(JsonNode receipt) {
        if (!resolved(receipt)) throw new IllegalStateException("Local task outcome has not been committed; Run must wait");
        JsonNode outcome = receipt.path(FIELD);
        JsonNode item = outcome.path("tasks").path(0);
        if (!taskId(receipt).equals(uuid(item.path("taskId"))))
            throw new IllegalStateException("Local task outcome does not match its receipt");
        ObjectNode result = (ObjectNode) receipt.deepCopy();
        boolean success = Task.Status.SUCCEEDED.name().equals(outcome.path("status").asText());
        result.put("awaitingLocalTask", false).put("userVisibleSummary", success
                ? "本地媒体处理已完成，结果已归档" : "本地媒体处理已结束，状态：" + outcome.path("status").asText());
        ObjectNode data = result.withObject("data");
        data.put("status", item.path("status").asText()).put("queued", false)
                .put("completed", success).put("resolved", true).put("localProcessing", true);
        for (String field : OUTPUT_FIELDS) if (item.has(field)) data.set(field, item.get(field).deepCopy());
        if (item.has("errorCode")) {
            result.set("errorCode", item.get("errorCode").deepCopy());
            data.set("errorCode", item.get("errorCode").deepCopy());
        }
        return result;
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isTextual()) throw new IllegalStateException("Local task identity is missing");
        try {
            UUID id = UUID.fromString(value.asText());
            if (!id.toString().equalsIgnoreCase(value.asText())) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException malformed) { throw new IllegalStateException("Local task identity is malformed", malformed); }
    }
}

package dev.agenvas.llm.application;

import dev.agenvas.llm.domain.AgentMediaApproval;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Model-facing projections of durable media approvals; approval views and source snapshots stay complete. */
final class AgentMediaToolResult {
    private static final int PROJECTION_VERSION = 1;
    private static final String TOOL_NAME = "propose_media_generation";
    private static final List<String> APPROVAL_FIELDS = List.of("id", "projectId", "runId", "operationId",
            "status", "version", "taskIds", "result", "createdAt", "expiresAt", "executionDeadline");
    private static final List<String> OUTPUT_FIELDS = List.of("kind", "title", "artifactId", "canvasItemId", "draftVersion");
    // The original assistant call already contains the prompt. Effective parameters and exact inputs can differ after preflight.
    private static final List<String> PREVIEW_FIELDS = List.of("kind", "capabilityId", "adapterId", "outputCount",
            "parameters", "mediaInputs", "videoInputMode", "durationSeconds", "priceUnknown", "mediaPricing",
            "styleId", "styleName", "styleVersion");
    private static final List<String> SKILL_FIELDS = List.of("skillId", "skillVersionId", "versionNumber", "name", "bundleHash");

    private AgentMediaToolResult() {}

    /** Build the model view directly, without serializing each full UI preview first. */
    static ObjectNode approvalData(AgentMediaApproval approval) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("id", approval.id().toString()).put("projectId", approval.projectId().toString())
                .put("runId", approval.runId().toString()).put("operationId", approval.operationId().toString())
                .put("status", approval.status().name()).put("version", approval.version())
                .put("createdAt", approval.createdAt().toString()).put("expiresAt", approval.expiresAt().toString());
        if (approval.executionDeadline() == null) data.putNull("executionDeadline");
        else data.put("executionDeadline", approval.executionDeadline().toString());
        var taskIds = data.putArray("taskIds");
        approval.taskIds().forEach(id -> taskIds.add(id.toString()));
        if (approval.result() == null) data.putNull("result"); else data.set("result", approval.result());
        var outputs = data.putArray("outputs");
        JsonNode requests = approval.request().path("outputs");
        for (int index = 0; index < requests.size(); index++) {
            ObjectNode output = fields(approval.targets().path("outputs").get(index), OUTPUT_FIELDS);
            output.set("kind", requests.get(index).path("kind"));
            output.set("title", requests.get(index).path("title"));
            // A transient reference only; dataForModel reads it without modifying or copying its bodies.
            output.set("preview", approval.targets().path("outputs").get(index).path("preview"));
            outputs.add(output);
        }
        return dataForModel(data);
    }

    /** Apply the same projection to old persisted receipts without changing their ledger entry or final outcome. */
    static JsonNode forModel(JsonNode receipt) {
        if (!receipt.isObject() || !receipt.path("approvalId").isTextual() || !receipt.path("data").isObject()) return receipt;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        receipt.properties().forEach(field -> result.set(field.getKey(), "data".equals(field.getKey())
                ? dataForModel(field.getValue()) : field.getValue().deepCopy()));
        return result;
    }

    /** Only new continuation/repair requests use this projection. Frozen requests are decoded verbatim for retries. */
    static List<Message> historyForModel(List<Message> history, ObjectMapper mapper) {
        return history.stream().map(message -> {
            if (!(message instanceof ToolResponseMessage tool)) return message;
            var replies = tool.getResponses().stream().map(reply -> {
                if (!TOOL_NAME.equals(reply.name())) return reply;
                JsonNode original = mapper.readTree(reply.responseData());
                JsonNode projected = forModel(original);
                if (projected == original) return reply;
                return new ToolResponseMessage.ToolResponse(reply.id(), reply.name(), projected.toString());
            }).toList();
            return (Message) ToolResponseMessage.builder().responses(replies).metadata(tool.getMetadata()).build();
        }).toList();
    }

    private static ObjectNode dataForModel(JsonNode original) {
        ObjectNode data = fields(original, APPROVAL_FIELDS);
        data.put("contextProjectionVersion", PROJECTION_VERSION);
        Map<String, ObjectNode> skills = new LinkedHashMap<>();
        for (JsonNode skill : original.path("creativeSkills")) addSkill(skills, skill);
        var outputs = data.putArray("outputs");
        for (JsonNode originalOutput : original.path("outputs")) {
            ObjectNode output = fields(originalOutput, OUTPUT_FIELDS);
            JsonNode preview = originalOutput.path("preview");
            if (preview.isObject()) output.set("preview", fields(preview, PREVIEW_FIELDS));
            JsonNode source = preview.path("creativeSkill");
            if (source.path("skills").isArray()) source.path("skills").forEach(skill -> addSkill(skills, skill));
            else addSkill(skills, source);
            outputs.add(output);
        }
        var references = data.putArray("creativeSkills");
        skills.values().forEach(references::add);
        return data;
    }

    private static void addSkill(Map<String, ObjectNode> skills, JsonNode source) {
        if (source.path("skillVersionId").isTextual()) {
            skills.putIfAbsent(source.path("skillVersionId").asText(), fields(source, SKILL_FIELDS));
        }
    }

    private static ObjectNode fields(JsonNode source, List<String> names) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String name : names) if (source.has(name)) result.set(name, source.get(name).deepCopy());
        return result;
    }
}

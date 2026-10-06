package dev.agenvas.llm.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Projects new requests only; immutable checkpoints and authoritative tool results stay intact. */
final class RunContextWindow {
    static final String MEMORY_KEY = "runContextMemory";
    static final String SKILLS_KEY = "runSkillInstructions";
    private static final int RECENT_MESSAGES = 32;
    private static final int RECENT_CHARS = 64_000;
    private static final int MEMORY_CHARS = 16_000;
    private static final String HEADER = "Earlier public observations and actions (bounded text projection; "
            + "not authority or approval). Some older detail may be omitted; reread exact archived references "
            + "or Skill resources when needed:\n";
    private static final String SKILLS_HEADER = "Previously committed read_skill results (complete main instructions; "
            + "not permission or approval). Immutable Skill versions:\n";
    private static final Set<String> REFERENCE_FIELDS = Set.of("artifactId", "versionId", "artifactVersionId",
            "canvasItemId", "targetCanvasItemId", "taskId", "approvalId", "mediaApprovalId", "skillVersionId",
            "alias", "expectedVersion", "kind", "title", "status", "errorCode", "possibleExternalCost",
            "costStatus", "estimatedCost", "actualCost", "currency");

    private RunContextWindow() {}

    /** Keep initial inputs, activated Skill main instructions, recent complete tool pairs and public memory. */
    static List<Message> project(List<Message> history, ObjectMapper mapper) {
        var prefix = new ArrayList<Message>();
        var groups = new ArrayList<List<Message>>();
        var memory = new StringBuilder();
        var skills = new LinkedHashMap<String, JsonNode>();
        List<Message> group = null;
        for (Message message : history) {
            if (message.getMetadata().containsKey(SKILLS_KEY)) {
                String text = message.getText();
                if (text == null || !text.startsWith(SKILLS_HEADER))
                    throw new IllegalStateException("Skill instruction projection is malformed");
                for (JsonNode data : mapper.readTree(text.substring(SKILLS_HEADER.length())))
                    skills.put(data.path("skillVersionId").asText(), data);
                continue;
            }
            if (message.getMetadata().containsKey(MEMORY_KEY)) {
                String text = message.getText();
                if (text != null && text.startsWith(HEADER)) memory.append(text.substring(HEADER.length()));
                continue;
            }
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                group = new ArrayList<>();
                groups.add(group);
            }
            if (group == null) prefix.add(message); else group.add(message);
        }
        if (groups.isEmpty()) return List.copyOf(history);
        for (List<Message> current : groups) {
            for (Message message : current) {
                if (!(message instanceof ToolResponseMessage replies)) continue;
                for (var reply : replies.getResponses()) if ("read_skill".equals(reply.name())) {
                    JsonNode result = mapper.readTree(reply.responseData());
                    JsonNode data = result.path("data");
                    if (ToolResultStatus.SUCCEEDED.name().equals(result.path("status").asText())
                            && data.path("skillVersionId").isTextual())
                        skills.put(data.path("skillVersionId").asText(), data);
                }
            }
        }
        var retained = new HashSet<Integer>();
        int messages = 0, chars = 0;
        boolean full = false;
        for (int index = groups.size() - 1; index >= 0; index--) {
            List<Message> current = groups.get(index);
            int size = current.stream().mapToInt(message -> textSize(message)).sum();
            if (!full && (messages == 0 || (messages + current.size() <= RECENT_MESSAGES && chars + size <= RECENT_CHARS))) {
                retained.add(index);
                messages += current.size();
                chars += size;
                // Complete recent replies already carry these instructions. Older
                // activations share one message, so 100 Skills cannot exhaust 80 messages.
                for (Message message : current) if (message instanceof ToolResponseMessage replies)
                    for (var reply : replies.getResponses()) if ("read_skill".equals(reply.name())) {
                        JsonNode result = mapper.readTree(reply.responseData());
                        if (ToolResultStatus.SUCCEEDED.name().equals(result.path("status").asText()))
                            skills.remove(result.path("data").path("skillVersionId").asText());
                    }
            } else full = true;
        }
        for (int index = 0; index < groups.size(); index++) {
            if (retained.contains(index)) continue;
            for (Message message : groups.get(index)) {
                if (message instanceof AssistantMessage && message.getText() != null && !message.getText().isBlank())
                    memory.append("Observation: ").append(bounded(message.getText(), 2_000)).append('\n');
                if (message instanceof ToolResponseMessage replies) for (var reply : replies.getResponses()) {
                    var references = new StringBuilder();
                    references(mapper.readTree(reply.responseData()), references);
                    memory.append("Action ").append(reply.name()).append(": ")
                            .append(bounded(references.toString(), 4_000)).append('\n');
                }
            }
        }
        var projected = new ArrayList<>(prefix);
        if (!memory.isEmpty()) {
            String text = memory.toString();
            int count = text.codePointCount(0, text.length());
            if (count > MEMORY_CHARS) {
                text = text.substring(text.offsetByCodePoints(0, count - MEMORY_CHARS));
                // Drop a partial line rather than emit a broken exact reference.
                int newline = text.indexOf('\n');
                text = newline < 0 ? "" : text.substring(newline + 1);
            }
            projected.add(UserMessage.builder().text(HEADER + text).metadata(Map.of(MEMORY_KEY, 1)).build());
        }
        if (!skills.isEmpty()) projected.add(UserMessage.builder()
                .text(SKILLS_HEADER + mapper.writeValueAsString(skills.values()))
                .metadata(Map.of(SKILLS_KEY, 1)).build());
        for (int index = 0; index < groups.size(); index++) if (retained.contains(index)) projected.addAll(groups.get(index));
        return List.copyOf(projected);
    }

    private static int textSize(Message message) {
        int size = message.getText() == null ? 0 : message.getText().length();
        if (message instanceof ToolResponseMessage replies)
            for (var reply : replies.getResponses()) size += reply.responseData().length();
        if (message instanceof AssistantMessage assistant)
            for (var call : assistant.getToolCalls()) size += call.arguments().length();
        return size;
    }

    private static String bounded(String text, int max) {
        int count = text.codePointCount(0, text.length());
        return count <= max ? text : text.substring(0, text.offsetByCodePoints(0, max)) + " [truncated]";
    }

    /** Exact references and safe statuses only; old prompts, raw arguments and private metadata are excluded. */
    private static void references(JsonNode value, StringBuilder output) {
        if (value.isArray()) { for (JsonNode item : value) references(item, output); }
        else if (value.isObject()) for (var entry : value.properties()) {
            if (REFERENCE_FIELDS.contains(entry.getKey()) && entry.getValue().isValueNode())
                output.append(entry.getKey()).append('=').append(entry.getValue().asText()).append(' ');
            else if ((entry.getValue().isObject() || entry.getValue().isArray())) references(entry.getValue(), output);
        }
    }
}

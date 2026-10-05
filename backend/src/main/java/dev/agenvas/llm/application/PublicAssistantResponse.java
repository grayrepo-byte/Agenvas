package dev.agenvas.llm.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** Removes provider-private thought fields before either public streaming or response checkpoints. */
public final class PublicAssistantResponse {
    private static final String THOUGHT_FLAG = "isThought";
    private static final String PUBLIC_OUTPUT = "outputWithoutThoughts";
    private static final Set<String> PRIVATE_FIELDS = Set.of("reasoningContent", "reasoning_content",
            "reasoning", "reasoningText", "reasoning_text", "reasoningDetails", "reasoning_details",
            "analysis", "thoughts", "thought", "thinking", THOUGHT_FLAG, PUBLIC_OUTPUT);

    private PublicAssistantResponse() {}

    /** Tools, opaque protocol data, usage and finish reasons remain available for caller-driven execution. */
    public static ChatResponse sanitize(ChatResponse response) {
        if (response == null) throw new IllegalStateException("Model returned no response");
        List<Generation> generations = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage safe = sanitize(generation.getOutput());
            Object thoughtFlag = generation.getMetadata().get(THOUGHT_FLAG);
            if (Boolean.parseBoolean(String.valueOf(thoughtFlag))) {
                safe = AssistantMessage.builder().content("").properties(safe.getMetadata())
                        .toolCalls(safe.getToolCalls()).media(safe.getMedia()).build();
            }
            ChatGenerationMetadata originalMetadata = generation.getMetadata();
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .finishReason(originalMetadata.getFinishReason())
                    .contentFilters(originalMetadata.getContentFilters())
                    .metadata(safeMetadata(entries(originalMetadata.entrySet()))).build();
            generations.add(new Generation(safe, metadata));
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .metadata(safeMetadata(entries(response.getMetadata().entrySet())))
                .id(response.getMetadata().getId()).model(response.getMetadata().getModel())
                .usage(response.getMetadata().getUsage()).rateLimit(response.getMetadata().getRateLimit())
                .promptMetadata(response.getMetadata().getPromptMetadata()).build();
        return new ChatResponse(generations, metadata);
    }

    /** Applied to response checkpoints and restored assistant history alike. */
    public static AssistantMessage sanitize(AssistantMessage original) {
        String text = original.getText();
        Object publicOutput = original.getMetadata().get(PUBLIC_OUTPUT);
        if (publicOutput instanceof String value) text = value;
        else if (Boolean.parseBoolean(String.valueOf(original.getMetadata().get(THOUGHT_FLAG)))) text = "";
        return AssistantMessage.builder().content(text).properties(safeMetadata(original.getMetadata()))
                .toolCalls(original.getToolCalls()).media(original.getMedia()).build();
    }

    /** Only the selected assistant's public content enters SSE; tools and metadata never do. */
    public static String text(ChatResponse response) {
        if (response.getResult() == null) return "";
        String value = response.getResult().getOutput().getText();
        return value == null ? "" : value;
    }

    private static Map<String, Object> entries(Set<Map.Entry<String, Object>> entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        entries.forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return values;
    }

    private static Map<String, Object> safeMetadata(Map<String, ?> original) {
        Map<String, Object> safe = new LinkedHashMap<>();
        original.forEach((key, value) -> {
            if (!PRIVATE_FIELDS.contains(key)) safe.put(key, safeValue(value));
        });
        return safe;
    }

    private static Object safeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (key instanceof String text && !PRIVATE_FIELDS.contains(text)) {
                    safe.put(text, safeValue(nested));
                }
            });
            return safe;
        }
        if (value instanceof List<?> list) return list.stream().map(PublicAssistantResponse::safeValue).toList();
        return value;
    }
}

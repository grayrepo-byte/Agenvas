package dev.agenvas.llm.application;

import java.util.List;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;

/** One estimator for Agent and direct text input; provider tokenizers can still differ. */
final class LlmTokenEstimator {
    private LlmTokenEstimator() {
    }
    static int estimate(List<Message> messages, List<ToolCallback> tools) {
        var tokens = new JTokkitTokenCountEstimator();
        long total = 0;
        for (var message : messages) {
            total += 16 + count(tokens, message.getText());
            if (message instanceof AssistantMessage assistant)
                for (var call : assistant.getToolCalls())
                    total += count(tokens, call.name()) + count(tokens, call.arguments()) + 16;
            if (message instanceof ToolResponseMessage replies)
                for (var reply : replies.getResponses())
                    total += count(tokens, reply.responseData()) + 16;
            long attachments = message instanceof UserMessage user ? user.getMedia().size() : 0;
            for (String key : List.of(AgentImageInputService.METADATA_KEY, AgentImageInputService.SKILL_METADATA_KEY,
                    AgentMediaSampleService.METADATA_KEY, ImagePromptInputService.METADATA_KEY)) {
                Object manifest = message.getMetadata().get(key);
                attachments += manifest instanceof java.util.Collection<?> values
                        ? values.size()
                        : manifest instanceof tools.jackson.databind.JsonNode node && node.isArray()
                                ? node.size()
                                : manifest == null ? 0 : 1;
            }
            total += attachments * 4096;
        }
        for (var tool : tools) {
            var definition = tool.getToolDefinition();
            total += count(tokens, definition.name()) + count(tokens, definition.description())
                    + count(tokens, definition.inputSchema()) + 16;
        }
        return (int) Math.min(Integer.MAX_VALUE, (total * 5 + 3) / 4);
    }
    private static int count(JTokkitTokenCountEstimator tokens, String value) {
        return value == null || value.isEmpty() ? 0 : tokens.estimate(value);
    }
}

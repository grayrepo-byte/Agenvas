package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Verifies that durable protocol JSON can rebuild a model continuation without losing IDs. */
class LlmProtocolCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);

    @Test
    void restoresRequestAssistantMetadataAndOrderedToolReplies() {
        AssistantMessage assistant = AssistantMessage.builder().content("")
                .properties(Map.of("providerProtocol", "opaque-continuation"))
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-2", "function", "create_scene", "{}"),
                        new AssistantMessage.ToolCall("call-1", "function", "create_text", "{}")))
                .build();
        List<Message> prior = List.of(new SystemMessage("Create a storyboard"),
                new UserMessage("Plan three shots"));
        List<Message> restored = codec.requestMessages(codec.request(prior, List.of()));
        assertThat(restored).extracting(Message::getText)
                .containsExactly("Create a storyboard", "Plan three shots");

        AssistantMessage recovered = codec.selectedAssistant(
                codec.response(new ChatResponse(List.of(new Generation(assistant)))));
        assertThat(recovered.getMetadata())
                .containsEntry("providerProtocol", "opaque-continuation");
        assertThat(recovered.getToolCalls()).extracting(AssistantMessage.ToolCall::id)
                .containsExactly("call-2", "call-1");
        ToolResponseMessage reply = codec.toolResults(recovered, Map.of(
                "call-1", mapper.createObjectNode().put("status", "SUCCEEDED"),
                "call-2", mapper.createObjectNode().put("status", "WAITING_APPROVAL")));
        assertThat(reply.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call-2", "call-1");
        assertThat(codec.requestMessages(codec.request(List.of(restored.get(0),
                restored.get(1), recovered, reply), List.of())).get(3))
                .isEqualTo(reply);
    }

    @Test
    void rejectsUnsupportedOrIncompleteSavedProtocol() {
        ObjectNode unsupported = codec.request(List.of(new UserMessage("hello")), List.of());
        unsupported.put("schemaVersion", 2);
        assertThatThrownBy(() -> codec.requestMessages(unsupported))
                .isInstanceOf(IllegalArgumentException.class);

        AssistantMessage assistant = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "create_text", "{}"))).build();
        assertThatThrownBy(() -> codec.toolResults(assistant, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode response = codec.response(new ChatResponse(List.of(new Generation(assistant))));
        response.put("schemaVersion", 0);
        assertThatThrownBy(() -> codec.selectedAssistant(response))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

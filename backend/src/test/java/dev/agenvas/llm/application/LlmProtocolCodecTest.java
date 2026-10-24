package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Verifies that durable protocol JSON can rebuild a model continuation without losing IDs. */
class LlmProtocolCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);

    @Test
    void restoresMediaContinuationLargerThanTheOldRequestLimit() {
        AssistantMessage assistant = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "media-call", "function", "propose_media_generation", "{}"))).build();
        ObjectNode result = mapper.createObjectNode().put("status", "SUCCEEDED");
        // Synthetic repeated Skill previews reproduce a completed media batch's large receipt.
        result.put("creativeSkill", "创作规范".repeat(50_000));
        ToolResponseMessage reply = codec.toolResults(assistant, Map.of("media-call", result));
        List<Message> continuation = List.of(new UserMessage("Continue from the completed media"),
                assistant, reply);

        ObjectNode request = codec.request(continuation, List.of());

        assertThat(request.toString().getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(512 * 1024);
        assertThat(codec.requestMessages(request)).containsExactlyElementsOf(continuation);
    }

    @Test
    void acceptsExact32MiBUtf8RequestAndRejectsOneExtraByteOnWriteAndRestore() throws Exception {
        int limitBytes = 32 * 1024 * 1024;
        ObjectNode empty = codec.request(List.of(new UserMessage(""), new UserMessage("")), List.of());
        int envelopeBytes = empty.toString().getBytes(StandardCharsets.UTF_8).length;
        // The supplementary Unicode character occupies four UTF-8 bytes, not two Java chars.
        // Split history into messages so the JSON round trip also respects Jackson's per-string bound.
        String first = "😀" + "x".repeat(limitBytes / 2 - 4);
        String last = "x".repeat(limitBytes / 2 - envelopeBytes);
        List<Message> messages = List.of(new UserMessage(first), new UserMessage(last));
        ObjectNode request = codec.request(messages, List.of());

        assertThat(request.toString().getBytes(StandardCharsets.UTF_8)).hasSize(limitBytes);
        assertThat(codec.requestMessages(mapper.readTree(request.toString()))).containsExactlyElementsOf(messages);
        assertThatThrownBy(() -> codec.request(List.of(new UserMessage(first), new UserMessage(last + "x")), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
        ((ObjectNode) request.path("messages").get(1)).put("text", last + "x");
        assertThatThrownBy(() -> codec.requestMessages(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
    }

    @Test
    void acceptsExact8MiBUtf8ResponseAndRejectsOneExtraByteOnWriteAndRestore() {
        int limitBytes = 8 * 1024 * 1024;
        ObjectNode empty = codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("")))));
        int envelopeBytes = empty.toString().getBytes(StandardCharsets.UTF_8).length;
        String text = "😀" + "x".repeat(limitBytes - envelopeBytes - 4);
        ObjectNode response = codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));

        assertThat(response.toString().getBytes(StandardCharsets.UTF_8)).hasSize(limitBytes);
        assertThat(codec.selectedAssistant(response).getText()).isEqualTo(text);
        assertThatThrownBy(() -> codec.response(new ChatResponse(
                List.of(new Generation(new AssistantMessage(text + "x"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
        ((ObjectNode) response.path("generations").get(0).path("assistant")).put("text", text + "x");
        assertThatThrownBy(() -> codec.selectedAssistant(response))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size limit");
    }

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

    @Test
    void restoresExactSavedToolDefinitionsWithoutAnExecutableCallback() {
        ObjectNode request = requestWithTool("read_artifacts");
        String oldSchema = "{\n  \"type\":\"object\", \"properties\":{\"versionIds\":{\"type\":\"array\"}}\n}";
        ((ObjectNode) request.at("/tools/0")).put("description", "Original description\nwith preserved whitespace")
                .put("inputSchema", oldSchema);
        request.withArray("tools").addObject().put("name", "read_canvas").put("description", "Original directory")
                .put("inputSchema", "{\"type\":\"object\",\"properties\":{}}");

        List<ToolCallback> restored = codec.requestTools(request, List.of("read_canvas", "read_artifacts"));

        assertThat(restored).extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("read_artifacts", "read_canvas");
        assertThat(restored.getFirst().getToolDefinition().inputSchema()).isEqualTo(oldSchema);
        assertThat(codec.request(codec.requestMessages(request), restored)).isEqualTo(request);
        assertThatThrownBy(() -> restored.getFirst().call("{}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("durable business executor");
    }

    @Test
    void rejectsUnknownDuplicateOrMalformedSavedToolDefinitions() {
        var allowed = List.of("read_artifacts", "read_canvas");
        ObjectNode unknown = requestWithTool("run_shell");
        assertThatThrownBy(() -> codec.requestTools(unknown, allowed)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode duplicate = requestWithTool("read_artifacts");
        duplicate.withArray("tools").add(duplicate.at("/tools/0").deepCopy());
        assertThatThrownBy(() -> codec.requestTools(duplicate, allowed)).isInstanceOf(IllegalArgumentException.class);

        for (String schema : List.of("not json", "null", "[]", "{\"type\":\"string\"}", "{}")) {
            ObjectNode request = requestWithTool("read_artifacts");
            ((ObjectNode) request.at("/tools/0")).put("inputSchema", schema);
            assertThatThrownBy(() -> codec.requestTools(request, allowed)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String field : List.of("name", "description", "inputSchema")) {
            ObjectNode request = requestWithTool("read_artifacts");
            ((ObjectNode) request.at("/tools/0")).putNull(field);
            assertThatThrownBy(() -> codec.requestTools(request, allowed)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode extra = requestWithTool("read_artifacts");
        ((ObjectNode) extra.at("/tools/0")).put("callback", "execute directly");
        assertThatThrownBy(() -> codec.requestTools(extra, allowed)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode absent = requestWithTool("read_artifacts");
        absent.remove("tools");
        assertThatThrownBy(() -> codec.requestTools(absent, allowed)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode wrongType = requestWithTool("read_artifacts");
        wrongType.putObject("tools");
        assertThatThrownBy(() -> codec.requestTools(wrongType, allowed)).isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode requestWithTool(String name) {
        ObjectNode request = codec.request(List.of(new UserMessage("Frozen request")), List.of());
        request.withArray("tools").addObject().put("name", name).put("description", "Frozen definition")
                .put("inputSchema", "{\"type\":\"object\",\"properties\":{}}");
        return request;
    }
}

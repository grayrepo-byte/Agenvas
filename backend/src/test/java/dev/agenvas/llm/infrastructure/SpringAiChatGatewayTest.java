package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import dev.agenvas.llm.application.LlmProperties;

/** Exact Spring AI 2.0.1 protocol test for one-call turns and caller-driven tool replies. */
class SpringAiChatGatewayTest {

    @Test
    void oneModelCallExposesToolIdWithoutExecutingItAndReplyRoundPreservesProtocol() {
        ProbeModel model = new ProbeModel();
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 7);
        assertThat(gateway.capabilities().toolCalling()).isTrue();
        assertThat(gateway.capabilities().vision()).isFalse();
        assertThat(gateway.capabilities().nativeStructuredOutput()).isFalse();
        assertThat(gateway.modelDetails().available()).isTrue();
        assertThat(gateway.modelDetails().providerAdapter()).isEqualTo("ProbeModel");
        assertThat(gateway.modelDetails().toolCalling()).isTrue();
        ProbeTool tool = new ProbeTool();
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("Use the read_project_summary tool."));
        history.add(new UserMessage("What is the project?"));
        Map<String, Object> trustedContext = Map.of("projectId", "server-owned-project");

        var first = gateway.call(history, List.of(tool), trustedContext);
        assertThat(first.configVersion()).isEqualTo(7);
        assertThat(model.calls).isEqualTo(1);
        assertThat(tool.executions.get()).isZero();
        AssistantMessage assistant = first.response().getResult().getOutput();
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(assistant.getToolCalls().getFirst().id()).isEqualTo("call-42");
        assertThat(assistant.getMetadata()).containsEntry("providerProtocol", "opaque-value");
        assertThat(first.response().getMetadata().getId()).isEqualTo("response-1");
        assertThat(first.response().getMetadata().<String>get("providerRequestId"))
                .isEqualTo("remote-1");

        String result = tool.call("{}");
        history.add(assistant);
        history.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        assistant.getToolCalls().getFirst().id(), "read_project_summary", result)))
                .build());
        var second = gateway.call(history, List.of(tool), trustedContext);
        assertThat(model.calls).isEqualTo(2);
        assertThat(tool.executions.get()).isEqualTo(1);
        assertThat(second.response().getResult().getOutput().getText()).isEqualTo("Project summary received.");
        assertThat(second.response().hasToolCalls()).isFalse();
    }

    @Test
    void rejectsToolRequestsWhenModelDoesNotAdvertiseToolOptions() {
        ChatModel textOnly = prompt -> {
            throw new AssertionError("Unsupported model must not be called with a tool request");
        };
        SpringAiChatGateway gateway = new SpringAiChatGateway(textOnly, 1);
        assertThat(gateway.capabilities().toolCalling()).isFalse();
        assertThat(gateway.modelDetails().toolCalling()).isFalse();
        assertThatThrownBy(() -> gateway.call(List.of(new UserMessage("Use a tool")),
                List.of(new ProbeTool()), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support tool calling");
    }

    @Test
    void mockOnlyModeStartsWithoutChatModelAndRejectsCallsExplicitly() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        ConfiguredChatGateway gateway = new ConfiguredChatGateway(models,
                new LlmProperties(1, false));
        assertThat(gateway.capabilities().toolCalling()).isFalse();
        assertThat(gateway.modelDetails().available()).isFalse();
        assertThatThrownBy(() -> gateway.call(List.of(new UserMessage("Hello")),
                List.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No LLM ChatModel");
    }

    @Test
    void configuredAdapterDoesNotClaimUnverifiedRemoteToolCapability() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(new ProbeModel());
        ConfiguredChatGateway unverified = new ConfiguredChatGateway(models,
                new LlmProperties(1, false));
        assertThat(unverified.modelDetails().available()).isTrue();
        assertThat(unverified.modelDetails().toolCalling()).isFalse();
        assertThat(unverified.capabilities().toolCalling()).isFalse();
        ConfiguredChatGateway verified = new ConfiguredChatGateway(models,
                new LlmProperties(1, true));
        assertThat(verified.capabilities().toolCalling()).isTrue();
    }

    /** Fake model verifies both prompt context and the assistant/tool message pairing. */
    private static final class ProbeModel implements ChatModel {
        private int calls;

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            assertThat(options.getToolContext()).containsEntry("projectId", "server-owned-project");
            if (calls == 1) {
                AssistantMessage output = AssistantMessage.builder()
                        .content("")
                        .properties(Map.of("providerProtocol", "opaque-value"))
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-42", "function", "read_project_summary", "{}")))
                        .build();
                return new ChatResponse(List.of(new Generation(output)),
                        ChatResponseMetadata.builder().id("response-1").model("mock-chat")
                                .keyValue("providerRequestId", "remote-1").build());
            }
            assertThat(prompt.getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
            ToolResponseMessage toolReply = (ToolResponseMessage) prompt.getInstructions().get(3);
            assertThat(toolReply.getResponses()).hasSize(1);
            assertThat(toolReply.getResponses().getFirst().id()).isEqualTo("call-42");
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("Project summary received."))));
        }
    }

    /** Counts actual callback execution to detect accidental advisor-driven double calls. */
    private static final class ProbeTool implements ToolCallback {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("read_project_summary")
                    .description("Read the project summary")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            executions.incrementAndGet();
            return "{\"name\":\"Mock project\"}";
        }
    }
}

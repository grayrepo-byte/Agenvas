package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.llm.infrastructure.SpringAiChatGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/** Synthetic model chunks verify privacy at both the streaming and checkpoint boundaries. */
class PublicAssistantResponseTest {
    @Test
    void stripsThoughtChunksAndNestedPrivateMetadataBeforeStreamingAndCheckpointing() {
        AtomicInteger toolsExecuted = new AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_project_summary").description("Read project")
                        .inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String input) { toolsExecuted.incrementAndGet(); return "{}"; }
        };
        ChatModel model = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("The real streaming path is required"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        response("private-thought-text", Map.of("isThought", true, "reasoningContent", "private-metadata"), List.of()),
                        response("Visible ", Map.of("isThought", false, "providerProtocol", "opaque-protocol"), List.of()),
                        response("answer", Map.of("nested", Map.of("reasoning_content", "private-nested", "publicId", "public-1")),
                                List.of(new AssistantMessage.ToolCall("call-1", "function", "read_project_summary", "{}"))));
            }
        };
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 3);
        List<String> deltas = new ArrayList<>();
        var exchange = gateway.callStreaming(List.of(new UserMessage("Hello")), List.of(tool), Map.of(),
                gateway.configIdentity(), deltas::add);
        assertThat(String.join("", deltas)).isEqualTo("Visible answer");
        assertThat(exchange.response().getResult().getOutput().getText()).isEqualTo("Visible answer");
        assertThat(exchange.response().getMetadata().getUsage()).isInstanceOf(EmptyUsage.class);
        assertThat(exchange.response().getResult().getOutput().getToolCalls().getFirst().id()).isEqualTo("call-1");
        assertThat(toolsExecuted).hasValue(0);
        var checkpoint = new LlmProtocolCodec(new ObjectMapper()).response(exchange.response());
        assertThat(checkpoint.toString()).doesNotContain("private-thought-text", "private-metadata", "private-nested",
                "private-generation", "private-response", "reasoningContent", "isThought", "thoughts");
        assertThat(checkpoint.toString()).contains("opaque-protocol", "call-1", "public-1", "opaque-response");
        assertThat(checkpoint.path("metadata").path("usage").isNull()).isTrue();
    }

    @Test
    void usesPublicProjectionFromPreviouslyAggregatedResponseAndPreservesToolAssociation() {
        ChatResponse original = response("hidden thought plus public answer", Map.of("thoughts", "private-history",
                "outputWithoutThoughts", "public answer", "reasoningContent", "private-reasoning"),
                List.of(new AssistantMessage.ToolCall("call-42", "function", "read_project_summary", "{}")));
        var codec = new LlmProtocolCodec(new ObjectMapper());
        var saved = codec.response(original);
        assertThat(codec.selectedAssistant(saved).getText()).isEqualTo("public answer");
        assertThat(codec.selectedAssistant(saved).getToolCalls().getFirst().id()).isEqualTo("call-42");
        assertThat(saved.toString()).doesNotContain("hidden thought", "private-history", "private-reasoning");
    }

    @Test
    void repeatedSmallChunkEnvelopesDoNotConsumeTheRetainedProtocolBudget() {
        ChatModel model = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("Streaming required"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.range(0, 5000).map(index -> response("x", Map.of("providerProtocol", "same-opaque-value"), List.of()));
            }
        };
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 3);
        List<String> deltas = new ArrayList<>();
        var exchange = gateway.callStreaming(List.of(new UserMessage("Hello")), List.of(), Map.of(), gateway.configIdentity(), deltas::add);
        assertThat(String.join("", deltas)).hasSize(5000);
        assertThat(exchange.response().getResult().getOutput().getText()).hasSize(5000);
    }

    @Test
    void boundsToolAndOpaqueMetadataChunksBeforeOurAggregatorCanRetainAnUnboundedResponse() {
        AtomicBoolean canceled = new AtomicBoolean();
        AtomicInteger chunks = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("No retry or synchronous fallback"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.range(0, 10000).map(index -> {
                    chunks.incrementAndGet();
                    return response("", Map.of("providerProtocol", "opaque".repeat(2000)),
                            List.of(new AssistantMessage.ToolCall("call-" + index, "function", "read_project_summary", "x".repeat(16000))));
                }).doOnCancel(() -> canceled.set(true));
            }
        };
        SpringAiChatGateway gateway = new SpringAiChatGateway(model, 3);
        List<String> deltas = new ArrayList<>();
        assertThatThrownBy(() -> gateway.callStreaming(List.of(new UserMessage("Hello")), List.of(), Map.of(),
                gateway.configIdentity(), deltas::add)).isInstanceOf(RuntimeException.class).hasMessageContaining("size limit");
        assertThat(chunks.get()).isLessThan(10000);
        assertThat(canceled).isTrue();
        assertThat(deltas).isEmpty();
    }

    @Test
    void generationThoughtFlagAlsoSuppressesTheEntirePrivateChunk() {
        var codec = new LlmProtocolCodec(new ObjectMapper());
        var original = new ChatResponse(List.of(new Generation(new AssistantMessage("private-generation-content"),
                ChatGenerationMetadata.builder().metadata(Map.of("isThought", true, "analysis", "private-analysis")).build())));
        var saved = codec.response(original);
        assertThat(codec.selectedAssistant(saved).getText()).isEmpty();
        assertThat(saved.toString()).doesNotContain("private-generation-content", "private-analysis", "isThought");
    }

    @Test
    void oldAssistantHistoryCannotRepersistThoughtTextOrMetadataInTheNextRequest() {
        var codec = new LlmProtocolCodec(new ObjectMapper());
        AssistantMessage history = AssistantMessage.builder().content("private-old-thought")
                .properties(Map.of("isThought", true, "reasoningContent", "private-old-metadata", "providerProtocol", "opaque-old"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("old-call", "function", "read_project_summary", "{}"))).build();
        var request = codec.request(List.of(new UserMessage("Hello"), history), List.of());
        assertThat(request.toString()).doesNotContain("private-old-thought", "private-old-metadata");
        assertThat(request.toString()).contains("opaque-old", "old-call");
    }

    @Test
    void deterministicGatewayFallbackEmitsOneSanitizedPublicTextBlock() {
        ChatGateway gateway = new ChatGateway() {
            @Override public Exchange call(List<org.springframework.ai.chat.messages.Message> messages,
                    List<ToolCallback> tools, Map<String, Object> context) {
                return new Exchange(1, response("public answer", Map.of("reasoningContent", "private-fallback"), List.of()));
            }
            @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
            @Override public int configVersion() { return 1; }
            @Override public String configSource() { return "mock"; }
        };
        List<String> deltas = new ArrayList<>();
        var result = gateway.callStreaming(List.of(new UserMessage("Hello")), List.of(), Map.of(), gateway.configIdentity(), deltas::add);
        assertThat(deltas).containsExactly("public answer");
        assertThat(new LlmProtocolCodec(new ObjectMapper()).response(result.response()).toString()).doesNotContain("private-fallback");
    }

    private static ChatResponse response(String content, Map<String, Object> metadata, List<AssistantMessage.ToolCall> tools) {
        AssistantMessage assistant = AssistantMessage.builder().content(content).properties(metadata).toolCalls(tools).build();
        return new ChatResponse(List.of(new Generation(assistant, ChatGenerationMetadata.builder()
                .finishReason("stop").metadata(Map.of("reasoningContent", "private-generation")).build())),
                ChatResponseMetadata.builder().id("response-1").model("synthetic-model")
                        .keyValue("reasoningContent", "private-response").keyValue("providerProtocol", "opaque-response").build());
    }
}

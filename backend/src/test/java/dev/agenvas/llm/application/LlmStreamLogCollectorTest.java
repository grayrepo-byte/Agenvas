package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import dev.agenvas.audit.domain.LlmStreamLog.EndStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class LlmStreamLogCollectorTest {
    private final AtomicLong nanos = new AtomicLong();

    @Test void distinguishesMetadataOnlyFirstChunkFirstTextAndKeepsActualUsage() {
        var log = new LlmStreamLogCollector(true, nanos::get);
        time(10); log.chunk(chunk("", false));
        time(35); log.chunk(chunk("first", false));
        time(90); log.chunk(chunk(" second", true));
        time(100); log.complete(chunk("first second", true));
        var snapshot = log.snapshot(EndStatus.COMPLETED, null);
        assertThat(snapshot.metrics().firstChunkMs()).isEqualTo(10L);
        assertThat(snapshot.metrics().firstTextMs()).isEqualTo(35L);
        assertThat(snapshot.metrics().durationMs()).isEqualTo(100L);
        assertThat(snapshot.metrics().chunkCount()).isEqualTo(3);
        assertThat(snapshot.metrics().promptTokens()).isEqualTo(4);
        assertThat(snapshot.metrics().completionTokens()).isEqualTo(2);
        assertThat(snapshot.metrics().totalTokens()).isEqualTo(6);
        assertThat(snapshot.metrics().finishReasons()).containsExactly("stop");
        assertThat(snapshot.content().response()).contains("first second", "synthetic-response");
    }

    @Test void failureKeepsAllReceivedTextAndToolPrefixAndMissingUsageIsUnknown() {
        var log = new LlmStreamLogCollector(true, nanos::get);
        time(10); log.chunk(chunk("published", false));
        time(15); log.chunk(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(" tail")
                .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-tool", "function", "read", "{}"))).build()))));
        var failed = log.snapshot(EndStatus.FAILED, "CALL_STREAM_FAILED");
        assertThat(failed.content().response()).contains("published tail", "synthetic-tool");
        assertThat(failed.metrics().totalTokens()).isNull();
        // A subsequent callback cannot mutate a snapshot already handed to the writer.
        log.chunk(chunk(" late", false));
        assertThat(failed.content().response()).contains("published tail").doesNotContain("late");
    }

    @Test void metricsWithoutDebugDoNotRetainBodiesAndToolOnlyStreamsHaveNoFirstText() {
        var log = new LlmStreamLogCollector(false, nanos::get);
        time(5); log.chunk(chunk("", false));
        var snapshot = log.snapshot(EndStatus.CANCELED, "CALL_STREAM_CANCELED");
        assertThat(snapshot.content()).isNull();
        assertThat(snapshot.metrics().firstTextMs()).isNull();
        assertThat(snapshot.metrics().totalTokens()).isNull();
    }

    @Test void contentLimitMarksPartialLogWithoutStoppingSubsequentMetrics() {
        var log = new LlmStreamLogCollector(true, nanos::get);
        log.chunk(chunk("prefix", false));
        log.chunk(chunk("x".repeat(1024 * 1024), false));
        var snapshot = log.snapshot(EndStatus.FAILED, "CALL_STREAM_FAILED");
        assertThat(snapshot.content().truncated()).isTrue();
        assertThat(snapshot.content().response()).contains("prefix").hasSizeLessThan(1024 * 1024);
        assertThat(snapshot.metrics().chunkCount()).isEqualTo(2);
    }

    @Test void debugKeepsActualModelFieldsWhileFirstTextMeasuresOnlyPublicText() {
        var log = new LlmStreamLogCollector(true, nanos::get);
        time(5); log.chunk(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("<think>synthetic thought</think>")
                .properties(Map.of("reasoningContent", "synthetic reasoning", "channel", "analysis", "isThought", true)).build()))));
        time(25); log.chunk(chunk("synthetic answer", true));
        log.complete(chunk("synthetic answer", true));
        var snapshot = log.snapshot(EndStatus.COMPLETED, null);
        assertThat(snapshot.metrics().firstChunkMs()).isEqualTo(5L);
        assertThat(snapshot.metrics().firstTextMs()).isEqualTo(25L);
        assertThat(snapshot.content().response()).contains("synthetic thought", "synthetic reasoning", "analysis", "synthetic answer");
    }

    @Test void completionRetainsAssembledToolArgumentsAndRawModelAttributes() {
        var log = new LlmStreamLogCollector(true, nanos::get);
        log.chunk(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", "synthetic tool reasoning"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("synthetic-tool", "function", "read", "{\"id\":"))).build()))));
        var assembled = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("synthetic-tool", "function", "read", "{\"id\":\"synthetic-id\"}"))).build();
        log.complete(new ChatResponse(List.of(new Generation(assembled,
                ChatGenerationMetadata.builder().finishReason("tool_calls").build()))));
        var snapshot = log.snapshot(EndStatus.COMPLETED, null);
        assertThat(snapshot.content().response()).contains("synthetic tool reasoning", "synthetic-id", "tool_calls");
        assertThat(new tools.jackson.databind.ObjectMapper().readTree(snapshot.content().response())
                .path("generations").path(0).path("assistant").path("toolCalls").size()).isEqualTo(1);
    }

    private void time(long milliseconds) { nanos.set(milliseconds * 1_000_000); }
    private ChatResponse chunk(String text, boolean usage) {
        var metadata = ChatResponseMetadata.builder().model("synthetic-model").id("synthetic-response");
        if (usage) metadata.usage(new DefaultUsage(4, 2, 6));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(usage ? "stop" : "").build())), metadata.build());
    }
}

package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.audit.domain.LlmStreamLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Synthetic reactive model exercises the real ChatClient/aggregator/batching pipeline. */
class SpringAiStreamLogTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test void waitsForInFlightPublicBatchBeforeCompletingTheResponse() throws Exception {
        var source = reactor.core.publisher.Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        var gateway = gateway(source.asFlux());
        CountDownLatch writing = new CountDownLatch(1), releaseWrite = new CountDownLatch(1);
        List<String> outputs = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        try (var caller = Executors.newSingleThreadExecutor()) {
            Future<dev.agenvas.llm.application.ChatGateway.Exchange> call = caller.submit(() -> gateway.callStreaming(
                    List.of(new UserMessage("test")), List.of(), Map.of(), gateway.configIdentity(), delta -> {
                        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try {
                            writing.countDown();
                            if (!releaseWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("Write not released");
                            outputs.add(delta);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        } finally { active.decrementAndGet(); }
                    }));
            source.emitNext(chunk("Test "), reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST);
            assertThat(writing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            source.emitNext(chunk("complete"), reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST);
            source.emitComplete(reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST);
            releaseWrite.countDown();
            var result = call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(maxActive).hasValue(1);
            assertThat(String.join("", outputs)).isEqualTo("Test complete");
            assertThat(result.response().getResult().getOutput().getText()).isEqualTo("Test complete");
        } finally { releaseWrite.countDown(); }
    }

    @Test void completesWithPublicResponseAndSeparateDebugContentFromTheSameSubscription() {
        var tool = new AssistantMessage.ToolCall("synthetic-tool", "function", "read", "{}");
        var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("answer")
                .properties(Map.of("reasoningContent", "private reasoning")).toolCalls(List.of(tool)).build(),
                ChatGenerationMetadata.builder().finishReason("tool_calls").build())),
                ChatResponseMetadata.builder().model("synthetic-model").id("synthetic-response")
                        .usage(new DefaultUsage(4, 2, 6)).build());
        var gateway = gateway(Flux.just(response));
        var saved = new AtomicReference<LlmStreamLog>();
        var outputs = new StringBuilder();
        var result = gateway.callStreaming(List.of(new UserMessage("test")), List.of(), Map.of(), gateway.configIdentity(),
                outputs::append, true, saved::set);
        assertThat(result.response().getResult().getOutput().getText()).isEqualTo("answer");
        assertThat(result.response().getResult().getOutput().getMetadata()).doesNotContainKey("reasoningContent");
        assertThat(result.response().getResult().getOutput().getToolCalls()).containsExactly(tool);
        assertThat(outputs.toString()).isEqualTo("answer");
        assertThat(saved.get().metrics().status()).isEqualTo(LlmStreamLog.EndStatus.COMPLETED);
        assertThat(saved.get().metrics().totalTokens()).isEqualTo(6);
        // Opt-in debug preserves actual model metadata; the Runtime/public stream stays sanitized.
        assertThat(saved.get().content().response()).contains("answer", "synthetic-tool", "private reasoning");
    }

    @Test void errorPreservesReceivedButUnpublishedPrefixAndWritesOneFailureSnapshot() {
        var gateway = gateway(Flux.concat(Flux.just(chunk("received")), Flux.error(new IllegalStateException("synthetic failure"))));
        var saved = new AtomicReference<LlmStreamLog>();
        AtomicInteger snapshots = new AtomicInteger();
        assertThatThrownBy(() -> gateway.callStreaming(List.of(new UserMessage("test")), List.of(), Map.of(), gateway.configIdentity(),
                ignored -> {}, true, log -> { snapshots.incrementAndGet(); saved.set(log); })).isInstanceOf(IllegalStateException.class);
        assertThat(snapshots).hasValue(1);
        assertThat(saved.get().metrics().status()).isEqualTo(LlmStreamLog.EndStatus.FAILED);
        assertThat(saved.get().content().response()).contains("received");
        assertThat(saved.get().metrics().firstTextMs()).isNotNull();
        assertThat(saved.get().metrics().totalTokens()).isNull();
    }

    @Test void rejectedPublicBatchStillPreservesTheReceivedModelResponse() {
        var gateway = gateway(Flux.just(chunk("rejected")));
        var saved = new AtomicReference<LlmStreamLog>();
        RuntimeException rejection = new IllegalStateException("synthetic lease rejection");
        assertThatThrownBy(() -> gateway.callStreaming(List.of(new UserMessage("test")), List.of(), Map.of(), gateway.configIdentity(),
                ignored -> { throw rejection; }, true, saved::set)).isSameAs(rejection);
        assertThat(saved.get().metrics().status()).isEqualTo(LlmStreamLog.EndStatus.FAILED);
        assertThat(saved.get().content().response()).contains("rejected");
    }

    @Test void interruptCancelsSubscriptionAndCapturesPublishedPrefixOnce() throws Exception {
        CountDownLatch canceled = new CountDownLatch(1), published = new CountDownLatch(1), logged = new CountDownLatch(1);
        var gateway = gateway(Flux.concat(Flux.just(chunk("partial")), Flux.<ChatResponse>never()).doOnCancel(canceled::countDown));
        var saved = new AtomicReference<LlmStreamLog>();
        AtomicInteger snapshots = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> invocation = caller.submit(() -> gateway.callStreaming(List.of(new UserMessage("test")), List.of(), Map.of(), gateway.configIdentity(),
                    ignored -> published.countDown(), true, log -> { snapshots.incrementAndGet(); saved.set(log); logged.countDown(); }));
            assertThat(published.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            invocation.cancel(true);
            assertThat(canceled.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(logged.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(snapshots).hasValue(1);
            assertThat(saved.get().metrics().status()).isEqualTo(LlmStreamLog.EndStatus.CANCELED);
            assertThat(saved.get().content().response()).contains("partial");
        } finally { caller.shutdownNow(); assertThat(caller.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void overlappingCallsOnCachedGatewayKeepIndependentSemanticState() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(2);
        var gateway = new SpringAiChatGateway(new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("Stream only"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                String text = prompt.getInstructions().getLast().getText();
                return Flux.defer(() -> {
                    subscribed.countDown();
                    try { if (!subscribed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new IllegalStateException("Both streams required"); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                    return Flux.just(chunk(text));
                });
            }
        }, 1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            AtomicReference<LlmStreamLog> first = new AtomicReference<>(), second = new AtomicReference<>();
            Future<?> a = callers.submit(() -> gateway.callStreaming(List.of(new UserMessage("first only")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {}, true, first::set));
            Future<?> b = callers.submit(() -> gateway.callStreaming(List.of(new UserMessage("second only")), List.of(), Map.of(),
                    gateway.configIdentity(), ignored -> {}, true, second::set));
            a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(first.get().content().response()).contains("first only").doesNotContain("second only");
            assertThat(second.get().content().response()).contains("second only").doesNotContain("first only");
        } finally { callers.shutdownNow(); assertThat(callers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue(); }
    }

    private SpringAiChatGateway gateway(Flux<ChatResponse> stream) {
        return new SpringAiChatGateway(new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("No blocking model call"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return stream; }
        }, 1);
    }
    private ChatResponse chunk(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}

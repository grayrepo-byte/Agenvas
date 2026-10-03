package dev.agenvas.llm.application;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.audit.domain.LlmStreamLog.EndStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.ObjectMapper;

/** One collector per subscription. Reader, batching and cancellation threads share only this instance. */
public final class LlmStreamLogCollector {
    private static final long NANOS_PER_MILLISECOND = 1_000_000;
    private static final int MAX_CONTENT_BYTES = 1024 * 1024;
    private static final int MAX_FINISH_REASONS = 16;
    private final boolean captureContent;
    private final LongSupplier nanoTime;
    private final long started;
    private final LlmProtocolCodec codec = new LlmProtocolCodec(new ObjectMapper());
    private final StringBuilder received = new StringBuilder();
    private final List<AssistantMessage.ToolCall> tools = new ArrayList<>();
    private final List<String> finishReasons = new ArrayList<>();
    private Long firstChunkMs;
    private Long firstTextMs;
    private long chunks;
    private int contentBytes;
    private boolean truncated;
    private Usage usage = new EmptyUsage();
    private String model;
    private String responseId;
    private String completeResponse;
    private ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.NULL;

    public LlmStreamLogCollector(boolean captureContent) { this(captureContent, System::nanoTime); }
    LlmStreamLogCollector(boolean captureContent, LongSupplier nanoTime) {
        this.captureContent = captureContent;
        this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
    }

    public synchronized void chunk(ChatResponse response) {
        chunks++;
        if (firstChunkMs == null) firstChunkMs = elapsed();
        String text = PublicAssistantResponse.text(response);
        if (!text.isEmpty() && firstTextMs == null) firstTextMs = elapsed();
        metadata(response);
        if (!captureContent || truncated) return;
        try {
            int retained = text.getBytes(StandardCharsets.UTF_8).length;
            if (response.getResult() != null) for (var tool : response.getResult().getOutput().getToolCalls()) {
                retained = Math.addExact(retained, tool.arguments().getBytes(StandardCharsets.UTF_8).length);
            }
            contentBytes = Math.addExact(contentBytes, retained);
            if (contentBytes > MAX_CONTENT_BYTES) { truncated = true; return; }
            received.append(text);
            // Opaque metadata is included in the complete response. A failed stream keeps the
            // public text/tool prefix and observed usage, without retaining unbounded metadata frames.
            if (response.getResult() != null) {
                tools.addAll(response.getResult().getOutput().getToolCalls());
                generationMetadata = response.getResult().getMetadata();
            }
        } catch (RuntimeException failure) { truncated = true; }
    }

    public synchronized void complete(ChatResponse response) {
        metadata(response);
        if (captureContent) {
            try { completeResponse = codec.response(response).toString(); }
            catch (RuntimeException failure) { truncated = true; }
        }
    }

    private void metadata(ChatResponse response) {
        Usage reported = response.getMetadata().getUsage();
        if (reported != null && !(reported instanceof EmptyUsage)
                && (positive(reported.getPromptTokens()) || positive(reported.getCompletionTokens())
                || positive(reported.getTotalTokens()))) usage = reported;
        String candidate = CallLogService.safeIdentifier(response.getMetadata().getModel());
        if (candidate != null && !candidate.isEmpty()) model = candidate;
        candidate = CallLogService.safeRequestId(response.getMetadata().getId());
        if (candidate != null && !candidate.isEmpty()) responseId = candidate;
        response.getResults().forEach(generation -> {
            String reason = CallLogService.safeIdentifier(generation.getMetadata().getFinishReason());
            if (reason != null && finishReasons.size() < MAX_FINISH_REASONS && !finishReasons.contains(reason)) finishReasons.add(reason);
        });
    }

    public synchronized LlmStreamLog snapshot(EndStatus status, String errorCode) {
        String response = completeResponse;
        if (captureContent && response == null) {
            var assistant = AssistantMessage.builder().content(received.toString()).toolCalls(List.copyOf(tools)).build();
            var metadata = ChatResponseMetadata.builder().model(model)
                    .id(responseId).usage(usage).build();
            try { response = codec.response(new ChatResponse(List.of(new Generation(assistant, generationMetadata)), metadata)).toString(); }
            catch (RuntimeException failure) { response = "{\"omitted\":\"LOG_CONTENT_LIMIT\"}"; truncated = true; }
        }
        var metrics = new LlmStreamLog.Metrics(LlmStreamLog.SCHEMA_VERSION, firstChunkMs, firstTextMs, elapsed(),
                chunks, usage instanceof EmptyUsage ? null : usage.getPromptTokens(),
                usage instanceof EmptyUsage ? null : usage.getCompletionTokens(),
                usage instanceof EmptyUsage ? null : usage.getTotalTokens(), model, responseId,
                finishReasons, status, errorCode);
        return new LlmStreamLog(metrics, captureContent ? new LlmStreamLog.Content(response, truncated) : null);
    }

    private boolean positive(Integer value) { return value != null && value > 0; }
    private long elapsed() { return Math.max(0, (nanoTime.getAsLong() - started) / NANOS_PER_MILLISECOND); }
}

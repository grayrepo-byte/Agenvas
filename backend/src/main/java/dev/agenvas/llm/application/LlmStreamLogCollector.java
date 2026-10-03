package dev.agenvas.llm.application;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.audit.domain.LlmStreamLog.EndStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.ObjectMapper;

/** One debug collector per subscription, independent of the sanitized public answer stream. */
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
    private final Map<String, Object> assistantAttributes = new LinkedHashMap<>();
    private final Map<String, Object> responseAttributes = new LinkedHashMap<>();

    public LlmStreamLogCollector(boolean captureContent) { this(captureContent, System::nanoTime); }
    LlmStreamLogCollector(boolean captureContent, LongSupplier nanoTime) {
        this.captureContent = captureContent;
        this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
    }

    public synchronized void chunk(ChatResponse response) {
        chunks++;
        if (firstChunkMs == null) firstChunkMs = elapsed();
        String publicText = PublicAssistantResponse.text(PublicAssistantResponse.sanitize(response));
        if (!publicText.isEmpty() && firstTextMs == null) firstTextMs = elapsed();
        metadata(response);
        if (!captureContent || truncated) return;
        try {
            int retained = codec.debugResponse(response).toString().getBytes(StandardCharsets.UTF_8).length;
            contentBytes = Math.addExact(contentBytes, retained);
            if (contentBytes > MAX_CONTENT_BYTES) { truncated = true; return; }
            received.append(PublicAssistantResponse.text(response));
            // Keep the received text/tool prefix and observed model attributes within the same
            // byte budget. Actual HTTP events preserve SDK-ignored fields separately.
            if (response.getResult() != null) {
                tools.addAll(response.getResult().getOutput().getToolCalls());
                generationMetadata = response.getResult().getMetadata();
                assistantAttributes.putAll(response.getResult().getOutput().getMetadata());
            }
            response.getMetadata().entrySet().forEach(entry -> responseAttributes.put(entry.getKey(), entry.getValue()));
        } catch (RuntimeException failure) { truncated = true; }
    }

    public synchronized void complete(ChatResponse response) {
        metadata(response);
        if (captureContent && !truncated) {
            try {
                // Reuse the SDK's completed tool/generation assembly while restoring the bounded
                // raw text and attributes that were removed from the public aggregation path.
                List<Generation> generations = new ArrayList<>(response.getResults());
                if (!generations.isEmpty() && chunks > 0) {
                    Generation first = generations.getFirst();
                    Map<String, Object> attributes = new LinkedHashMap<>(first.getOutput().getMetadata());
                    attributes.putAll(assistantAttributes);
                    AssistantMessage assistant = AssistantMessage.builder().content(received.toString())
                            .toolCalls(first.getOutput().getToolCalls()).media(first.getOutput().getMedia())
                            .properties(attributes).build();
                    Map<String, Object> generationAttributes = new LinkedHashMap<>();
                    first.getMetadata().entrySet().forEach(entry -> generationAttributes.put(entry.getKey(), entry.getValue()));
                    generationMetadata.entrySet().forEach(entry -> generationAttributes.put(entry.getKey(), entry.getValue()));
                    var metadata = ChatGenerationMetadata.builder().finishReason(first.getMetadata().getFinishReason())
                            .contentFilters(first.getMetadata().getContentFilters()).metadata(generationAttributes).build();
                    generations.set(0, new Generation(assistant, metadata));
                }
                Map<String, Object> attributes = new LinkedHashMap<>();
                response.getMetadata().entrySet().forEach(entry -> attributes.put(entry.getKey(), entry.getValue()));
                attributes.putAll(responseAttributes);
                var metadata = ChatResponseMetadata.builder().metadata(attributes)
                        .id(response.getMetadata().getId()).model(response.getMetadata().getModel())
                        .usage(response.getMetadata().getUsage()).rateLimit(response.getMetadata().getRateLimit())
                        .promptMetadata(response.getMetadata().getPromptMetadata()).build();
                completeResponse = codec.debugResponse(new ChatResponse(generations, metadata)).toString();
            }
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
            var assistant = AssistantMessage.builder().content(received.toString()).toolCalls(List.copyOf(tools))
                    .properties(new LinkedHashMap<>(assistantAttributes)).build();
            var metadata = ChatResponseMetadata.builder().metadata(new LinkedHashMap<>(responseAttributes)).model(model)
                    .id(responseId).usage(usage).build();
            try { response = codec.debugResponse(new ChatResponse(List.of(new Generation(assistant, generationMetadata)), metadata)).toString(); }
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

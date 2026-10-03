package dev.agenvas.audit.domain;

import java.util.List;

/** Immutable semantic stream log; content is opt-in and separate from safe timing metadata. */
public record LlmStreamLog(Metrics metrics, Content content) {
    public static final int SCHEMA_VERSION = 2;
    public enum EndStatus { COMPLETED, FAILED, CANCELED }
    public record Metrics(int schemaVersion, Long firstChunkMs, Long firstTextMs, long durationMs,
            long chunkCount, Integer promptTokens,
            Integer completionTokens, Integer totalTokens, String model, String responseId,
            List<String> finishReasons, EndStatus status, String errorCode) {
        public Metrics {
            if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported stream log schema");
            finishReasons = List.copyOf(finishReasons);
        }
    }
    /** response is the application's public model protocol JSON, never raw SSE or private reasoning. */
    public record Content(String response, boolean truncated) {}
}

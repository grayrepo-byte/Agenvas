package dev.agenvas.audit.application;

import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.shared.http.DebugHttpCapture.Exchange;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Best-effort logging only; immutable snapshots cannot cause a model retry or alter its result. */
@Service
public class AsyncCallLogWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCallLogWriter.class);
    private final CallLogRepository repository;
    private final Executor executor;

    public AsyncCallLogWriter(CallLogRepository repository,
            @Qualifier("llmStreamLogExecutor") Executor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    public void submit(UUID id, CallLogService.CallOutcome outcome, Instant respondedAt, long durationMs,
            LlmStreamLog log, List<Exchange> exchanges, boolean captured) {
        List<Exchange> snapshot = List.copyOf(exchanges);
        try {
            executor.execute(() -> {
                try { repository.finishStream(id, outcome, respondedAt, durationMs, log, snapshot, captured); }
                catch (RuntimeException failure) {
                    LOGGER.error("LLM stream log write failed callId={} code=CALL_STREAM_WRITE_FAILED", id);
                }
            });
        } catch (RejectedExecutionException rejected) {
            LOGGER.error("LLM stream log queue rejected callId={} code=CALL_STREAM_QUEUE_REJECTED", id);
        }
    }
}

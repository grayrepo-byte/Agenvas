package dev.agenvas.audit.domain;

import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.UUID;

/** Safe call metadata. Historical checkpoints never pretend to know network response times. */
public record CallLog(UUID id, UUID projectId, String projectTitle, UUID taskId, UUID runId,
        Kind kind, Operation operation, Status status, Task.Status taskStatus,
        String provider, String model, String traceId, String providerRequestId,
        String errorCode, Instant startedAt, Instant respondedAt, Long durationMs,
        boolean historical, boolean mock) {
    public enum Kind { LLM, IMAGE, VIDEO, AUDIO }
    public enum Operation { CHAT, SUBMIT, POLL, LEGACY }
    public enum Status { RUNNING, SUCCEEDED, FAILED, UNKNOWN }
}

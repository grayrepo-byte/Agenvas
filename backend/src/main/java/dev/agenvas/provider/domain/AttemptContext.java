package dev.agenvas.provider.domain;

import dev.agenvas.task.domain.Task;
import java.util.UUID;

/** The request identity is persisted before an adapter is allowed to submit. */
public record AttemptContext(Task lease, MediaCapabilityBinding binding, UUID ownerId,
        String requestKey, String originalRequestId) {}

package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;
import java.util.UUID;

/** Optional provider-specific read-only proof followed by a fenced original-task recovery. */
public interface UnknownTaskReconciler {

    /** Never submits or retries generation; NO_EVIDENCE leaves the task unchanged. */
    Result reconcile(UUID ownerId, UUID projectId, UUID taskId);

    /** Minimal controller-facing decision, not raw provider history or private task input. */
    record Result(Outcome outcome, Task task) {}

    /** Provider absence is inconclusive, while RESUMED means the original request was found. */
    enum Outcome { NO_EVIDENCE, RESUMED }
}

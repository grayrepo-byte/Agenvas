package dev.agenvas.run.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import dev.agenvas.task.domain.Task;

/** Run-owned cancellation checkpoint implemented by the task persistence boundary. */
public interface RunTaskCancellation {

    /** Stops unsubmitted work, marks in-flight work, and returns only newly canceled media Tasks. */
    List<Task> requestCancellation(UUID projectId, UUID runId, Instant now);
}

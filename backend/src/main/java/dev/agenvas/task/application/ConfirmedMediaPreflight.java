package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;

/** Issued only after a leased preflight CAS proves that no provider attempt was started. */
public final class ConfirmedMediaPreflight {
    private final Task task;

    ConfirmedMediaPreflight(Task task) {
        if (task.providerRequestId() != null
                || (task.status() != Task.Status.BLOCKED && task.status() != Task.Status.CANCELED)) {
            throw new IllegalArgumentException("Expected a terminal media preflight outcome");
        }
        this.task = task;
    }

    public Task task() {
        return task;
    }
}

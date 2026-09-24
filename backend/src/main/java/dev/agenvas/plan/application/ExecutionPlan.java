package dev.agenvas.plan.application;

import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Immutable proposal content with a separately mutable approval lifecycle. */
public record ExecutionPlan(UUID id, UUID projectId, UUID runId, int revision,
        Stage stage, Status status, String objective, JsonNode plan,
        JsonNode inputSnapshot, String inputSnapshotHash, String planHash,
        int providerConfigVersion, String workflowVersion, JsonNode estimate,
        Instant createdAt, Instant updatedAt, List<Step> steps) {

    /** P0 has separate keyframe and selected-keyframe-to-video approval stages. */
    public enum Stage { IMAGE, VIDEO }

    /** Approval never mutates the proposed plan body or its hashes. */
    public enum Status { PENDING, APPROVED, REJECTED, STALE }

    /** One immutable DAG node with a named future output slot. */
    public record Step(String stepKey, int ordinal, Task.Kind kind,
            UUID shotArtifactId, UUID shotVersionId,
            UUID imageArtifactId, UUID imageVersionId,
            String outputSlotKey, JsonNode input, List<String> dependencyKeys) {}
}

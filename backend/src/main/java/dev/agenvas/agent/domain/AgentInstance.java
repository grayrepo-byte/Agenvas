package dev.agenvas.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistent Agent card configuration; runtime conversations live in AgentRun records. */
public record AgentInstance(
        UUID id,
        UUID projectId,
        String profileKey,
        int profileVersion,
        String name,
        String instruction,
        UUID outputGroupId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<Binding> bindings) {

    /** Explicit input pinned to an immutable ArtifactVersion. */
    public record Binding(
            UUID id,
            UUID artifactId,
            UUID selectedVersionId,
            BindingType bindingType,
            Instant createdAt) {}

    /** Binding roles supported by the MVP creator profile. */
    public enum BindingType {
        INPUT
    }
}

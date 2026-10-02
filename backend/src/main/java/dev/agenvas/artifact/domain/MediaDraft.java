package dev.agenvas.artifact.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** A persisted working draft, independent of the selected immutable media result. */
public record MediaDraft(
        UUID projectId,
        UUID canvasItemId,
        String prompt,
        JsonNode parameters,
        Integer durationSeconds,
        UUID capabilityId,
        UUID styleId,
        VideoInputMode videoInputMode,
        List<MediaInput> mediaInputs,
        List<PromptMention> mentions,
        DisplayMode displayMode,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public enum DisplayMode { DRAFT, RESULT }

    /** Video modes have independent input requirements; image drafts keep this null. */
    public enum VideoInputMode { TEXT, START_END, GENERAL_REFERENCE }

    /** Semantic role of one exact image version in the provider input. */
    public enum InputRole { REFERENCE, START_FRAME, END_FRAME, AUDIO_REFERENCE, VIDEO_REFERENCE }

    /** Why an input remains in the deduplicated card-local input row. */
    public enum SourceType { MANUAL, CONNECTION }

    public record InputSource(UUID id, SourceType type, UUID connectionId) {}

    public record MediaInput(UUID versionId, UUID artifactId, InputRole role, int order,
            String color, List<InputSource> sources) {}

    public record PromptMention(UUID versionId, InputRole role) {}
}

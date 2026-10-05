package dev.agenvas.canvas.application;

import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.event.application.ProjectEventService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Coordinates topology reconciliation and complete draft writes in one transaction. */
@Service
public class MediaDraftRestoreService {
    private final CanvasConnectionService connections;
    private final MediaDraftService drafts;
    private final ProjectEventService events;

    public MediaDraftRestoreService(CanvasConnectionService connections,
            MediaDraftService drafts, ProjectEventService events) {
        this.connections = connections;
        this.drafts = drafts;
        this.events = events;
    }

    @Transactional
    public MediaDraft restore(UUID ownerId, UUID projectId, UUID canvasItemId,
            UUID versionId, long expectedDraftVersion) {
        long versionAfterCleanup = connections.clearTargetMediaConnectionsWithinChange(
                ownerId, projectId, canvasItemId, expectedDraftVersion);
        return drafts.restoreVersionInputs(ownerId, projectId, canvasItemId, versionId,
                versionAfterCleanup);
    }

    /** Switching capability is an explicit input reconciliation. Ordinary autosaves still
     * retain connection-only inputs; a switch removes only lines excluded by the new plan.
     * Cleanup, draft validation/CAS and the event share the project lock and transaction.
     */
    @Transactional
    public MediaDraft save(UUID ownerId, UUID projectId, UUID canvasItemId,
            long expectedDraftVersion, String prompt, JsonNode parameters,
            Integer durationSeconds, UUID capabilityId, MediaDraft.VideoInputMode videoInputMode,
            List<MediaDraftService.SaveMediaInput> mediaInputs,
            List<MediaDraft.PromptMention> mentions, UUID styleId) {
        return events.recordChange(ownerId, projectId, () -> {
            MediaDraft before = drafts.get(ownerId, projectId, canvasItemId);
            long version = expectedDraftVersion;
            if (!java.util.Objects.equals(before.capabilityId(), capabilityId)) {
                java.util.Set<UUID> retained = mediaInputs == null ? java.util.Set.of()
                        : mediaInputs.stream().filter(java.util.Objects::nonNull)
                                .map(MediaDraftService.SaveMediaInput::versionId)
                                .collect(java.util.stream.Collectors.toSet());
                version = connections.retainTargetMediaConnectionsWithinChange(ownerId, projectId,
                        canvasItemId, expectedDraftVersion, retained);
            }
            MediaDraft saved = drafts.save(ownerId, projectId, canvasItemId, version,
                    prompt, parameters, durationSeconds, capabilityId, videoInputMode,
                    mediaInputs, mentions, styleId);
            return ProjectEventService.Change.unchanged(saved);
        }).value();
    }

    /** An explicit full replacement removes connection sources atomically with the new inputs.
     * Ordinary draft saves deliberately retain those sources; using several removal requests
     * here would leave a partially changed draft if a later CAS or input validation failed.
     */
    @Transactional
    public MediaDraft replaceInputs(UUID ownerId, UUID projectId, UUID canvasItemId,
            long expectedDraftVersion, String prompt, JsonNode parameters,
            Integer durationSeconds, UUID capabilityId, MediaDraft.VideoInputMode videoInputMode,
            List<MediaDraftService.SaveMediaInput> mediaInputs,
            List<MediaDraft.PromptMention> mentions, UUID styleId) {
        // Keep the project -> draft lock order used by ordinary saves and connection changes.
        // The nested save records the event; this outer scope only serializes the whole change.
        return events.recordChange(ownerId, projectId, () -> {
            long versionAfterCleanup = connections.clearTargetMediaConnectionsWithinChange(
                    ownerId, projectId, canvasItemId, expectedDraftVersion);
            MediaDraft saved = drafts.save(ownerId, projectId, canvasItemId, versionAfterCleanup,
                    prompt, parameters, durationSeconds, capabilityId, videoInputMode,
                    mediaInputs, mentions, styleId);
            return ProjectEventService.Change.unchanged(saved);
        }).value();
    }
}

package dev.agenvas.character.application;

import dev.agenvas.artifact.domain.MediaDraft;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Public character profiles and fixed shot snapshots; no live reference resolves a saved look. */
public final class CharacterModels {
    private CharacterModels() {}
    public enum ClearMode { KEEP_INPUTS, REMOVE_OWNED }
    public enum ReviewReason { PROFILE_CHANGED, DRAFT_CHANGED, RESULT_CHANGED, NOT_REVIEWED, CHARACTER_ARCHIVED }
    public record CharacterView(UUID versionId, String purpose, String description) {}
    public record CharacterVoice(UUID versionId, String speakerId, String description) {}
    public record CharacterLook(UUID id, String name, String clothing, String expression,
            String description, List<CharacterView> views) {}
    public record CharacterContent(String name, String description, List<CharacterLook> looks,
            CharacterVoice voice) {}
    public record CharacterProfile(UUID id, UUID projectId, CharacterContent content, long version,
            boolean archived, Instant createdAt, Instant updatedAt) {}
    public record CharacterSnapshot(UUID characterId, long characterVersion, String name,
            String description, CharacterLook look, CharacterVoice voice,
            boolean useAppearance, boolean useVoice) {}
    public record CharacterBindingSelection(UUID characterId, long characterVersion, UUID lookId,
            boolean useAppearance, boolean useVoice) {}
    public record SaveCharacter(UUID commandId, long expectedVersion, CharacterContent content) {}
    public record ArchiveCharacter(UUID commandId, long expectedVersion) {}
    public record AffectedItem(UUID canvasItemId, String title, long bindingVersion) {}
    public record CharacterChangePreview(UUID characterId, long expectedVersion,
            List<AffectedItem> affectedItems) {}
    public record PreviewCharacterBindings(long expectedBindingVersion, long expectedDraftVersion,
            List<CharacterBindingSelection> selections, ClearMode clearMode) {}
    public record ApplyCharacterBindings(UUID commandId, long expectedBindingVersion,
            long expectedDraftVersion, List<CharacterBindingSelection> selections,
            ClearMode clearMode, String previewHash) {
        public PreviewCharacterBindings previewRequest() {
            return new PreviewCharacterBindings(expectedBindingVersion, expectedDraftVersion, selections, clearMode);
        }
    }
    public record ReviewCharacterBindings(long expectedBindingVersion, long expectedDraftVersion,
            UUID selectedVersionId) {}
    public record CharacterContribution(List<UUID> versionIds, String promptBlock, String speakerId) {}
    public record CharacterBinding(UUID canvasItemId, long version, long draftVersion,
            UUID selectedVersionId, List<CharacterSnapshot> snapshots, boolean needsReview,
            List<ReviewReason> reviewReasons, CharacterContribution contribution, Instant reviewedAt) {}
    public record CharacterBindingPreview(String previewHash, long expectedBindingVersion,
            long expectedDraftVersion, List<CharacterSnapshot> snapshots, MediaDraft candidateDraft,
            List<String> warnings, List<String> changes) {}
    public record CharacterBindingApplied(CharacterBinding binding, MediaDraft draft) {}
    public record CharacterIndex(List<CharacterProfile> characters, List<CharacterBinding> bindings) {}
}

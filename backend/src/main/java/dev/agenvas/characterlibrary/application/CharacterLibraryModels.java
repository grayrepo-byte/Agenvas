package dev.agenvas.characterlibrary.application;

import dev.agenvas.character.application.CharacterModels.AffectedItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Private account content uses independent media identities, never live project references. */
public final class CharacterLibraryModels {
    private CharacterLibraryModels() {
    }
    public enum Status {
        ACCEPTED, ARCHIVING, SUCCEEDED, FAILED
    }
    public enum Kind {
        SAVE, ARCHIVE, MEDIA, PUBLISH, IMPORT
    }
    public enum SourceType {
        LIBRARY_ENTRY, PROJECT_VERSION
    }
    public record View(UUID mediaId, String purpose, String description) {
    }
    public record Voice(UUID mediaId, String speakerId, String description) {
    }
    public record Look(UUID id, String name, String clothing, String expression, String description, List<View> views) {
    }
    public record Content(String name, String persona, String appearance, List<View> identityViews, List<Look> looks,
            Voice voice) {
    }
    public record Profile(UUID id, Content content, long version, boolean archived, Instant createdAt,
            Instant updatedAt) {
    }
    public record Page(List<Profile> items, UUID nextCursor) {
    }
    public record Save(UUID commandId, long expectedVersion, Content content) {
    }
    public record Archive(UUID commandId, long expectedVersion) {
    }
    public record MediaSource(UUID commandId, SourceType sourceType, UUID entryId, Long expectedEntryVersion,
            UUID projectId, UUID versionId) {
    }
    public record Publish(UUID commandId, long expectedVersion, UUID sourceProjectId, UUID sourceCharacterId,
            long sourceCharacterVersion, List<UUID> identityVersionIds) {
    }
    public record Transfer(UUID libraryId, long libraryVersion, UUID characterId, long expectedCharacterVersion) {
    }
    public record Import(UUID libraryId, long libraryVersion, UUID characterId, long expectedCharacterVersion,
            UUID commandId, String previewHash) {
        public Transfer transfer() {
            return new Transfer(libraryId, libraryVersion, characterId, expectedCharacterVersion);
        }
    }
    public record TransferPreview(String previewHash, UUID libraryId, long libraryVersion, UUID characterId,
            long expectedCharacterVersion, Content content,
            dev.agenvas.character.application.CharacterModels.CharacterProfile currentCharacter,
            List<AffectedItem> affectedItems, List<String> changes) {
    }
    public record Command(UUID id, Status status, JsonNode result, String errorCode, String errorDetail) {
    }
    public record Link(UUID characterId, UUID libraryId, long libraryVersion, long currentLibraryVersion,
            boolean libraryArchived, long projectCharacterVersion, boolean hasUpdate) {
    }
    public record Links(List<Link> items) {
    }
}

package dev.agenvas.skill.domain;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.PrivateMediaArchive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable values for the bounded, data-only Skill format. Internal media keys never form API DTOs. */
public final class SkillContent {
    public static final int SCHEMA_VERSION = 1;
    private SkillContent() {}
    public enum Usage { GUIDE, PROVIDER_REFERENCE }
    public enum Status { ACTIVE, TRASHED }
    public enum OperationStatus { ACCEPTED, ARCHIVING, SUCCEEDED, FAILED }
    public record InputSlot(String alias, Artifact.Kind kind, @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean required) {}
    public record Resource(String path, String content) {}
    public record DraftAsset(String alias, UUID libraryEntryId, Long expectedLibraryVersion, UUID sourceVersionId,
            String sourceAlias, String contentHash, Usage usage, @com.fasterxml.jackson.annotation.JsonProperty(required = true) boolean required, String purpose) {}
    public record DraftContent(int schemaVersion, String skillMd, List<Artifact.Kind> outputKinds,
            List<InputSlot> inputSlots, List<Resource> resources, List<DraftAsset> assets) {}
    public record PublishedResource(String path, String content, String contentHash) {}
    public record PublishedAsset(String alias, Artifact.Kind kind, String title, String contentHash,
            Usage usage, boolean required, String purpose, PrivateMediaArchive.Media media) {}
    public record Bundle(int schemaVersion, String name, String description, String skillMd,
            List<Artifact.Kind> outputKinds, List<InputSlot> inputSlots, List<PublishedResource> resources,
            List<PublishedAsset> assets) {}
    public record Catalogue(UUID id, UUID ownerId, String title, String description, UUID currentVersionId,
            Instant trashedAt, long version, Instant createdAt, Instant updatedAt) {}
    public record Draft(UUID skillId, UUID ownerId, long version, DraftContent content, Instant updatedAt) {}
    public record Version(UUID id, UUID ownerId, UUID skillId, long versionNumber, String bundleHash,
            Bundle bundle, Instant createdAt) {}
    public record Binding(UUID agentId, UUID projectId, UUID ownerId, UUID skillId, UUID skillVersionId, Instant updatedAt) {}
    public record PublishInput(int schemaVersion, long draftVersion, DraftContent draft) {}
    public record PublishOperation(UUID id, UUID ownerId, UUID skillId, String commandKey, String payloadHash,
            PublishInput input, tools.jackson.databind.JsonNode progress, OperationStatus status, long epoch,
            Instant leaseUntil, UUID resultVersionId, String errorCode, String errorDetail, boolean pinsCleaned,
            Instant createdAt, Instant updatedAt) {}
}

package dev.agenvas.referencecatalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local metadata snapshots never confer rights to remote image bytes. */
public final class ReferenceCatalog {
    private ReferenceCatalog() {
    }
    public enum Format {
        OPEN_POSE_COSTUME, AGENVAS_REFERENCE_PACK
    }
    public enum SourceType {
        OMILIC, GITHUB
    }
    public enum Kind {
        POSE, COSTUME
    }
    public enum Origin {
        THIRD_PARTY, SELF_CREATED
    }
    public record Source(String id, String name, String owner, String repository, String ref, String manifestPath,
            Format format, boolean enabled, boolean imageDistributionApproved, long version, UUID currentSnapshotId,
            String lastSyncStatus, String lastSyncError, Instant syncedAt, String resolvedCommit,
            SourceType sourceType) {
    }
    public record Variant(String id, String label, String view, String angle, Map<String, String> localizedLabel,
            String externalUrl, String path, String sha256, Long byteSize, String contentType) {
    }
    public record Entry(String id, Kind kind, String title, Map<String, String> localizedTitle, String code,
            String category, Map<String, String> localizedCategory, Map<String, String> series,
            List<String> tags, Map<String, Map<String, String>> localizedTags, Map<String, String> localizedKeywords,
            Map<String, String> facets,
            String collection, boolean extended, String externalUrl, List<Variant> variants) {
    }
    public record Snapshot(UUID id, String sourceId, String commit, String manifestHash, String license,
            List<Entry> entries) {
    }
}

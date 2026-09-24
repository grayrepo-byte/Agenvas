package dev.agenvas.export.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Owner-scoped project description built from an explicit export allowlist. */
@Service
public class ProjectExportManifestService {

    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProjectExportManifestService(ProjectService projects, ArtifactService artifacts,
            AssetService assets, ObjectMapper mapper, Clock clock) {
        this.projects = projects;
        this.artifacts = artifacts;
        this.assets = assets;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** One MVCC snapshot includes historical versions and private Asset IDs, never URLs/keys. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Manifest build(UUID ownerId, UUID projectId) {
        Project project = projects.get(ownerId, projectId);
        ArtifactService.ProjectExportVersions catalog =
                artifacts.listProjectExport(ownerId, projectId);
        Map<UUID, Artifact.Kind> kinds = catalog.artifacts().stream()
                .collect(Collectors.toMap(Artifact::id, Artifact::kind));
        Map<UUID, List<VersionEntry>> versions = catalog.versions().stream()
                .collect(Collectors.groupingBy(ArtifactVersion::artifactId,
                        Collectors.mapping(version -> versionEntry(version,
                                kinds.get(version.artifactId())), Collectors.toList())));
        return new Manifest(1, clock.instant(), project.eventSeq(),
                new ProjectEntry(project.id(), project.name(), project.aspectRatio(),
                        project.status(), project.createdAt()),
                catalog.artifacts().stream().map(artifact -> artifactEntry(
                        artifact, versions.getOrDefault(artifact.id(), List.of()))).toList(),
                assets.listProjectAssets(ownerId, projectId).stream()
                        .map(this::assetEntry).toList());
    }

    private ArtifactEntry artifactEntry(Artifact artifact, List<VersionEntry> versions) {
        return new ArtifactEntry(artifact.id(), artifact.kind(), artifact.title(),
                artifact.currentVersionId(), artifact.archivedAt(), versions);
    }

    /** Arbitrary media parameters and task/provider internals are intentionally not copied. */
    private VersionEntry versionEntry(ArtifactVersion version, Artifact.Kind kind) {
        ObjectNode safe = mapper.createObjectNode();
        String[] fields = allowedFields(kind);
        for (String field : fields) {
            JsonNode value = version.content().get(field);
            if (value != null) safe.set(field, value.deepCopy());
        }
        return new VersionEntry(version.id(), version.versionNo(), version.schemaVersion(),
                version.createdAt(), safe);
    }

    private String[] allowedFields(Artifact.Kind kind) {
        // The artifact kind is obtained from the validated catalog, not provider JSON.
        return switch (kind) {
            case TEXT -> new String[] {"format", "text"};
            case CHARACTER -> new String[] {"name", "description", "appearance",
                    "referenceVersionIds"};
            case SCENE -> new String[] {"name", "location", "timeOfDay", "lighting",
                    "style", "referenceVersionIds"};
            case SHOT -> new String[] {"order", "durationMs", "description", "camera",
                    "action", "characterVersionIds", "sceneVersionId",
                    "selectedImageVersionId", "selectedVideoVersionId"};
            case IMAGE -> new String[] {"assetId", "prompt", "negativePrompt",
                    "providerConfigVersion", "workflowVersion"};
            case VIDEO -> new String[] {"assetId", "prompt", "negativePrompt",
                    "providerConfigVersion", "workflowVersion", "keyframeVersionId"};
        };
    }

    private AssetEntry assetEntry(Asset asset) {
        return new AssetEntry(asset.id(), asset.mediaKind(), asset.contentType(),
                asset.byteSize(), asset.sha256(), asset.width(), asset.height(),
                asset.durationMs(), asset.thumbnailSha256(), asset.createdAt());
    }

    /** Top-level export intentionally excludes owner IDs, sessions and provider configuration. */
    public record Manifest(int schemaVersion, Instant generatedAt, long snapshotSeq,
            ProjectEntry project, List<ArtifactEntry> artifacts, List<AssetEntry> assets) {}

    /** Non-secret project identity. */
    public record ProjectEntry(UUID id, String name, Project.AspectRatio aspectRatio,
            Project.Status status, Instant createdAt) {}

    /** One stable artifact with all of its immutable history. */
    public record ArtifactEntry(UUID id, Artifact.Kind kind, String title,
            UUID currentVersionId, Instant archivedAt, List<VersionEntry> versions) {}

    /** Whitelisted content only; no provider parameters or task internals. */
    public record VersionEntry(UUID id, int versionNo, int schemaVersion,
            Instant createdAt, JsonNode content) {}

    /** Asset metadata omits private object keys and any reusable download URL. */
    public record AssetEntry(UUID id, Asset.MediaKind mediaKind, String contentType,
            long byteSize, String sha256, Integer width, Integer height,
            Integer durationMs, String thumbnailSha256, Instant createdAt) {}
}

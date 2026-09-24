package dev.agenvas.asset.application;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Authorizes private asset writes and reads; files are installed before READY metadata. */
@Service
public class AssetService {

    private final ProjectService projects;
    private final AssetRepository assets;
    private final LocalAssetStorage storage;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AssetService(ProjectService projects, AssetRepository assets,
            LocalAssetStorage storage, ProjectEventService events, ObjectMapper mapper,
            Clock clock) {
        this.projects = projects;
        this.assets = assets;
        this.storage = storage;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Stores an image with bounded streaming and real decoder validation. */
    public Asset archiveImage(UUID ownerId, UUID projectId, InputStream input) {
        projects.requireActiveProject(ownerId, projectId);
        UUID assetId = UUID.randomUUID();
        LocalAssetStorage.StoredImage stored = storage.storeImage(projectId, assetId, input);
        return publishImage(ownerId, projectId, assetId, stored, true, false);
    }

    /** One generated task owns one image archive, including across file/DB crash windows. */
    public Asset archiveTaskImage(UUID ownerId, UUID projectId, UUID taskId,
            Supplier<InputStream> download) {
        projects.get(ownerId, projectId);
        UUID assetId = taskImageAssetId(taskId);
        return storage.withTaskImageLock(projectId, assetId,
                () -> archiveTaskImageLocked(ownerId, projectId, assetId, download));
    }

    /** This check/download/publish sequence runs under the shared-volume task lock. */
    private Asset archiveTaskImageLocked(UUID ownerId, UUID projectId, UUID assetId,
            Supplier<InputStream> download) {
        var recovered = storage.recoverImage(projectId, assetId);
        var existing = assets.find(projectId, assetId);
        if (existing.isPresent()) {
            LocalAssetStorage.StoredImage file = recovered.orElseThrow(() ->
                    new IllegalStateException("Task image metadata exists without original file"));
            Asset asset = existing.get();
            if (!asset.objectKey().equals(file.objectKey())
                    || !asset.sha256().equals(file.sha256())
                    || asset.byteSize() != file.byteSize()
                    || !asset.thumbnailSha256().equals(file.thumbnailSha256())) {
                throw new IllegalStateException("Task image bytes differ from READY metadata");
            }
            return asset;
        }
        if (recovered.isPresent()) {
            return publishImage(ownerId, projectId, assetId, recovered.get(), false, true);
        }
        try (InputStream input = download.get()) {
            if (input == null) throw new IllegalStateException("Image download returned no stream");
            LocalAssetStorage.StoredImage stored = storage.storeImage(projectId, assetId, input);
            // Keep task-keyed files after a DB failure so the next poll can reconcile them.
            return publishImage(ownerId, projectId, assetId, stored, false, true);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close generated image stream", failure);
        }
    }

    /** Namespaces the deterministic key away from random user-upload asset identifiers. */
    public static UUID taskImageAssetId(UUID taskId) {
        if (taskId == null) throw new IllegalArgumentException("taskId is required");
        return UUID.nameUUIDFromBytes(("agenvas:task-image:v1:" + taskId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private Asset publishImage(UUID ownerId, UUID projectId, UUID assetId,
            LocalAssetStorage.StoredImage stored, boolean discardOnFailure,
            boolean taskOutput) {
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.IMAGE,
                stored.objectKey(), stored.contentType(), stored.byteSize(),
                stored.sha256(), stored.width(), stored.height(), null,
                stored.thumbnailKey(), stored.thumbnailByteSize(),
                stored.thumbnailSha256(), clock.instant());
        try {
            events.recordChange(ownerId, projectId, () -> {
                if (taskOutput) {
                    projects.get(ownerId, projectId);
                } else {
                    projects.requireActiveProject(ownerId, projectId);
                }
                assets.insert(asset);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("assetId", asset.id().toString());
                payload.put("contentType", asset.contentType());
                payload.put("byteSize", asset.byteSize());
                return ProjectEventService.Change.changed(asset,
                        new ProjectEventService.EventDraft("asset.ready", 1,
                                asset.id(), 0, payload));
            });
            return asset;
        } catch (RuntimeException exception) {
            if (discardOnFailure) {
                try {
                    storage.discard(stored.objectKey());
                } finally {
                    storage.discard(stored.thumbnailKey());
                }
            }
            throw exception;
        }
    }

    /** Archives an actual bounded MP4 and its decoded poster before publishing READY metadata. */
    public Asset archiveVideo(UUID ownerId, UUID projectId, InputStream input) {
        projects.requireActiveProject(ownerId, projectId);
        UUID assetId = UUID.randomUUID();
        LocalAssetStorage.StoredVideo stored = storage.storeVideo(projectId, assetId, input);
        return publishVideo(ownerId, projectId, assetId, stored, true, false);
    }

    /** A generated video Task owns one recoverable MP4, even after a file/DB crash. */
    public Asset archiveTaskVideo(UUID ownerId, UUID projectId, UUID taskId,
            Supplier<InputStream> download) {
        projects.get(ownerId, projectId);
        UUID assetId = taskVideoAssetId(taskId);
        return storage.withTaskVideoLock(projectId, assetId,
                () -> archiveTaskVideoLocked(ownerId, projectId, assetId, download));
    }

    private Asset archiveTaskVideoLocked(UUID ownerId, UUID projectId, UUID assetId,
            Supplier<InputStream> download) {
        var recovered = storage.recoverVideo(projectId, assetId);
        var existing = assets.find(projectId, assetId);
        if (existing.isPresent()) {
            LocalAssetStorage.StoredVideo file = recovered.orElseThrow(() ->
                    new IllegalStateException("Task video metadata exists without original file"));
            Asset asset = existing.get();
            if (asset.mediaKind() != Asset.MediaKind.VIDEO
                    || !asset.objectKey().equals(file.objectKey())
                    || !asset.sha256().equals(file.sha256())
                    || asset.byteSize() != file.byteSize()
                    || !asset.thumbnailSha256().equals(file.thumbnailSha256())) {
                throw new IllegalStateException("Task video bytes differ from READY metadata");
            }
            return asset;
        }
        if (recovered.isPresent()) {
            return publishVideo(ownerId, projectId, assetId, recovered.get(), false, true);
        }
        try (InputStream input = download.get()) {
            if (input == null) throw new IllegalStateException("Video download returned no stream");
            LocalAssetStorage.StoredVideo stored = storage.storeVideo(projectId, assetId, input);
            return publishVideo(ownerId, projectId, assetId, stored, false, true);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close generated video stream", failure);
        }
    }

    /** Keeps generated video identifiers separate from images and random user uploads. */
    public static UUID taskVideoAssetId(UUID taskId) {
        if (taskId == null) throw new IllegalArgumentException("taskId is required");
        return UUID.nameUUIDFromBytes(("agenvas:task-video:v1:" + taskId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private Asset publishVideo(UUID ownerId, UUID projectId, UUID assetId,
            LocalAssetStorage.StoredVideo stored, boolean discardOnFailure,
            boolean taskOutput) {
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.VIDEO,
                stored.objectKey(), "video/mp4", stored.byteSize(), stored.sha256(),
                stored.width(), stored.height(), stored.durationMs(), stored.thumbnailKey(),
                stored.thumbnailByteSize(), stored.thumbnailSha256(), clock.instant());
        try {
            events.recordChange(ownerId, projectId, () -> {
                if (taskOutput) {
                    projects.get(ownerId, projectId);
                } else {
                    projects.requireActiveProject(ownerId, projectId);
                }
                assets.insert(asset);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("assetId", asset.id().toString());
                payload.put("contentType", asset.contentType());
                payload.put("byteSize", asset.byteSize());
                return ProjectEventService.Change.changed(asset,
                        new ProjectEventService.EventDraft("asset.ready", 1,
                                asset.id(), 0, payload));
            });
            return asset;
        } catch (RuntimeException exception) {
            if (discardOnFailure) {
                try {
                    storage.discard(stored.objectKey());
                } finally {
                    storage.discard(stored.thumbnailKey());
                }
            }
            throw exception;
        }
    }

    /** Returns metadata and a checked private file after the owner-scoped project lookup. */
    public AssetFile get(UUID ownerId, UUID projectId, UUID assetId) {
        projects.get(ownerId, projectId);
        Asset asset = assets.find(projectId, assetId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND",
                        "素材不存在", "找不到该项目中的素材。", false));
        Path path = checkedReadyFile(asset.objectKey(), asset.byteSize());
        return new AssetFile(asset, path);
    }

    /** Exposes only owned Asset records to the manifest assembler, not storage paths. */
    public List<Asset> listProjectAssets(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return assets.listProjectAssets(projectId);
    }

    /** Rejects a media version unless its referenced bytes are ready in this exact project. */
    public Asset requireReadyMedia(UUID ownerId, UUID projectId, UUID assetId,
            Asset.MediaKind expectedKind) {
        Asset asset = get(ownerId, projectId, assetId).asset();
        if (asset.mediaKind() != expectedKind) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "ARTIFACT_ASSET_KIND_INVALID",
                    "素材类型不匹配", "产物引用的素材类型不匹配。", false);
        }
        return asset;
    }

    /** Resolves a small precomputed PNG preview with the same project authorization. */
    public ThumbnailFile getThumbnail(UUID ownerId, UUID projectId, UUID assetId) {
        Asset asset = get(ownerId, projectId, assetId).asset();
        if (asset.thumbnailKey() == null || asset.thumbnailByteSize() == null) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_THUMBNAIL_NOT_FOUND",
                    "预览不存在", "该素材没有可用的缩略图。", false);
        }
        return new ThumbnailFile(asset,
                checkedReadyFile(asset.thumbnailKey(), asset.thumbnailByteSize()));
    }

    private Path checkedReadyFile(String objectKey, long expectedSize) {
        Path path = storage.checkedPath(objectKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("READY asset file is missing");
        }
        try {
            if (Files.size(path) != expectedSize) {
                throw new IllegalStateException("READY asset file size differs from metadata");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot inspect READY asset file", exception);
        }
        return path;
    }

    /** Authorized archive reference used by private download handlers. */
    public record AssetFile(Asset asset, Path path) {}

    /** Authorized preview reference backed by a bounded archived PNG. */
    public record ThumbnailFile(Asset asset, Path path) {}
}

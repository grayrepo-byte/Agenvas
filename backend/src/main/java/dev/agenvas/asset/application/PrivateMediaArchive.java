package dev.agenvas.asset.application;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Independent account media, using the same actual decoder and immutable installation as projects. */
@Service
public class PrivateMediaArchive {
    private static final String LIBRARY_DIRECTORY = "library";
    private static final int SCHEMA_VERSION = 1;
    private static final String PIN_SUFFIX = ".pin";
    private final LocalAssetStorage storage;

    public PrivateMediaArchive(AssetProperties properties, MediaToolRunner tools, ObjectMapper mapper) {
        storage = new LocalAssetStorage(new AssetProperties(properties.root().resolve(LIBRARY_DIRECTORY)), tools, mapper);
    }

    /** Internal metadata only: object keys must never enter public DTOs. */
    public record Media(int schemaVersion, UUID id, UUID ownerId, Asset.MediaKind kind,
            String objectKey, String contentType, long byteSize, String sha256, Integer width,
            Integer height, Integer durationMs, String thumbnailKey, Long thumbnailByteSize,
            String thumbnailSha256) {}

    /** A stable hard link pins already verified bytes before a command is accepted.
     * The worker still copies into its own immutable archive, so pins are only temporary.
     */
    public String pin(UUID ownerId, UUID commandId, Path verifiedSource) {
        String key = ownerId + "/" + commandId + PIN_SUFFIX;
        Path target = storage.checkedPath(key);
        try {
            Files.createDirectories(target.getParent());
            storage.checkedPath(key);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createLink(target, verifiedSource); }
                catch (java.nio.file.FileAlreadyExistsException concurrent) { /* same immutable command */ }
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid pin");
            return key;
        } catch (IOException failure) {
            throw new ApiProblemException(HttpStatus.INSUFFICIENT_STORAGE, "LIBRARY_STORAGE_FAILED",
                    "无法保存资产", "无法固定媒体文件，请检查文件卷空间与权限。", true);
        }
    }

    public Path pinned(UUID ownerId, String key) {
        requireOwnedKey(ownerId, key);
        Path path = storage.checkedPath(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Pinned media is missing");
        return path;
    }

    public Media archive(UUID ownerId, UUID fileId, Asset.MediaKind kind, Path source) {
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            return archive(ownerId, fileId, kind, input);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read pinned media", failure); }
    }

    /** Stable IDs, archive file locks and recovery avoid overwriting bytes after a process restart. */
    public Media archive(UUID ownerId, UUID fileId, Asset.MediaKind kind, InputStream input) {
        return switch (kind) {
            case IMAGE -> storage.withTaskImageLock(ownerId, fileId, () -> {
                var saved = storage.recoverImage(ownerId, fileId).orElseGet(() -> storage.storeImage(ownerId, fileId, input));
                return new Media(SCHEMA_VERSION, fileId, ownerId, kind, saved.objectKey(), saved.contentType(), saved.byteSize(),
                        saved.sha256(), saved.width(), saved.height(), null, saved.thumbnailKey(),
                        saved.thumbnailByteSize(), saved.thumbnailSha256());
            });
            case VIDEO -> storage.withTaskVideoLock(ownerId, fileId, () -> {
                var saved = storage.recoverVideo(ownerId, fileId).orElseGet(() -> storage.storeVideo(ownerId, fileId, input));
                return new Media(SCHEMA_VERSION, fileId, ownerId, kind, saved.objectKey(), "video/mp4", saved.byteSize(),
                        saved.sha256(), saved.width(), saved.height(), saved.durationMs(), saved.thumbnailKey(),
                        saved.thumbnailByteSize(), saved.thumbnailSha256());
            });
            case AUDIO -> storage.withTaskAudioLock(ownerId, fileId, () -> {
                var saved = storage.recoverAudio(ownerId, fileId).orElseGet(() -> storage.storeAudio(ownerId, fileId, input));
                return new Media(SCHEMA_VERSION, fileId, ownerId, kind, saved.objectKey(), saved.contentType(), saved.byteSize(),
                        saved.sha256(), null, null, saved.durationMs(), null, null, null);
            });
        };
    }

    public Path file(UUID ownerId, Media media, boolean thumbnail) {
        if (!ownerId.equals(media.ownerId())) throw new IllegalStateException("Media owner mismatch");
        String key = thumbnail ? media.thumbnailKey() : media.objectKey();
        Long size = thumbnail ? media.thumbnailByteSize() : media.byteSize();
        if (key == null || size == null) throw new ApiProblemException(HttpStatus.NOT_FOUND,
                "ASSET_THUMBNAIL_NOT_FOUND", "没有预览", "该资产没有缩略图。", false);
        requireOwnedKey(ownerId, key);
        Path path = storage.checkedPath(key);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size)
                throw new IOException("Archived media size mismatch");
            return path;
        } catch (IOException failure) { throw new IllegalStateException("Cannot read library file", failure); }
    }

    public void discardPin(UUID ownerId, String key) {
        if (key != null) { requireOwnedKey(ownerId, key); storage.discard(key); }
    }

    public void discardKeys(UUID ownerId, String objectKey, String thumbnailKey) {
        requireOwnedKey(ownerId, objectKey); storage.discard(objectKey);
        if (thumbnailKey != null) { requireOwnedKey(ownerId, thumbnailKey); storage.discard(thumbnailKey); }
    }

    public void discard(UUID ownerId, Media media) {
        requireOwnedKey(ownerId, media.objectKey());
        storage.discard(media.objectKey());
        if (media.thumbnailKey() != null) {
            requireOwnedKey(ownerId, media.thumbnailKey()); storage.discard(media.thumbnailKey());
        }
    }

    private void requireOwnedKey(UUID ownerId, String key) {
        if (!key.startsWith(ownerId + "/")) throw new IllegalStateException("Archive partition mismatch");
    }
}

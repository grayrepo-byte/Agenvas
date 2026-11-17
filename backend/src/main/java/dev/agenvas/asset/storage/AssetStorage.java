package dev.agenvas.asset.storage;

import dev.agenvas.asset.infrastructure.LocalAssetStorage.StoredAudio;
import dev.agenvas.asset.infrastructure.LocalAssetStorage.StoredImage;
import dev.agenvas.asset.infrastructure.LocalAssetStorage.StoredVideo;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Validated immutable archives, including recovery of an already downloaded task result. */
public interface AssetStorage {
    /** Decoder-supported image originals and their fixed preview; used only for fenced failed installs. */
    java.util.List<String> PREPARED_IMAGE_ORIGINAL_SUFFIXES = java.util.List.of(".png", ".jpg", ".webp", ".gif");
    String PREPARED_IMAGE_THUMBNAIL_SUFFIX = ".thumb.png";
    StoredImage storeImage(UUID projectId, UUID assetId, InputStream source);
    /** Imports MP4 or bounded silent WebM, always archiving validated MP4. */
    StoredVideo storeUploadedVideo(UUID projectId, UUID assetId, InputStream source);
    StoredVideo storeVideo(UUID projectId, UUID assetId, InputStream source);
    StoredAudio storeAudio(UUID projectId, UUID assetId, InputStream source);
    Optional<StoredImage> recoverImage(UUID projectId, UUID assetId);
    Optional<StoredVideo> recoverVideo(UUID projectId, UUID assetId);
    Optional<StoredAudio> recoverAudio(UUID projectId, UUID assetId);
    <T> T withTaskImageLock(UUID projectId, UUID assetId, Supplier<T> action);
    <T> T withTaskVideoLock(UUID projectId, UUID assetId, Supplier<T> action);
    <T> T withTaskAudioLock(UUID projectId, UUID assetId, Supplier<T> action);
    default void verify(String key, long size, String hash) {
        Path file = checkedPath(key);
        try {
            if (!java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || java.nio.file.Files.size(file) != size)
                throw new java.io.IOException("Asset size mismatch");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("READY asset unavailable", failure);
        }
    }
    default InputStream open(String key, long start, long length, long total) throws java.io.IOException {
        var channel = java.nio.channels.FileChannel.open(checkedPath(key),
                java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try {
            channel.position(start);
        } catch (java.io.IOException failure) {
            channel.close();
            throw failure;
        }
        return new java.io.FilterInputStream(java.nio.channels.Channels.newInputStream(channel)) {
            private long remaining = length;
            @Override
            public int read() throws java.io.IOException {
                if (remaining == 0)
                    return -1;
                int value = in.read();
                if (value < 0)
                    throw new java.io.IOException("Asset ended before recorded size");
                remaining--;
                return value;
            }
            @Override
            public int read(byte[] bytes, int offset, int count) throws java.io.IOException {
                if (count == 0)
                    return 0;
                if (remaining == 0)
                    return -1;
                int read = in.read(bytes, offset, (int) Math.min(count, remaining));
                if (read < 0)
                    throw new java.io.IOException("Asset ended before recorded size");
                remaining -= read;
                return read;
            }
            @Override
            public int available() throws java.io.IOException {
                return (int) Math.min(in.available(), Math.min(remaining, Integer.MAX_VALUE));
            }
            @Override
            public long skip(long count) throws java.io.IOException {
                long skipped = in.skip(Math.min(Math.max(0, count), remaining));
                remaining -= skipped;
                return skipped;
            }
        };
    }
    Path checkedPath(String objectKey);
    /** Validate the internal archive partition before removing an unpublished project copy. */
    default boolean belongsToProject(String objectKey, UUID projectId) {
        return objectKey.startsWith(projectId + "/");
    }
    void discard(String objectKey);

    /** Removes deterministic unpublished bytes without decoding or resuming remote uploads. */
    default void discardPreparedMedia(UUID projectId, UUID assetId, dev.agenvas.asset.domain.Asset.MediaKind kind) {
        if (kind == dev.agenvas.asset.domain.Asset.MediaKind.IMAGE) {
            discardPreparedImage(projectId, assetId);
            return;
        }
        if (kind != dev.agenvas.asset.domain.Asset.MediaKind.AUDIO)
            throw new IllegalArgumentException("Unsupported prepared kind");
        String prefix = projectId + "/" + assetId;
        for (String suffix : java.util.List.of(".mp3", ".wav", ".ogg"))
            discard(prefix + suffix);
    }

    /** Rejected publication cleanup; caller holds the media lock and has checked READY absence. */
    default void discardRejectedUpload(UUID projectId, UUID assetId, String original, String thumbnail) {
        try {
            discard(original);
        } finally {
            if (thumbnail != null)
                discard(thumbnail);
        }
    }

    /** Removes controlled server-derived image keys without decoding or resuming an upload. */
    default void discardPreparedImage(UUID projectId, UUID assetId) {
        String prefix = projectId + "/" + assetId;
        for (String suffix : PREPARED_IMAGE_ORIGINAL_SUFFIXES)
            discard(prefix + suffix);
        discard(prefix + PREPARED_IMAGE_THUMBNAIL_SUFFIX);
    }
}

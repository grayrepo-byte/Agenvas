package dev.agenvas.asset.storage;

import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.asset.infrastructure.LocalAssetStorage.StoredAudio;
import dev.agenvas.asset.infrastructure.LocalAssetStorage.StoredImage;
import dev.agenvas.asset.infrastructure.LocalAssetStorage.StoredVideo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Pins every ingest destination, validates locally, then archives privately without publishing partial results. */
@Component
public class ConfiguredAssetStorage implements AssetStorage {
    private static final String REMOTE_PREFIX = "objects/";
    private static final int SCHEMA_VERSION = 1;
    private static final String IMAGE_KIND = dev.agenvas.asset.domain.Asset.MediaKind.IMAGE.name();
    private static final String VIDEO_KIND = dev.agenvas.asset.domain.Asset.MediaKind.VIDEO.name();
    private static final String AUDIO_KIND = dev.agenvas.asset.domain.Asset.MediaKind.AUDIO.name();
    private static final int COPY_BUFFER_BYTES = 8192;
    private final LocalAssetStorage local;
    private final StorageRepository repository;
    private final StorageSettingsService settings;
    private final ObjectStorageClient cloud;
    private final ObjectMapper mapper;
    private final java.time.Clock clock;
    public ConfiguredAssetStorage(LocalAssetStorage local, StorageRepository repository,
            StorageSettingsService settings, ObjectStorageClient cloud, ObjectMapper mapper, java.time.Clock clock) {
        this.local = local; this.repository = repository; this.settings = settings; this.cloud = cloud; this.mapper = mapper; this.clock = clock;
    }
    /** Receipt contains only validated metadata and local staging keys, never credentials or URLs. */
    public record Archive(int schemaVersion, String kind, String key, String mime, long size, String hash,
            Integer width, Integer height, Integer duration, String thumbnail, Long thumbnailSize, String thumbnailHash) {
        static Archive from(StoredImage s) {
            return new Archive(SCHEMA_VERSION, IMAGE_KIND, s.objectKey(), s.contentType(), s.byteSize(), s.sha256(),
                    s.width(), s.height(), null, s.thumbnailKey(), s.thumbnailByteSize(), s.thumbnailSha256());
        }
        static Archive from(StoredVideo s) {
            return new Archive(SCHEMA_VERSION, VIDEO_KIND, s.objectKey(), "video/mp4", s.byteSize(), s.sha256(),
                    s.width(), s.height(), s.durationMs(), s.thumbnailKey(), s.thumbnailByteSize(), s.thumbnailSha256());
        }
        static Archive from(StoredAudio s) {
            return new Archive(SCHEMA_VERSION, AUDIO_KIND, s.objectKey(), s.contentType(), s.byteSize(), s.sha256(),
                    null, null, s.durationMs(), null, null, null);
        }
        StoredImage image() { return new StoredImage(key, mime, size, hash, width, height, thumbnail, thumbnailSize, thumbnailHash); }
        StoredVideo video() { return new StoredVideo(key, size, hash, width, height, duration, thumbnail, thumbnailSize, thumbnailHash); }
        StoredAudio audio() { return new StoredAudio(key, mime, size, hash, duration); }
    }
    @Override public StoredImage storeImage(UUID project, UUID asset, InputStream source) {
        return store(project, asset, IMAGE_KIND, () -> Archive.from(local.storeImage(project, asset, source))).image();
    }
    @Override public StoredVideo storeVideo(UUID project, UUID asset, InputStream source) {
        return store(project, asset, VIDEO_KIND, () -> Archive.from(local.storeVideo(project, asset, source))).video();
    }
    @Override public StoredAudio storeAudio(UUID project, UUID asset, InputStream source) {
        return store(project, asset, AUDIO_KIND, () -> Archive.from(local.storeAudio(project, asset, source))).audio();
    }
    private Archive store(UUID project, UUID asset, String kind, Supplier<Archive> ingest) {
        StorageRepository.Route route = repository.pin(project, asset, kind);
        Archive archive;
        if (route.metadata() != null) archive = decode(route.metadata());
        else {
            archive = ingest.get();
            repository.checkpoint(project, asset, mapper.writeValueAsString(archive));
        }
        return finish(project, asset, route, archive);
    }
    @Override public Optional<StoredImage> recoverImage(UUID project, UUID asset) {
        return recover(project, asset, IMAGE_KIND, () -> local.recoverImage(project, asset).map(Archive::from)).map(Archive::image);
    }
    @Override public Optional<StoredVideo> recoverVideo(UUID project, UUID asset) {
        return recover(project, asset, VIDEO_KIND, () -> local.recoverVideo(project, asset).map(Archive::from)).map(Archive::video);
    }
    @Override public Optional<StoredAudio> recoverAudio(UUID project, UUID asset) {
        return recover(project, asset, AUDIO_KIND, () -> local.recoverAudio(project, asset).map(Archive::from)).map(Archive::audio);
    }
    private Optional<Archive> recover(UUID project, UUID asset, String kind, Supplier<Optional<Archive>> localRecovery) {
        var existing = repository.route(project, asset);
        if (existing.isPresent() && !existing.get().mediaKind().equals(kind)) throw new IllegalStateException("Archive kind conflict");
        if (existing.isPresent() && existing.get().metadata() != null)
            return Optional.of(finish(project, asset, existing.get(), decode(existing.get().metadata())));
        var archive = localRecovery.get();
        if (archive.isEmpty()) return Optional.empty();
        // Files installed by older deployments remain local even if a cloud default is now selected.
        StorageRepository.Route route = existing.orElseGet(() -> repository.pinLocal(project, asset, kind));
        repository.checkpoint(project, asset, mapper.writeValueAsString(archive.get()));
        return Optional.of(finish(project, asset, route, archive.get()));
    }
    private Archive decode(String json) {
        Archive a = mapper.readValue(json, Archive.class);
        if (a.schemaVersion() != SCHEMA_VERSION) throw new IllegalStateException("Unsupported storage receipt version");
        return a;
    }
    private Archive finish(UUID project, UUID asset, StorageRepository.Route route, Archive a) {
        if (route.profileId() == null) {
            verifyLocal(a.key(), a.size(), a.hash());
            if (a.thumbnail() != null) verifyLocal(a.thumbnail(), a.thumbnailSize(), a.thumbnailHash());
            if (!route.ready()) repository.ready(project, asset);
            return a;
        }
        StorageProfile profile = settings.requireProfile(route.profileId());
        if (!route.ready()) {
            upload(profile, a.key(), a.mime(), a.size(), a.hash());
            if (a.thumbnail() != null) upload(profile, a.thumbnail(), "image/png", a.thumbnailSize(), a.thumbnailHash());
            repository.ready(project, asset);
        }
        // Only remove staging bytes after BOTH objects and the durable receipt are committed.
        local.discard(a.key());
        if (a.thumbnail() != null) local.discard(a.thumbnail());
        return new Archive(a.schemaVersion(), a.kind(), qualified(profile.id(), a.key()), a.mime(), a.size(), a.hash(),
                a.width(), a.height(), a.duration(), a.thumbnail() == null ? null : qualified(profile.id(), a.thumbnail()),
                a.thumbnailSize(), a.thumbnailHash());
    }
    private void upload(StorageProfile profile, String key, String mime, long size, String hash) {
        String remote = remoteKey(profile, key);
        if (cloud.matches(profile, remote, size, hash)) return;
        verifyLocal(key, size, hash);
        cloud.put(profile, remote, local.checkedPath(key), mime, size, hash);
    }
    private static String qualified(UUID profile, String key) { return REMOTE_PREFIX + profile + "/" + key; }
    private static String remoteKey(StorageProfile p, String key) {
        // Config identity isolates distinct connections even when they share a bucket and prefix.
        return (p.keyPrefix().isEmpty() ? "" : p.keyPrefix() + "/") + p.id() + "/" + key;
    }
    private record Remote(StorageProfile profile, String key, Archive archive, boolean thumbnail) {}
    private Remote remote(String objectKey) {
        String[] parts = objectKey.split("/", 4);
        if (parts.length != 4 || !parts[0].equals("objects")) throw new IllegalArgumentException("Invalid remote key");
        UUID profileId = UUID.fromString(parts[1]);
        UUID project = UUID.fromString(parts[2]);
        String filename = parts[3];
        UUID asset = UUID.fromString(filename.substring(0, filename.indexOf('.')));
        var route = repository.route(project, asset).orElseThrow(() -> new IllegalStateException("Remote receipt missing"));
        if (!route.ready() || !profileId.equals(route.profileId()) || route.metadata() == null)
            throw new IllegalStateException("Remote receipt does not match asset location");
        Archive archive = decode(route.metadata());
        String key = parts[2] + "/" + filename;
        boolean thumbnail = key.equals(archive.thumbnail());
        if (!thumbnail && !key.equals(archive.key())) throw new IllegalStateException("Remote key differs from receipt");
        return new Remote(settings.requireProfile(profileId), key, archive, thumbnail);
    }
    /** Internal provider input descriptor, validated against the asset's durable archive route. */
    public record CloudObject(StorageProfile profile, String key) {}
    public Optional<CloudObject> cloudObject(String objectKey) {
        if (!objectKey.startsWith(REMOTE_PREFIX)) return Optional.empty();
        Remote r = remote(objectKey);
        if (r.thumbnail()) throw new IllegalArgumentException("Provider references must use original bytes");
        return Optional.of(new CloudObject(r.profile(), remoteKey(r.profile(), r.key())));
    }

    @Override public void verify(String key, long size, String hash) {
        if (!key.startsWith(REMOTE_PREFIX)) { local.verify(key, size, hash); return; }
        Remote r = remote(key);
        if (!cloud.matches(r.profile(), remoteKey(r.profile(), r.key()), size, hash))
            throw new IllegalStateException("READY remote object missing");
    }
    private void verifyLocal(String key, long size, String hash) {
        verifyCache(local.checkedPath(key), size, hash);
    }
    @Override public InputStream open(String key, long start, long length, long total) throws IOException {
        if (!key.startsWith(REMOTE_PREFIX)) return local.open(key, start, length, total);
        Remote r = remote(key);
        return cloud.open(r.profile(), remoteKey(r.profile(), r.key()), start, length, total);
    }
    @Override public Path checkedPath(String objectKey) {
        if (!objectKey.startsWith(REMOTE_PREFIX)) return local.checkedPath(objectKey);
        Path cached = local.checkedPath(".cloud-cache/" + objectKey);
        synchronized (ObjectStorageCache.lock(cached)) {
            Path path = materialize(objectKey);
            try { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(clock.instant())); }
            catch (IOException failure) { throw new IllegalStateException("Cannot refresh processing cache lease", failure); }
            return path;
        }
    }
    private Path materialize(String objectKey) {
        if (!objectKey.startsWith(REMOTE_PREFIX)) return local.checkedPath(objectKey);
        Remote r = remote(objectKey);
        Archive a = r.archive();
        long size = r.thumbnail() ? a.thumbnailSize() : a.size();
        String hash = r.thumbnail() ? a.thumbnailHash() : a.hash();
        Path cached = local.checkedPath(".cloud-cache/" + objectKey);
        if (Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) { verifyCache(cached, size, hash); return cached; }
        Path temporary = null;
        try {
            Files.createDirectories(cached.getParent());
            temporary = Files.createTempFile(cached.getParent(), ".download-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (InputStream input = new DigestInputStream(open(objectKey, 0, size, size), digest);
                    var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES]; int read;
                while ((read = input.read(buffer)) != -1) { count += read; if (count > size) throw new IOException("Object exceeds recorded size"); output.write(buffer, 0, read); }
            }
            if (count != size || !hash.equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("Object hash mismatch");
            Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE); temporary = null;
            return cached;
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot materialize private object for media processing", failure);
        } finally {
            if (temporary != null) local.discard(".cloud-cache/" + objectKey.substring(0, objectKey.lastIndexOf('/') + 1) + temporary.getFileName());
        }
    }
    private void verifyCache(Path file, long size, String hash) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != size)
                throw new IOException("Cached object size mismatch");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES]; while (input.read(buffer) != -1) { /* bounded hash */ }
            }
            if (!hash.equals(HexFormat.of().formatHex(digest.digest()))) throw new IOException("Cached object hash mismatch");
        } catch (IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Invalid private cache", failure); }
    }
    @Override public boolean belongsToProject(String key, UUID project) {
        if (!key.startsWith(REMOTE_PREFIX)) return AssetStorage.super.belongsToProject(key, project);
        // The durable route validates the cloud connection and exact original/thumbnail key.
        return remote(key).key().startsWith(project + "/");
    }
    @Override public void discard(String key) {
        if (!key.startsWith(REMOTE_PREFIX)) { local.discard(key); return; }
        Remote r = remote(key); cloud.delete(r.profile(), remoteKey(r.profile(), r.key()));
        local.discard(".cloud-cache/" + key);
    }

    /** Cleanup uses the pinned receipt directly; recovering an unfinished route would upload it. */
    @Override public void discardPreparedImage(UUID project, UUID asset) {
        var route = repository.route(project, asset);
        if (route.isPresent()) {
            if (!IMAGE_KIND.equals(route.get().mediaKind())) throw new IllegalStateException("Prepared image route kind mismatch");
            if (route.get().metadata() != null && route.get().profileId() != null) {
                Archive receipt = decode(route.get().metadata());
                String prefix = project + "/" + asset;
                boolean originalIdentity = AssetStorage.PREPARED_IMAGE_ORIGINAL_SUFFIXES.stream()
                        .anyMatch(suffix -> receipt.key().equals(prefix + suffix));
                boolean thumbnailIdentity = receipt.thumbnail() == null
                        || receipt.thumbnail().equals(prefix + AssetStorage.PREPARED_IMAGE_THUMBNAIL_SUFFIX);
                if (!IMAGE_KIND.equals(receipt.kind()) || !originalIdentity || !thumbnailIdentity)
                    throw new IllegalStateException("Prepared image receipt identity mismatch");
                StorageProfile profile = settings.requireProfile(route.get().profileId());
                discardPreparedCloudFile(profile, receipt.key());
                if (receipt.thumbnail() != null) discardPreparedCloudFile(profile, receipt.thumbnail());
            }
        }
        local.discardPreparedImage(project, asset);
    }

    private void discardPreparedCloudFile(StorageProfile profile, String key) {
        cloud.delete(profile, remoteKey(profile, key));
        local.discard(".cloud-cache/" + qualified(profile.id(), key));
    }
    @Override public <T> T withTaskImageLock(UUID project, UUID asset, Supplier<T> action) {
        return local.withTaskImageLock(project, asset, action);
    }
    @Override public <T> T withTaskVideoLock(UUID project, UUID asset, Supplier<T> action) {
        return local.withTaskVideoLock(project, asset, action);
    }
    @Override public <T> T withTaskAudioLock(UUID project, UUID asset, Supplier<T> action) {
        return local.withTaskAudioLock(project, asset, action);
    }
}

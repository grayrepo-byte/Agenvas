package dev.agenvas.asset.storage;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Provider-only temporary access; it neither changes asset location nor exposes URLs to clients. */
@Service
public class MediaRelayService {
    public static final Duration URL_LIFETIME = Duration.ofHours(72);
    public static final Duration COPY_RETENTION = Duration.ofDays(7);
    private static final String RELAY_DIRECTORY = "media-relay/";
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaRelayService.class);
    private final AssetService assets;
    private final ConfiguredAssetStorage storage;
    private final StorageSettingsService settings;
    private final ObjectStorageClient cloud;
    private final MediaRelayRepository copies;
    private final Clock clock;
    public MediaRelayService(AssetService assets, ConfiguredAssetStorage storage, StorageSettingsService settings,
            ObjectStorageClient cloud, MediaRelayRepository copies, Clock clock) {
        this.assets = assets; this.storage = storage; this.settings = settings;
        this.cloud = cloud; this.copies = copies; this.clock = clock;
    }

    /** No network I/O: freeze the relay connection at acceptance, independent of archive settings. */
    public UUID pinProfile(Asset asset) {
        if (storage.cloudObject(asset.objectKey()).isPresent()) return null;
        UUID profile = settings.relayProfileId();
        if (profile == null) throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MEDIA_RELAY_REQUIRED",
                ApiMessage.of("api.media-relay.unavailable"), ApiMessage.of("api.media-relay.required"), false);
        settings.requireProfile(profile);
        return profile;
    }

    /** Validate before provider submission; the private archive endpoint need not be public in general. */
    public void preflight(Asset asset, UUID pinnedProfile) {
        var original = storage.cloudObject(asset.objectKey());
        StorageProfile profile = original.isPresent() ? original.get().profile() : requirePinned(pinnedProfile);
        cloud.requirePublic(profile);
        settings.credentials(profile);
    }

    public String signedVideo(UUID owner, UUID project, UUID assetId, UUID pinnedProfile) {
        Asset asset = assets.requireReadyMedia(owner, project, assetId, Asset.MediaKind.VIDEO);
        var original = storage.cloudObject(asset.objectKey());
        if (original.isPresent()) {
            var object = original.get();
            // Sign the recorded original location, even when the archive default has since changed.
            return cloud.signedGet(object.profile(), object.key(), URL_LIFETIME);
        }
        StorageProfile profile = requirePinned(pinnedProfile);
        cloud.requirePublic(profile);
        UUID copyId = UUID.randomUUID();
        String key = (profile.keyPrefix().isEmpty() ? "" : profile.keyPrefix() + "/")
                + RELAY_DIRECTORY + copyId + ".mp4";
        var copy = new MediaRelayRepository.Copy(copyId, profile.id(), key, clock.instant().plus(COPY_RETENTION));
        // Commit cleanup identity BEFORE PUT. A lost response or process crash cannot orphan the object.
        copies.register(copy, clock.instant());
        var file = assets.get(owner, project, assetId);
        cloud.put(profile, key, file.path(), asset.contentType(), asset.byteSize(), asset.sha256());
        return cloud.signedGet(profile, key, URL_LIFETIME);
    }

    private StorageProfile requirePinned(UUID profile) {
        if (profile == null) throw new IllegalArgumentException("Frozen video reference omitted its relay connection");
        return settings.requireProfile(profile);
    }

    @Scheduled(fixedDelayString = "${agenvas.storage.relay-cleanup-interval:PT1H}")
    public void cleanup() {
        for (var copy : copies.expired(clock.instant())) {
            try {
                cloud.delete(settings.requireProfile(copy.profileId()), copy.key());
                copies.removed(copy);
            } catch (RuntimeException failure) {
                // Retain the row for a later cleanup; vendor exceptions/URLs must never reach logs.
                LOGGER.warn("Media relay copy cleanup failed; retained for retry");
            }
        }
    }
}

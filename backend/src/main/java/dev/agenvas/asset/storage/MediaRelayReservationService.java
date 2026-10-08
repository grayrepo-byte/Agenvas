package dev.agenvas.asset.storage;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A short transaction protects a relay destination from relocation/deletion before its first PUT. */
@Service
public class MediaRelayReservationService {
    private final StorageRepository storage;
    private final StorageSettingsService settings;
    private final MediaRelayRepository copies;
    private final Clock clock;

    public MediaRelayReservationService(StorageRepository storage, StorageSettingsService settings,
            MediaRelayRepository copies, Clock clock) {
        this.storage = storage; this.settings = settings; this.copies = copies; this.clock = clock;
    }

    @Transactional
    public Reservation reserve(UUID profileId, String extension) {
        storage.lockVersion();
        StorageProfile profile = settings.requireProfile(profileId);
        UUID id = UUID.randomUUID();
        String key = (profile.keyPrefix().isEmpty() ? "" : profile.keyPrefix() + "/")
                + "media-relay/" + id + extension;
        var copy = new MediaRelayRepository.Copy(id, profile.id(), key,
                clock.instant().plus(MediaRelayService.COPY_RETENTION));
        copies.register(copy, clock.instant());
        return new Reservation(profile, copy);
    }

    public record Reservation(StorageProfile profile, MediaRelayRepository.Copy copy) {}
}

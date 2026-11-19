package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Snapshot;
import org.springframework.stereotype.Service;

/** Reads a published binding independently of installed runtime adapters and current enablement. */
@Service
public class PinnedMediaPublications {
    private final JooqMediaCapabilityRepository repository;

    public PinnedMediaPublications(JooqMediaCapabilityRepository repository) {
        this.repository = repository;
    }

    public boolean isLocal(MediaCapabilityBinding binding) {
        return snapshot(binding).connection().platform() == MediaPlatform.LOCAL;
    }

    Snapshot snapshot(MediaCapabilityBinding binding) {
        Snapshot snapshot = repository.snapshotAt(binding.capabilityId(),
                binding.capabilityVersion(), binding.connectionId(),
                binding.connectionVersion())
                .orElseThrow(() -> new IllegalStateException("Pinned media capability version is missing"));
        if (!binding.adapterId().equals(snapshot.adapterId())
                || !binding.mappingSha256().equals(snapshot.mappingSha256())) {
            throw new IllegalStateException("Pinned media adapter identity differs from history");
        }
        return snapshot;
    }
}

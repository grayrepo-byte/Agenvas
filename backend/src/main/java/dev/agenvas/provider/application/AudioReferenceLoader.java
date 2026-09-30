package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.domain.Task;
import dev.agenvas.artifact.domain.AudioGenerationParameters;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reads only authorized immutable archived inputs, with each protocol's byte/time bounds. */
@Component
final class AudioReferenceLoader {
    static final int SEED_MAX_BYTES = AudioGenerationParameters.MAX_REFERENCE_BYTES;
    static final int SEED_MAX_DURATION_MS = AudioGenerationParameters.MAX_REFERENCE_DURATION_MS;
    static final int ARK_MAX_BYTES = MediaAdapterRegistry.SEEDANCE_MAX_AUDIO_BYTES;
    static final int ARK_MIN_DURATION_MS = MediaAdapterRegistry.SEEDANCE_MIN_AUDIO_DURATION_MS;
    static final int ARK_MAX_DURATION_MS = MediaAdapterRegistry.SEEDANCE_MAX_AUDIO_DURATION_MS;
    private final ArtifactService artifacts;
    private final AssetService assets;
    AudioReferenceLoader(ArtifactService artifacts, AssetService assets) {
        this.artifacts = artifacts; this.assets = assets;
    }
    record Reference(String contentType, byte[] bytes) {}

    List<Reference> load(UUID ownerId, Task task, boolean seedAudio) {
        List<Reference> result = new ArrayList<>();
        long totalDuration = 0;
        for (var input : FrozenMediaInputs.audios(task)) {
            var version = artifacts.requireVersion(ownerId, task.projectId(), input.artifactId(), input.versionId());
            var file = assets.get(ownerId, task.projectId(), UUID.fromString(version.content().path("assetId").asText()));
            Asset asset = file.asset();
            int maximumBytes = seedAudio ? SEED_MAX_BYTES : ARK_MAX_BYTES;
            int maximumDuration = seedAudio ? SEED_MAX_DURATION_MS : ARK_MAX_DURATION_MS;
            if (asset.mediaKind() != Asset.MediaKind.AUDIO || asset.byteSize() > maximumBytes
                    || asset.durationMs() == null || asset.durationMs() > maximumDuration
                    || !seedAudio && (asset.durationMs() < ARK_MIN_DURATION_MS
                            || !java.util.Set.of("audio/wav", "audio/mpeg").contains(asset.contentType())))
                throw new IllegalArgumentException("Audio reference exceeds fixed protocol bounds");
            totalDuration += asset.durationMs();
            try (var stream = Files.newInputStream(file.path())) {
                byte[] bytes = stream.readNBytes(maximumBytes + 1);
                if (bytes.length == 0 || bytes.length > maximumBytes) throw new IllegalArgumentException("Audio size invalid");
                result.add(new Reference(asset.contentType(), bytes));
            } catch (IOException failure) { throw new IllegalStateException("Archived audio unavailable", failure); }
        }
        if (result.size() > 3 || !seedAudio && totalDuration > ARK_MAX_DURATION_MS)
            throw new IllegalArgumentException("Audio references exceed fixed protocol bounds");
        return List.copyOf(result);
    }
}

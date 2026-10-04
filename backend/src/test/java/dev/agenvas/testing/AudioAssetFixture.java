package dev.agenvas.testing;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Archives synthetic silence through the real audio decoding and metadata path, without a Provider. */
public final class AudioAssetFixture {
    private AudioAssetFixture() {}

    public static UUID archive(AssetService assets, MediaToolRunner tools, UUID ownerId, UUID projectId) throws IOException {
        var source = Files.createTempFile("agenvas-synthetic-audio-", ".wav");
        try {
            tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "anullsrc=r=8000:cl=mono", "-t", "1", "-c:a", "pcm_s16le", "-y", source.toString()));
            try (var input = Files.newInputStream(source)) {
                return assets.archiveAudio(ownerId, projectId, input).id();
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }
}

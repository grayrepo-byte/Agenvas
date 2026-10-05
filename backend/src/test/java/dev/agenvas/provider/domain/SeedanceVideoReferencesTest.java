package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeedanceVideoReferencesTest {
    private Asset video(long bytes, Integer duration, Integer width, Integer height) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.VIDEO, "synthetic.mp4",
                "video/mp4", bytes, "a".repeat(64), width, height, duration, null, null, null, Instant.EPOCH);
    }
    @Test void acceptsInclusiveDurationAndSizeBoundsForLandscapeAndPortrait() {
        assertThatCode(() -> SeedanceVideoReferences.validateMetadata(video(200L * 1024 * 1024, 2000, 1280, 720)))
                .doesNotThrowAnyException();
        assertThatCode(() -> SeedanceVideoReferences.validateMetadata(video(100, 15000, 720, 1280)))
                .doesNotThrowAnyException();
    }
    @Test void rejectsDurationBytesMissingDimensionsPixelCountAndAspectRatio() {
        for (Asset invalid : new Asset[] { video(200L * 1024 * 1024 + 1, 2000, 1280, 720),
                video(100, 1999, 1280, 720), video(100, 15001, 1280, 720), video(100, null, 1280, 720),
                video(100, 4000, null, 720), video(100, 4000, 299, 1400), video(100, 4000, 300, 300),
                video(100, 4000, 6000, 6000), video(100, 4000, 2000, 700) })
            assertThatThrownBy(() -> SeedanceVideoReferences.validateMetadata(invalid))
                    .isInstanceOf(ApiProblemException.class)
                    .satisfies(f -> assertThat(((ApiProblemException) f).code()).isEqualTo("SEEDANCE_VIDEO_REFERENCE_INVALID"));
    }
}

package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.VideoOperation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalVideoOperationBoundsTest {
    @Test
    void audioAcceptsAnArchivedTenMinuteVideoWhileDepthKeepsItsInferenceBudget() {
        var asset = video(600_000, 500L * 1024 * 1024, 7680, 4320);
        assertThatCode(() -> LocalVideoOperationBounds.require(asset, VideoOperation.EXTRACT_AUDIO)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LocalVideoOperationBounds.require(asset, VideoOperation.DEPTH_MAP))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.detail().key()).isEqualTo("api.media-function.local-limits"));
    }

    @Test
    void rejectsAudioInputOutsideTheCommonArchiveBounds() {
        var asset = video(600_001, 1024, 16, 16);
        assertThatThrownBy(() -> LocalVideoOperationBounds.require(asset, VideoOperation.EXTRACT_AUDIO))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.detail().key()).isEqualTo("api.media-function.local-audio-limits"));
    }

    @Test
    void preservesShortWavButRequiresExplicitMp3ForLongAudio() {
        var shortVideo = video(30_000, 1024, 16, 16);
        var longVideo = video(600_000, 1024, 16, 16);
        var wav = new AudioExtractionParameters(AudioExtractionParameters.Format.WAV, 0);
        var mp3 = new AudioExtractionParameters(AudioExtractionParameters.Format.MP3, 0);
        assertThatCode(() -> LocalVideoOperationBounds.requireAudioOutput(shortVideo, wav)).doesNotThrowAnyException();
        assertThatCode(() -> LocalVideoOperationBounds.requireAudioOutput(longVideo, mp3)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LocalVideoOperationBounds.requireAudioOutput(longVideo, wav))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("LOCAL_VIDEO_AUDIO_OUTPUT_TOO_LARGE"));
    }

    private Asset video(int duration, long bytes, int width, int height) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.VIDEO,
                "synthetic.mp4", "video/mp4", bytes, "synthetic", width, height, duration, null, null, null, Instant.EPOCH);
    }
}

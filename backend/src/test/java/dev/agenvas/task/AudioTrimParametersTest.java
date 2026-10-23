package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.AudioExtractionParameters.Format;
import dev.agenvas.task.domain.AudioTrimParameters;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AudioTrimParametersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void requiresExactIntegralTimingAndKnownFields() {
        for (String json : new String[] {"{\"startMs\":0.5,\"endMs\":1000,\"format\":\"WAV\"}",
                "{\"startMs\":0,\"endMs\":1000,\"format\":\"WAV\",\"filter\":\"anything\"}",
                "{\"startMs\":0,\"endMs\":99,\"format\":\"WAV\"}",
                "{\"startMs\":1000,\"endMs\":1000,\"format\":\"WAV\"}",
                "{\"startMs\":-1,\"endMs\":1000,\"format\":\"WAV\"}",
                "{\"startMs\":0,\"endMs\":600001,\"format\":\"WAV\"}",
                "{\"startMs\":0,\"endMs\":1000,\"format\":\"FLAC\"}"}) {
            assertThatThrownBy(() -> AudioTrimParameters.parse(mapper.readTree(json)))
                    .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("AUDIO_TRIM_INVALID"));
        }
        var expected = new AudioTrimParameters(123, 223, Format.WAV);
        assertThat(AudioTrimParameters.parse(expected.toJson(mapper))).isEqualTo(expected);
    }

    @Test void oversizedWavRejectedBeforeTaskButMp3Fits() {
        var source = source(600_000, 5_000_000);
        assertThatThrownBy(() -> new AudioTrimParameters(0, 600_000, Format.WAV).requireSource(source))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("AUDIO_TRIM_OUTPUT_TOO_LARGE"));
        new AudioTrimParameters(0, 600_000, Format.MP3).requireSource(source);
        new AudioTrimParameters(599_900, 600_000, Format.WAV).requireSource(source);
    }

    @Test void sourceDurationAndByteLimitsRemainIndependentFromTrimDuration() {
        var shortTrim = new AudioTrimParameters(0, 100, Format.WAV);
        for (Asset source : new Asset[] {source(99, 100), source(600_001, 100),
                source(1000, AudioTrimParameters.MAX_SOURCE_BYTES + 1)})
            assertThatThrownBy(() -> shortTrim.requireSource(source)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> new AudioTrimParameters(900, 1100, Format.MP3).requireSource(source(1000, 1000)))
                .isInstanceOf(ApiProblemException.class);
    }

    private Asset source(int duration, long bytes) {
        var asset = mock(Asset.class);
        when(asset.mediaKind()).thenReturn(Asset.MediaKind.AUDIO);
        when(asset.durationMs()).thenReturn(duration);
        when(asset.byteSize()).thenReturn(bytes);
        return asset;
    }
}

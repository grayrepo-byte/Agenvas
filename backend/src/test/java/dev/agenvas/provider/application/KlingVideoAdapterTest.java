package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KlingVideoAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaToolRunner tools = mock(MediaToolRunner.class);
    private final KlingVideoAdapter adapter = new KlingVideoAdapter(mock(JooqMediaCapabilityRepository.class),
            mock(CredentialCipher.class), mock(KlingClient.class), mock(KlingMediaDownloadPolicy.class), mapper,
            mock(KlingPreparedMedia.class), tools);

    @Test void validatesOrdinaryDurationAndPreservesNativeAudioStreams() {
        var profile = KlingModels.resolve("kling-3.0", "GENERATE", Task.Kind.VIDEO_GENERATION);
        var values = mapper.createObjectNode().put("duration", 5).put("audio", "native");
        probe(5.2, true, true);
        adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        probe(9, true, true);
        assertThatThrownBy(() -> adapter.validateResult(null, profile, values, Path.of("synthetic-result")))
                .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
        probe(5, false, true);
        assertThatThrownBy(() -> adapter.validateResult(null, profile, values, Path.of("synthetic-result")))
                .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
    }

    @Test void acceptsShorterValidMotionSegmentsAndRejectsInvalidOrOverlongResults() {
        var profile = KlingModels.resolve("kling-3.0", "MOTION_CONTROL", Task.Kind.VIDEO_GENERATION);
        var values = mapper.createObjectNode().put("character_orientation", "video").put("audio", "original");
        probe(3, true, true);
        adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        probe(12, true, true);
        adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        for (double invalid : new double[] {1, 31, Double.NaN}) {
            probe(invalid, true, true);
            assertThatThrownBy(() -> adapter.validateResult(null, profile, values, Path.of("synthetic-result")))
                    .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
        }
        values.put("character_orientation", "image");
        probe(10, true, true);
        adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        probe(12, true, true);
        assertThatThrownBy(() -> adapter.validateResult(null, profile, values, Path.of("synthetic-result")))
                .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
    }

    @Test void baseVideoEditCanKeepItsSourceDurationInsteadOfTheDefaultRequestedDuration() {
        var profile = KlingModels.resolve("kling-3.0-omni", "GENERATE", Task.Kind.VIDEO_GENERATION);
        var values = mapper.createObjectNode().put("duration", 5).put("base_video", java.util.UUID.randomUUID().toString());
        probe(10, true, true);
        adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
    }

    private void probe(double duration, boolean video, boolean audio) {
        var probe = mapper.createObjectNode();
        probe.putObject("format").put("format_name", "mov,mp4").put("duration", Double.toString(duration));
        var streams = probe.putArray("streams");
        if (video) streams.addObject().put("codec_type", "video");
        if (audio) streams.addObject().put("codec_type", "audio");
        when(tools.ffprobe(anyList())).thenReturn(mapper.writeValueAsString(probe));
    }
}

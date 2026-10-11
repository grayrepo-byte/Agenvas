package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.WanClient;
import dev.agenvas.provider.infrastructure.WanMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WanVideoAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaToolRunner tools = mock(MediaToolRunner.class);
    private final WanVideoAdapter adapter = new WanVideoAdapter(mock(JooqMediaCapabilityRepository.class), mock(CredentialCipher.class),
            mock(WanClient.class), mock(WanMediaDownloadPolicy.class), mapper, mock(NativeMediaReferences.class), tools);
    private final dev.agenvas.provider.domain.NativeMediaProtocol.Profile profile = new WanModels().profiles().get(2);
    @Test void preservesNativeAudioAndVerifiesFixedDurationAgainstActualMp4() {
        var values = mapper.createObjectNode().put("duration", 5).put("audio", true);
        probe(5.2, true, true); adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        probe(9, true, true); assertInvalid(values);
        probe(5, true, false); assertInvalid(values);
        probe(5, false, true); assertInvalid(values);
        values.put("audio", false);
        probe(5, true, false); adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        probe(5, true, true); assertInvalid(values);
    }
    @Test void automaticDurationAllowsNativeRangeWhileInputPlusOutputRemainsBounded() {
        var values = mapper.createObjectNode().put("duration", 5).put("audio", true).put("smart_duration", true);
        probe(20, true, true); adapter.validateResult(null, profile, values, Path.of("synthetic-result"));
        for (double invalid : new double[] {1, 31, Double.NaN}) { probe(invalid, true, true); assertInvalid(values); }
        Task lease = mock(Task.class); when(lease.input()).thenReturn(mapper.createObjectNode().put("nativeBillableInputSeconds", 15));
        var context = new AttemptContext(lease, null, null, null, "synthetic-original");
        probe(15, true, true); adapter.validateResult(context, profile, values, Path.of("synthetic-result"));
        probe(16, true, true);
        assertThatThrownBy(() -> adapter.validateResult(context, profile, values, Path.of("synthetic-result")))
                .isInstanceOf(NativeMediaFailure.class).hasMessage("PROVIDER_PROTOCOL_INVALID");
    }
    @Test void invalidReturnedMediaIsPermanentWhileMissingMediaToolsRemainRetryable() {
        var values = mapper.createObjectNode().put("duration", 5).put("audio", true);
        doThrow(new MediaToolRunner.MediaToolException("synthetic decode failure", true, null)).when(tools).ffprobe(anyList());
        assertInvalid(values);
        doThrow(new MediaToolRunner.MediaToolException("synthetic tool unavailable", false, null)).when(tools).ffprobe(anyList());
        assertThatThrownBy(() -> adapter.validateResult(null, profile, values, Path.of("synthetic-result")))
                .isInstanceOf(MediaToolRunner.MediaToolException.class);
    }
    private void assertInvalid(tools.jackson.databind.JsonNode values) {
        assertThatThrownBy(() -> adapter.validateResult(null, profile, values, Path.of("synthetic-result")))
                .isInstanceOf(NativeMediaFailure.class).hasMessage("PROVIDER_PROTOCOL_INVALID");
    }
    private void probe(double duration, boolean video, boolean audio) {
        var result = mapper.createObjectNode(); result.putObject("format").put("format_name", "mov,mp4").put("duration", Double.toString(duration));
        var streams = result.putArray("streams"); if (video) streams.addObject().put("codec_type", "video"); if (audio) streams.addObject().put("codec_type", "audio");
        when(tools.ffprobe(anyList())).thenReturn(mapper.writeValueAsString(result));
    }
}

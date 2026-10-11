package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KlingVideo3AdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JooqMediaCapabilityRepository catalog = mock(JooqMediaCapabilityRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final KlingClient client = spy(new KlingClient(mapper, URI.create("http://127.0.0.1:65534")));
    private final KlingReferenceLoader references = mock(KlingReferenceLoader.class);
    private final MediaToolRunner mediaTools = mock(MediaToolRunner.class);
    private final KlingVideo3Adapter adapter = new KlingVideo3Adapter(catalog, cipher, client,
            mock(KlingMediaDownloadPolicy.class), mapper, references, mediaTools);

    @Test void preflightWorksBeforeTheRequestKeyExistsAndSendsNoGeneration() {
        UUID connection = UUID.randomUUID(), capability = UUID.randomUUID();
        var binding = new MediaCapabilityBinding(connection, 1, capability, 1, KlingProtocol.VIDEO_ADAPTER_ID, "a".repeat(64));
        var version = new JooqMediaCapabilityRepository.ConnectionVersion(connection, 1, KlingClient.ORIGIN, null,
                new byte[] {1}, new byte[] {2}, 1, "masked");
        var snapshot = new JooqMediaCapabilityRepository.Snapshot(null, null, version,
                KlingProtocol.VIDEO_ADAPTER_ID, binding.mappingSha256(), "{}");
        when(catalog.snapshotAt(capability, 1, connection, 1)).thenReturn(Optional.of(snapshot));
        when(cipher.decryptMedia(eq(connection), eq(1), any(CredentialCipher.Encrypted.class))).thenReturn("synthetic-key");
        var context = context(binding);
        when(references.load(context, true)).thenReturn(List.of());
        assertThat(adapter.preflightFailure(context)).isNull();
        verify(client, never()).createVideo(anyString(), anyString(), any());
        assertThat(adapter.prepare(context, false).path("options").path("external_task_id").asText()).isEqualTo("preflight-only");
        assertThatThrownBy(() -> adapter.prepare(context, true)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }

    @Test void validatesARealVideoStreamAndItsDurationWithoutRemovingNativeAudio() {
        var context = context(null);
        when(mediaTools.ffprobe(anyList())).thenReturn("{\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"5.2\"},\"streams\":[{\"codec_type\":\"video\"},{\"codec_type\":\"audio\"}]}");
        adapter.validateResult(context, Path.of("synthetic-result"));
        for (String invalid : List.of("{\"format\":{\"format_name\":\"mp4\",\"duration\":\"9\"},\"streams\":[{\"codec_type\":\"video\"}]}",
                "{\"format\":{\"format_name\":\"mp4\",\"duration\":\"5\"},\"streams\":[{\"codec_type\":\"audio\"}]}")) {
            when(mediaTools.ffprobe(anyList())).thenReturn(invalid);
            assertThatThrownBy(() -> adapter.validateResult(context, Path.of("synthetic-result")))
                    .isInstanceOf(KlingMediaDownloadPolicy.Rejected.class);
        }
    }

    private AttemptContext context(MediaCapabilityBinding binding) {
        Task task = mock(Task.class);
        var input = mapper.createObjectNode().put("prompt", "A mountain").put("durationSeconds", 5);
        var media = input.putObject("mediaInput").put("mode", "TEXT");
        media.putObject("parameters").put("aspectRatio", "AUTO").put("videoResolution", "1080p");
        media.putObject("providerParameters").put("aspectRatio", "16:9");
        when(task.input()).thenReturn(input);
        return new AttemptContext(task, binding, UUID.randomUUID(), null, null);
    }
}

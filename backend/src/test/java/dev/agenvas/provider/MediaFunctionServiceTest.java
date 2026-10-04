package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaFunctionRepository;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MediaFunctionServiceTest {
    private final JooqMediaFunctionRepository repository = mock(JooqMediaFunctionRepository.class);
    private final MediaCapabilityService catalog = mock(MediaCapabilityService.class);
    private final MediaFunctionService service = new MediaFunctionService(repository, catalog, Clock.systemUTC());

    @Test void rejectsGenerationCapabilitiesWithoutVideoInput() {
        UUID id = UUID.randomUUID();
        var binding = new MediaCapabilityBinding(UUID.randomUUID(), 1, id, 1, "COMFY_VIDEO_V1", "a".repeat(64));
        when(catalog.resolve(id, Task.Kind.VIDEO_GENERATION, 0)).thenReturn(binding);
        assertThatThrownBy(() -> service.update(VideoOperation.UPSCALE, 0, id))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_FUNCTION_INCOMPATIBLE");
        verify(repository, never()).update(any(), anyLong(), any(), any());
    }

    @Test void allowsLocalAudioOnlyForExtraction() {
        UUID id = MediaFunctionService.LOCAL_AUDIO_CAPABILITY;
        var binding = new MediaCapabilityBinding(UUID.randomUUID(), 1, id, 1,
                MediaAdapterRegistry.LOCAL_VIDEO_AUDIO_EXTRACTOR, "a".repeat(64));
        when(catalog.resolve(id, Task.Kind.AUDIO_GENERATION, 0)).thenReturn(binding);
        when(repository.update(eq(VideoOperation.EXTRACT_AUDIO), eq(3L), eq(id), any())).thenReturn(true);
        service.update(VideoOperation.EXTRACT_AUDIO, 3, id);
        verify(repository).update(eq(VideoOperation.EXTRACT_AUDIO), eq(3L), eq(id), any());
    }

    @Test void settingChangesBlockBeforeResolvingProvider() {
        when(repository.get(VideoOperation.UPSCALE)).thenReturn(new JooqMediaFunctionRepository.Setting(VideoOperation.UPSCALE, UUID.randomUUID(), 2));
        assertThatThrownBy(() -> service.resolve(VideoOperation.UPSCALE, 1))
                .isInstanceOf(ApiProblemException.class).extracting("code").isEqualTo("MEDIA_FUNCTION_CONFLICT");
        verifyNoInteractions(catalog);
    }

    @Test void requiresOneUnconditionalVideoSlot() {
        var mapper = new ObjectMapper();
        var definition = RunningHubDefinition.parse(mapper, mapper.readTree("""
                {"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
                 "fields":[{"key":"video","label":"Video","type":"VIDEO","nodeId":"1","fieldName":"video","required":true}],
                 "outputs":[{"nodeId":"2","kind":"VIDEO","primary":true,"maxCount":1}]}
                """), Task.Kind.VIDEO_GENERATION);
        assertThat(MediaFunctionService.compatibleDefinition(definition)).isTrue();
        assertThat(MediaFunctionService.compatibleDefinition(null)).isFalse();
    }
}

package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Only compiled adapter IDs can be published; runtime implementations register as Spring beans. */
@Component
public final class MediaAdapterRegistry {

    public record Declaration(MediaPlatform platform, Task.Kind kind, int minimumSeconds,
            int maximumSeconds, boolean originRequired, int maxReferenceImages,
            Set<String> supportedVideoInputModes, String defaultVideoInputMode,
            boolean supportsEndFrame) {}

    private static final Map<String, Declaration> DECLARATIONS = Map.of(
            "MOCK_IMAGE", image(MediaPlatform.MOCK, false, 4),
            "MOCK_VIDEO", video(MediaPlatform.MOCK, 1, 30, false, true),
            "COMFY_IMAGE_V1", image(MediaPlatform.COMFYUI, true, 1),
            "COMFY_VIDEO_V1", video(MediaPlatform.COMFYUI, 1, 5, true, false),
            "OPENAI_GPT_IMAGE_2", image(MediaPlatform.OPENAI, false, 1),
            "GOOGLE_NANO_BANANA_2", image(MediaPlatform.GOOGLE, false, 1),
            "ARK_SEEDANCE_2_I2V", video(MediaPlatform.ARK, 4, 15, false, false));

    private static Declaration image(MediaPlatform platform, boolean originRequired,
            int maxReferenceImages) {
        return new Declaration(platform, Task.Kind.IMAGE_GENERATION, 0, 0, originRequired,
                maxReferenceImages, Set.of(), null, false);
    }

    private static Declaration video(MediaPlatform platform, int minimumSeconds,
            int maximumSeconds, boolean originRequired, boolean supportsEndFrame) {
        return new Declaration(platform, Task.Kind.VIDEO_GENERATION, minimumSeconds,
                maximumSeconds, originRequired, 0, Set.of("START_END"), "START_END",
                supportsEndFrame);
    }

    private final Map<String, MediaAdapter> implementations;

    public MediaAdapterRegistry(List<MediaAdapter> adapters) {
        this.implementations = adapters.stream().collect(Collectors.toUnmodifiableMap(
                MediaAdapter::adapterId, Function.identity()));
        for (String id : implementations.keySet()) {
            declaration(id);
        }
    }

    public Declaration declaration(String adapterId) {
        Declaration result = DECLARATIONS.get(adapterId);
        if (result == null) {
            throw unsupported();
        }
        return result;
    }

    public boolean supports(String adapterId, PortInput input) {
        Declaration declaration = declaration(adapterId);
        if (declaration.kind() != input.kind()) {
            return false;
        }
        return input.kind() == Task.Kind.IMAGE_GENERATION
                || input.durationSeconds() >= declaration.minimumSeconds()
                && input.durationSeconds() <= declaration.maximumSeconds();
    }

    public MediaAdapter require(String adapterId) {
        declaration(adapterId);
        MediaAdapter adapter = implementations.get(adapterId);
        if (adapter == null) {
            throw unsupported();
        }
        return adapter;
    }

    private static ApiProblemException unsupported() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                "PROVIDER_UNSUPPORTED_CAPABILITY", "媒体能力不可用",
                "当前应用未安装此媒体适配器", false);
    }
}

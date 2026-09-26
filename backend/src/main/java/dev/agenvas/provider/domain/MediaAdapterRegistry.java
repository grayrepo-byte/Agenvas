package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Only compiled adapter IDs can be published; runtime implementations register as Spring beans. */
@Component
public final class MediaAdapterRegistry {

    public record Declaration(MediaPlatform platform, Task.Kind kind, int minimumSeconds,
            int maximumSeconds, boolean originRequired) {}

    private static final Map<String, Declaration> DECLARATIONS = Map.of(
            "MOCK_IMAGE", new Declaration(MediaPlatform.MOCK, Task.Kind.IMAGE_GENERATION, 0, 0, false),
            "MOCK_VIDEO", new Declaration(MediaPlatform.MOCK, Task.Kind.VIDEO_GENERATION, 1, 30, false),
            "COMFY_IMAGE_V1", new Declaration(MediaPlatform.COMFYUI, Task.Kind.IMAGE_GENERATION, 0, 0, true),
            "COMFY_VIDEO_V1", new Declaration(MediaPlatform.COMFYUI, Task.Kind.VIDEO_GENERATION, 1, 5, true),
            "OPENAI_GPT_IMAGE_2", new Declaration(MediaPlatform.OPENAI, Task.Kind.IMAGE_GENERATION, 0, 0, false),
            "GOOGLE_NANO_BANANA_2", new Declaration(MediaPlatform.GOOGLE, Task.Kind.IMAGE_GENERATION, 0, 0, false),
            "ARK_SEEDANCE_2_I2V", new Declaration(MediaPlatform.ARK, Task.Kind.VIDEO_GENERATION, 4, 15, false));

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

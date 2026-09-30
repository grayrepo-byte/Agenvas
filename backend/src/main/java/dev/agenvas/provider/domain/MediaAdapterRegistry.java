package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.artifact.domain.ImageGenerationParameters;
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

    public static final String SEED_AUDIO_1 = "VOLC_SEED_AUDIO_1";
    public static final String SEEDANCE_2 = "ARK_SEEDANCE_2_I2V";
    public static final int SEEDANCE_MAX_AUDIO_BYTES = 15 * 1024 * 1024;
    public static final int SEEDANCE_MIN_AUDIO_DURATION_MS = 2_000;
    public static final int SEEDANCE_MAX_AUDIO_DURATION_MS = 15_000;
    public static final String LOCAL_IMAGE_PROCESSOR = "LOCAL_IMAGE_PROCESSOR";
    public static final String OPENAI_GPT_IMAGE_2 = "OPENAI_GPT_IMAGE_2";
    public static final String GOOGLE_NANO_BANANA_2 = "GOOGLE_NANO_BANANA_2";

    /** Product bounds verified against each fixed third-party request protocol. */
    public static final int OPENAI_MAX_REFERENCE_IMAGES = 4;
    public static final int GOOGLE_MAX_REFERENCE_IMAGES = 14;

    public record Declaration(MediaPlatform platform, Task.Kind kind, int minimumSeconds,
            int maximumSeconds, boolean originRequired, int maxReferenceImages,
            Set<String> supportedVideoInputModes, String defaultVideoInputMode,
            boolean supportsEndFrame, Set<String> supportedImageAspectRatios,
            Set<String> supportedImageResolutions, Set<String> supportedImageQualities,
            boolean supportsTransparentBackground, boolean supportsImageMask, int maxReferenceAudios) {}

    private static final Map<String, Declaration> DECLARATIONS = Map.of(
            LOCAL_IMAGE_PROCESSOR, image(MediaPlatform.LOCAL, false, 1,
                    ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS,
                    ImageGenerationParameters.QUALITIES, true, false),
            "MOCK_IMAGE", image(MediaPlatform.MOCK, false, 4,
                    ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS,
                    ImageGenerationParameters.QUALITIES, true, false),
            "MOCK_AUDIO", audio(MediaPlatform.MOCK),
            "VOLC_SEED_AUDIO_1", audio(MediaPlatform.VOLCENGINE),
            "MOCK_VIDEO", video(MediaPlatform.MOCK, 1, 30, false, 4,
                    Set.of("TEXT", "START_END", "GENERAL_REFERENCE"), "TEXT", true),
            "COMFY_IMAGE_V1", image(MediaPlatform.COMFYUI, true, 1,
                    Set.of("AUTO", "1:1", "9:16", "16:9"), Set.of("1K"), Set.of(), false,
                    false),
            "COMFY_VIDEO_V1", video(MediaPlatform.COMFYUI, 1, 5, true, 1,
                    Set.of("START_END"), "START_END", false),
            OPENAI_GPT_IMAGE_2, image(MediaPlatform.OPENAI, false,
                    OPENAI_MAX_REFERENCE_IMAGES, ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS,
                    ImageGenerationParameters.QUALITIES, true, true),
            GOOGLE_NANO_BANANA_2, image(MediaPlatform.GOOGLE, false,
                    GOOGLE_MAX_REFERENCE_IMAGES, ImageGenerationParameters.ASPECT_RATIOS,
                    ImageGenerationParameters.RESOLUTIONS, Set.of(), false, false),
            "ARK_SEEDANCE_2_I2V", video(MediaPlatform.ARK, 4, 15, false, 9,
                    Set.of("TEXT", "START_END", "GENERAL_REFERENCE"), "START_END", true));

    private static Declaration image(MediaPlatform platform, boolean originRequired,
            int maxReferenceImages, Set<String> aspectRatios, Set<String> resolutions,
            Set<String> qualities, boolean transparentBackground, boolean imageMask) {
        return new Declaration(platform, Task.Kind.IMAGE_GENERATION, 0, 0, originRequired,
                maxReferenceImages, Set.of(), null, false, aspectRatios, resolutions,
                qualities, transparentBackground, imageMask, 0);
    }

    private static Declaration video(MediaPlatform platform, int minimumSeconds,
            int maximumSeconds, boolean originRequired, int maxReferenceImages,
            Set<String> inputModes, String defaultInputMode, boolean supportsEndFrame) {
        return new Declaration(platform, Task.Kind.VIDEO_GENERATION, minimumSeconds,
                maximumSeconds, originRequired, maxReferenceImages, inputModes, defaultInputMode,
                supportsEndFrame, Set.of(), Set.of(), Set.of(), false, false,
                platform == MediaPlatform.MOCK || platform == MediaPlatform.ARK ? 3 : 0);
    }

    private static Declaration audio(MediaPlatform platform) {
        return new Declaration(platform, Task.Kind.AUDIO_GENERATION, 0, 0, false, 1,
                Set.of(), null, false, Set.of(), Set.of(), Set.of(), false, false, 3);
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

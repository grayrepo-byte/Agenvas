package dev.agenvas.task.application;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.List;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared standard image controls and static reference boundary, independent of draft or canvas identity. */
public final class ImageGenerationPreparation {
    private ImageGenerationPreparation() {
    }

    public static ImageGenerationParameters parameters(JsonNode values, MediaAdapterRegistry.Declaration policy) {
        ImageGenerationParameters result = ImageGenerationParameters.parse(values);
        result.requireSupported(policy.supportedImageAspectRatios(), policy.supportedImageResolutions(),
                policy.supportedImageQualities(), policy.supportsTransparentBackground());
        return result;
    }

    /** The tool owns the ratio, transparency and single result. Native edits can override the bound output defaults. */
    public static ImageGenerationParameters operationParameters(ObjectMapper mapper, String adapterId,
            MediaAdapterRegistry.Declaration policy, JsonNode settings, JsonNode requested,
            String aspectRatio, boolean transparent) {
        var fixed = new ImageGenerationParameters(aspectRatio, ImageGenerationParameters.DEFAULT_RESOLUTION,
                "high", transparent, 1);
        if (!MediaAdapterRegistry.OPENAI_GPT_IMAGE_2.equals(adapterId)
                && !MediaAdapterRegistry.GOOGLE_NANO_BANANA_2.equals(adapterId)) {
            if (requested.has("resolution") || requested.has("quality"))
                throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        ApiMessage.of("api.image-generation-parameters.invalid-image-generation-parameters"),
                        ApiMessage.of("api.media-function.invalid-request"), false);
            return fixed;
        }
        if (requested.has("quality") && policy.supportedImageQualities().isEmpty())
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.image-generation-parameters.invalid-image-generation-parameters"),
                    ApiMessage.of(
                            "api.image-generation-parameters.the-selected-image-capability-does-not-support-quality",
                            requested.path("quality").asText()),
                    false);
        var controls = fixed.toJson(mapper);
        if (settings.has("quality"))
            controls.set("quality", settings.get("quality"));
        var defaults = settings.path("defaultParameters");
        for (String field : List.of("resolution", "quality")) {
            if (requested.has(field))
                controls.set(field, requested.get(field));
            else if (defaults.has(field))
                controls.set(field, defaults.get(field));
        }
        return parameters(controls, policy);
    }

    public static void requireStaticImage(Asset asset) {
        if ("image/gif".equals(asset.contentType())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.image-generation-parameters.invalid-image-generation-parameters"),
                    ApiMessage.of("api.asset.gif-static-operation"), false);
        }
    }
}

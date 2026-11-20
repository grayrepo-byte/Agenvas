package dev.agenvas.task.application;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

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

    public static void requireStaticImage(Asset asset) {
        if ("image/gif".equals(asset.contentType())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.image-generation-parameters.invalid-image-generation-parameters"),
                    ApiMessage.of("api.asset.gif-static-operation"), false);
        }
    }
}

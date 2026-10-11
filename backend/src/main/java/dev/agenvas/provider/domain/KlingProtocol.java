package dev.agenvas.provider.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Compiled official Kling 3 bounds; administrator settings may only narrow them. */
public final class KlingProtocol {
    public static final String IMAGE_ADAPTER_ID = "KLING_IMAGE_3";
    public static final String VIDEO_ADAPTER_ID = "KLING_VIDEO_3";
    public static final String IMAGE_MODEL_ID = "kling-v3";
    public static final String VIDEO_MODEL_ID = "kling-3.0";
    public static final String DEFAULT_RESOLUTION = "1080p";
    public static final Set<String> RESOLUTIONS = Set.of("720p", DEFAULT_RESOLUTION, "2160p");
    public static final Set<String> IMAGE_RESOLUTIONS = Set.of("1K", "2K");
    public static final Set<String> IMAGE_ASPECT_RATIOS = Set.of("1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9");
    public static final Set<String> VIDEO_ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1");
    // Decimal MB avoids exceeding the provider's documented limits.
    public static final int MAX_IMAGE_BYTES = 10_000_000;
    public static final int MAX_VIDEO_IMAGE_BYTES = 50_000_000;
    public static final int MAX_IMAGE_PROMPT_LENGTH = 2_500;
    public static final int MAX_VIDEO_PROMPT_LENGTH = 3_072;
    public static final int MINIMUM_SECONDS = 3;
    public static final int MAXIMUM_SECONDS = 15;

    private KlingProtocol() {}

    public static String resolution(String requested) {
        if (requested == null) return DEFAULT_RESOLUTION;
        if (!RESOLUTIONS.contains(requested)) throw invalid();
        return requested;
    }

    public static String providerResolution(String requested) {
        String resolved = resolution(requested);
        return "2160p".equals(resolved) ? "4k" : resolved;
    }

    public static void requireImagePrompt(String prompt) {
        validatePrompt(prompt, false);
    }

    public static void requireVideoPrompt(String prompt) { validatePrompt(prompt, true); }

    public static void validatePrompt(String prompt, boolean video) {
        if (prompt == null || prompt.isBlank()
                || prompt.codePointCount(0, prompt.length()) > (video ? MAX_VIDEO_PROMPT_LENGTH : MAX_IMAGE_PROMPT_LENGTH))
            throw invalid();
    }

    public static void validateMetadata(Asset asset) { validateMetadata(asset, false); }

    /** Only still image references are accepted by either Kling generation endpoint. */
    public static void validateMetadata(Asset asset, boolean videoGeneration) {
        if (asset.mediaKind() != Asset.MediaKind.IMAGE
                || !Set.of("image/jpeg", "image/png").contains(asset.contentType())
                || asset.byteSize() < 1
                || asset.byteSize() > (videoGeneration ? MAX_VIDEO_IMAGE_BYTES : MAX_IMAGE_BYTES)
                || asset.width() == null || asset.height() == null)
            throw invalid();
        requireDimensions(asset.width(), asset.height());
    }

    public static void requireDimensions(int width, int height) {
        double ratio = (double) width / height;
        if (width < 300 || height < 300 || ratio < 0.4 || ratio > 2.5) throw invalid();
    }

    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.media-capability-service.media-capability-does-not-support-this-input"),
                ApiMessage.of("api.kling.input-requirements"), false);
    }
}

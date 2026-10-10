package dev.agenvas.provider.domain;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Fixed H3 bounds; generated duration and reference durations are independent. */
public final class MiniMaxH3Protocol {
    public static final String ADAPTER_ID = "MINIMAX_H3";
    public static final String MODEL_ID = "MiniMax-H3";
    public static final String DEFAULT_RESOLUTION = "768p";
    public static final Set<String> RESOLUTIONS = Set.of(DEFAULT_RESOLUTION, "1440p");
    // Official limits are documented in MB; decimal bytes keep inputs within either interpretation.
    public static final int MAX_REQUEST_BYTES = 64_000_000;
    public static final long MAX_IMAGE_BYTES = 30_000_000;
    public static final long MAX_VIDEO_BYTES = 50_000_000;
    public static final long MAX_AUDIO_BYTES = 15_000_000;
    public static final int MAX_REFERENCE_DURATION_MS = 15_000;
    private MiniMaxH3Protocol() {}

    public static String resolution(String requested) {
        if (requested == null) return DEFAULT_RESOLUTION;
        if (!RESOLUTIONS.contains(requested)) throw invalid();
        return requested;
    }

    public static void validateMetadata(Asset asset) {
        switch (asset.mediaKind()) {
            case IMAGE -> {
                if (!Set.of("image/jpeg", "image/png", "image/webp").contains(asset.contentType())
                        || asset.byteSize() > MAX_IMAGE_BYTES) throw invalid();
                dimensions(asset);
            }
            case VIDEO -> {
                if (!"video/mp4".equals(asset.contentType()) || asset.byteSize() > MAX_VIDEO_BYTES) throw invalid();
                dimensions(asset);
                duration(asset);
            }
            case AUDIO -> {
                if (!Set.of("audio/mpeg", "audio/wav").contains(asset.contentType())
                        || asset.byteSize() > MAX_AUDIO_BYTES) throw invalid();
                duration(asset);
            }
        }
    }

    private static void duration(Asset asset) {
        if (asset.durationMs() == null || asset.durationMs() < 2_000
                || asset.durationMs() > MAX_REFERENCE_DURATION_MS) throw invalid();
    }

    private static void dimensions(Asset asset) {
        if (asset.width() == null || asset.height() == null || asset.width() < 256 || asset.width() > 5760
                || asset.height() < 256 || asset.height() > 5760
                || (double) asset.width() / asset.height() < 0.4
                || (double) asset.width() / asset.height() > 2.5) throw invalid();
    }

    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "PROVIDER_UNSUPPORTED_INPUT",
                ApiMessage.of("api.media-capability-service.media-capability-does-not-support-this-input"),
                ApiMessage.of("api.minimax-h3.input-requirements"), false);
    }
}

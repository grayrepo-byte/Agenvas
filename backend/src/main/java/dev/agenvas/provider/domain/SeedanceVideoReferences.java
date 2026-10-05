package dev.agenvas.provider.domain;

import static dev.agenvas.provider.domain.MediaAdapterRegistry.*;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.springframework.http.HttpStatus;

/** Official Seedance 2.0 reference limits, separate from generated output duration. */
public final class SeedanceVideoReferences {
    private SeedanceVideoReferences() {}
    public static void validateMetadata(Asset asset) {
        if (asset.mediaKind() != Asset.MediaKind.VIDEO || !"video/mp4".equals(asset.contentType())
                || asset.byteSize() > SEEDANCE_MAX_VIDEO_BYTES || asset.durationMs() == null
                || asset.durationMs() < SEEDANCE_MIN_VIDEO_DURATION_MS || asset.durationMs() > SEEDANCE_MAX_VIDEO_DURATION_MS
                || asset.width() == null || asset.height() == null) throw invalid();
        int width = asset.width(), height = asset.height();
        long pixels = (long) width * height;
        double ratio = (double) width / height;
        if (width < SEEDANCE_MIN_VIDEO_SIDE || width > SEEDANCE_MAX_VIDEO_SIDE
                || height < SEEDANCE_MIN_VIDEO_SIDE || height > SEEDANCE_MAX_VIDEO_SIDE
                || pixels < SEEDANCE_MIN_VIDEO_PIXELS || pixels > SEEDANCE_MAX_VIDEO_PIXELS
                || ratio < SEEDANCE_MIN_VIDEO_RATIO || ratio > SEEDANCE_MAX_VIDEO_RATIO) throw invalid();
    }
    public static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "SEEDANCE_VIDEO_REFERENCE_INVALID",
                ApiMessage.of("api.media-relay.invalid-video"), ApiMessage.of("api.media-relay.video-requirements"), false);
    }
}

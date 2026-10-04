package dev.agenvas.task.application;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.springframework.http.HttpStatus;

/** Shared acceptance limits keep local frame inference and scratch storage bounded. */
public final class LocalVideoOperationBounds {
    public static final int MAX_DURATION_MS = 30_000;
    public static final long MAX_BYTES = 200L * 1024 * 1024;
    public static final long MAX_INPUT_PIXELS = 8_294_400;
    public static final int DEPTH_FPS = 12;
    public static final int DEPTH_EDGE = 518;
    private LocalVideoOperationBounds() {}

    public static void require(Asset asset) {
        if (asset.mediaKind() != Asset.MediaKind.VIDEO || asset.durationMs() == null
                || asset.durationMs() < 1 || asset.durationMs() > MAX_DURATION_MS
                || asset.width() == null || asset.height() == null || asset.width() < 1 || asset.height() < 1
                || (long) asset.width() * asset.height() > MAX_INPUT_PIXELS || asset.byteSize() > MAX_BYTES) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "LOCAL_VIDEO_LIMIT_EXCEEDED",
                    ApiMessage.of("api.media-function.title"), ApiMessage.of("api.media-function.local-limits"), false);
        }
    }
}

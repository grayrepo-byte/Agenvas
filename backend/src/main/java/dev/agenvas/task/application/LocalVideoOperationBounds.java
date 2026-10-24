package dev.agenvas.task.application;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.domain.AudioExtractionParameters;
import dev.agenvas.task.domain.VideoOperation;
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
    public static final int AUDIO_MAX_DURATION_MS = 600_000;
    public static final long AUDIO_MAX_BYTES = 500L * 1024 * 1024;
    public static final long AUDIO_MAX_INPUT_PIXELS = 40_000_000;
    public static final long MAX_AUDIO_OUTPUT_BYTES = 50L * 1024 * 1024;
    private LocalVideoOperationBounds() {}

    public static void require(Asset asset, VideoOperation operation) {
        boolean audio = operation == VideoOperation.EXTRACT_AUDIO;
        int durationLimit = audio ? AUDIO_MAX_DURATION_MS : MAX_DURATION_MS;
        long byteLimit = audio ? AUDIO_MAX_BYTES : MAX_BYTES;
        long pixelLimit = audio ? AUDIO_MAX_INPUT_PIXELS : MAX_INPUT_PIXELS;
        if (asset.mediaKind() != Asset.MediaKind.VIDEO || asset.durationMs() == null
                || asset.durationMs() < 1 || asset.durationMs() > durationLimit
                || asset.width() == null || asset.height() == null || asset.width() < 1 || asset.height() < 1
                || (long) asset.width() * asset.height() > pixelLimit || asset.byteSize() < 1 || asset.byteSize() > byteLimit) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "LOCAL_VIDEO_LIMIT_EXCEEDED",
                    ApiMessage.of("api.media-function.title"), audio
                            ? ApiMessage.of("api.media-function.local-audio-limits") : ApiMessage.of("api.media-function.local-limits"), false);
        }
    }

    /** Reject oversized PCM before output nodes or a processing Task can be created. */
    public static void requireAudioOutput(Asset asset, AudioExtractionParameters parameters) {
        if (parameters.maximumOutputBytes(asset.durationMs()) > MAX_AUDIO_OUTPUT_BYTES) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "LOCAL_VIDEO_AUDIO_OUTPUT_TOO_LARGE",
                    ApiMessage.of("api.media-function.title"), ApiMessage.of("api.media-function.local-audio-output-too-large"), false);
        }
    }
}

package dev.agenvas.asset.domain;

/** Byte limits shared by media validation and upload discovery; decoding adds its own pixel/duration limits. */
public final class MediaUploadLimits {
    public static final long IMAGE_BYTES = 20L * 1024 * 1024;
    public static final long AUDIO_BYTES = 50L * 1024 * 1024;
    public static final long VIDEO_BYTES = 500L * 1024 * 1024;

    private MediaUploadLimits() {
    }
}

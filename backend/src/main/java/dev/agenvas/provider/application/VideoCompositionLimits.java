package dev.agenvas.provider.application;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment resource budgets, shared by acceptance, preview and export. Protocol ceilings are
 * deliberately finite; source/output duration and output bytes also respect asset archive limits. */
@ConfigurationProperties(prefix = "agenvas.video-composition.limits")
public record VideoCompositionLimits(Integer maxDurationMs, Integer maxShots, Integer maxLines,
        Integer maxSubtitles, Long maxSourceBytes, Long maxScratchBytes, Long maxOutputBytes,
        Long minFreeBytes, Duration timeout, Duration previewTimeout, Path scratchRoot, String maxResolution) {
    public static final int SOURCE_DURATION_MS = 600_000;
    public static final int DURATION_CEILING_MS = 600_000;
    public static final int SHOT_CEILING = 120;
    public static final int LINE_CEILING = 600;
    public static final int SUBTITLE_CEILING = 5000;
    public static final long GIB = 1024L * 1024 * 1024;
    public static final long OUTPUT_CEILING_BYTES = 500L * 1024 * 1024;
    public static final Duration TIMEOUT_CEILING = Duration.ofHours(1);

    public VideoCompositionLimits {
        maxDurationMs = (int) bound(maxDurationMs, 600_000, 100, DURATION_CEILING_MS);
        maxShots = (int) bound(maxShots, 60, 1, SHOT_CEILING);
        maxLines = (int) bound(maxLines, 300, 0, LINE_CEILING);
        maxSubtitles = (int) bound(maxSubtitles, 2000, 0, SUBTITLE_CEILING);
        maxSourceBytes = bound(maxSourceBytes, 2 * GIB, 1, 8 * GIB);
        maxScratchBytes = bound(maxScratchBytes, 2 * GIB, 1024 * 1024, 8 * GIB);
        maxOutputBytes = bound(maxOutputBytes, OUTPUT_CEILING_BYTES, 1024, OUTPUT_CEILING_BYTES);
        minFreeBytes = bound(minFreeBytes, 64L * 1024 * 1024, 0, 8 * GIB);
        timeout = duration(timeout, Duration.ofMinutes(15), TIMEOUT_CEILING);
        previewTimeout = duration(previewTimeout, Duration.ofSeconds(30), Duration.ofMinutes(2));
        scratchRoot = scratchRoot == null ? Path.of(System.getProperty("java.io.tmpdir")) : scratchRoot;
        if (!scratchRoot.isAbsolute())
            throw new IllegalArgumentException("Composition scratch root must be absolute");
        maxResolution = maxResolution == null ? "2160P" : maxResolution;
        if (!List.of("720P", "1080P", "2160P").contains(maxResolution))
            throw new IllegalArgumentException("Invalid composition maximum resolution");
    }
    public static VideoCompositionLimits defaults() {
        return new VideoCompositionLimits(null, null, null, null, null, null, null, null, null, null, null, null);
    }
    public int maxFrames() {
        return (int) ((long) maxDurationMs * VideoCompositionPlanner.FPS / 1000);
    }
    public List<String> resolutions() {
        var values = List.of("720P", "1080P", "2160P");
        return values.subList(0, values.indexOf(maxResolution) + 1);
    }
    private static long bound(Number value, long fallback, long min, long max) {
        long result = value == null ? fallback : value.longValue();
        if (result < min || result > max)
            throw new IllegalArgumentException("Composition budget outside supported range");
        return result;
    }
    private static Duration duration(Duration value, Duration fallback, Duration max) {
        Duration result = value == null ? fallback : value;
        if (result.compareTo(Duration.ofMillis(1)) < 0 || result.compareTo(max) > 0)
            throw new IllegalArgumentException("Composition timeout outside supported range");
        return result;
    }
}

package dev.agenvas.subtitle.domain;

import java.util.List;
import java.util.UUID;

/** A correctable transcript draft. The source identity never follows a node's selected version. */
public record SubtitleTrack(UUID sourceVersionId, int sourceDurationMs, String language, List<SubtitleCue> cues) {
    public static final int MAX_CUES = 5000;

    public SubtitleTrack {
        if (sourceVersionId == null || sourceDurationMs < 1)
            throw new IllegalArgumentException("SUBTITLE_INVALID_SOURCE");
        if (language == null || !language.matches("auto|[a-z]{2,3}"))
            throw new IllegalArgumentException("SUBTITLE_INVALID_LANGUAGE");
        if (cues == null || cues.size() > MAX_CUES || cues.stream().anyMatch(cue -> cue == null
                || cue.endMs() > sourceDurationMs))
            throw new IllegalArgumentException("SUBTITLE_INVALID_CUES");
        cues = List.copyOf(cues);
    }

    /** Corrections retain the exact media identity and are validated before they can be saved. */
    public SubtitleTrack withCues(List<SubtitleCue> corrections) {
        return new SubtitleTrack(sourceVersionId, sourceDurationMs, language, corrections);
    }
}

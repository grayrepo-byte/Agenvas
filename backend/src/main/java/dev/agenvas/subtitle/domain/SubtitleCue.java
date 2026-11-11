package dev.agenvas.subtitle.domain;

/** Plain text over a half-open interval, in milliseconds of the fixed source media version. */
public record SubtitleCue(String text, int startMs, int endMs) {
    public static final int MAX_TEXT_LENGTH = 500;

    public SubtitleCue {
        if (text == null) throw new IllegalArgumentException("SUBTITLE_INVALID_TEXT");
        text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        // Blank lines delimit cues in both exchange formats. Reject them instead of silently
        // changing a user's corrected text or accidentally creating another subtitle on export.
        if (text.isBlank() || text.length() > MAX_TEXT_LENGTH || text.matches("(?s).*\n[ \\t]*\n.*")
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw new IllegalArgumentException("SUBTITLE_INVALID_TEXT");
        if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("SUBTITLE_INVALID_TIMING");
    }
}

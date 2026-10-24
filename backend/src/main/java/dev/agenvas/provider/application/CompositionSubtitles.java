package dev.agenvas.provider.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Writes data-only ASS in the renderer's private working directory, never an FFmpeg expression. */
final class CompositionSubtitles {
    static final String FILE_NAME = "captions.ass";
    private CompositionSubtitles() {}

    static void write(JsonNode subtitles, int durationMs, int width, int height, String fontFamily, Path scratch) throws IOException {
        if (!subtitles.isArray() || subtitles.size() > 100) throw new IllegalArgumentException("Invalid subtitles");
        StringBuilder ass = new StringBuilder("[Script Info]\nScriptType: v4.00+\nScaledBorderAndShadow: yes\nWrapStyle: 0\n")
                .append("PlayResX: ").append(width).append("\nPlayResY: ").append(height)
                .append("\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                .append("Style: Default,").append(fontFamily).append(',').append(Math.max(24, height / 24))
                .append(",&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,1,2,30,30,")
                .append(Math.max(24, height / 18)).append(",1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (JsonNode subtitle : subtitles) {
            JsonNode text = subtitle.path("text"), start = subtitle.path("startMs"), end = subtitle.path("endMs");
            if (!text.isTextual() || text.asText().isBlank() || text.asText().length() > 500
                    || !start.isIntegralNumber() || !end.isIntegralNumber() || !start.canConvertToInt() || !end.canConvertToInt()
                    || start.intValue() < 0 || start.intValue() >= end.intValue() || end.intValue() > durationMs)
                throw new IllegalArgumentException("Invalid subtitle timing or text");
            ass.append("Dialogue: 0,").append(timestamp(start.intValue())).append(',').append(timestamp(end.intValue()))
                    .append(",Default,,0,0,0,,").append(escape(text.asText())).append('\n');
        }
        Files.writeString(scratch.resolve(FILE_NAME), ass, StandardCharsets.UTF_8);
    }

    /** ASS override delimiters and escapes use visible full-width literals; only actual newlines become \N. */
    static String escape(String text) {
        return text.replace("\\", "＼").replace("{", "｛").replace("}", "｝")
                .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\N")
                .replaceAll("[\\p{Cc}&&[^\\n]]", " ");
    }

    private static String timestamp(int millis) {
        return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", millis / 3_600_000,
                millis / 60_000 % 60, millis / 1000 % 60, millis / 10 % 100);
    }
}

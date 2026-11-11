package dev.agenvas.subtitle.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded SRT/WebVTT exchange. Cue payload is plain text, never HTML, ASS or a filter expression. */
public final class SubtitleFiles {
    public enum Format { SRT, VTT }
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final Pattern SRT_TIME = Pattern.compile("(\\d{2,}):([0-5]\\d):([0-5]\\d),(\\d{3})");
    private static final Pattern VTT_TIME = Pattern.compile("(?:(\\d{2,}):)?([0-5]\\d):([0-5]\\d)\\.(\\d{3})");
    private SubtitleFiles() {}

    /** Presentation settings and NOTE blocks are ignored; cue text and timing remain editable data. */
    public static SubtitleTrack decode(String contents, Format format, UUID sourceVersionId, int durationMs, String language) {
        if (contents == null || format == null || contents.length() > MAX_FILE_BYTES
                || contents.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
            throw invalid();
        String normalized = contents.replaceFirst("^\\ufeff", "").replace("\r\n", "\n").replace('\r', '\n').strip();
        String[] blocks = normalized.isEmpty() ? new String[0] : normalized.split("\\n[ \\t]*\\n+");
        int firstCue = 0;
        if (format == Format.VTT) {
            if (blocks.length == 0 || !blocks[0].lines().findFirst().orElse("").matches("WEBVTT(?:[ \\t].*)?"))
                throw invalid();
            // The header may contain metadata, but cannot swallow a missing blank line before a cue.
            if (blocks[0].contains("-->")) throw invalid();
            firstCue = 1;
        }
        var cues = new ArrayList<SubtitleCue>();
        for (int block = firstCue; block < blocks.length; block++) {
            String[] lines = blocks[block].split("\n", -1);
            if (format == Format.VTT && lines[0].matches("NOTE(?:[ \\t].*)?")) continue;
            int timingIndex = lines[0].contains("-->") ? 0 : 1;
            if (lines.length <= timingIndex + 1 || (timingIndex == 1 && format == Format.SRT
                    && !lines[0].strip().matches("\\d+"))) throw invalid();
            String[] timing = lines[timingIndex].strip().split("[ \\t]+-->[ \\t]+", -1);
            if (timing.length != 2) throw invalid();
            String end = timing[1];
            if (format == Format.VTT) end = end.split("[ \\t]+", 2)[0];
            int startMs = timestamp(timing[0], format), endMs = timestamp(end, format);
            String payload = String.join("\n", java.util.Arrays.copyOfRange(lines, timingIndex + 1, lines.length));
            cues.add(new SubtitleCue(unescape(payload), startMs, endMs));
            if (cues.size() > SubtitleTrack.MAX_CUES) throw invalid();
        }
        return new SubtitleTrack(sourceVersionId, durationMs, language, cues);
    }

    /** Stable chronological export; overlapping cues are allowed and no corrected text is dropped. */
    public static String encode(SubtitleTrack track, Format format) {
        if (track == null || format == null) throw invalid();
        StringBuilder output = new StringBuilder(format == Format.VTT ? "WEBVTT\n\n" : "");
        int index = 1;
        for (var cue : track.cues().stream().sorted(Comparator.comparingInt(SubtitleCue::startMs)
                .thenComparingInt(SubtitleCue::endMs)).toList()) {
            if (format == Format.SRT) output.append(index++).append('\n');
            output.append(timestamp(cue.startMs(), format)).append(" --> ").append(timestamp(cue.endMs(), format))
                    .append('\n').append(escape(cue.text())).append("\n\n");
            if (output.length() > MAX_FILE_BYTES) throw invalid();
        }
        String contents = output.toString();
        if (contents.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) throw invalid();
        return contents;
    }

    private static int timestamp(String text, Format format) {
        var match = (format == Format.SRT ? SRT_TIME : VTT_TIME).matcher(text);
        if (!match.matches()) throw invalid();
        try {
            long hours = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
            long millis = Math.addExact(Math.multiplyExact(hours, 3_600_000), Long.parseLong(match.group(2)) * 60_000
                    + Long.parseLong(match.group(3)) * 1000 + Long.parseLong(match.group(4)));
            if (millis > Integer.MAX_VALUE) throw invalid();
            return (int) millis;
        } catch (ArithmeticException | NumberFormatException invalid) { throw invalid(); }
    }

    private static String timestamp(int millis, Format format) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", millis / 3_600_000,
                millis / 60_000 % 60, millis / 1000 % 60, format == Format.SRT ? ',' : '.', millis % 1000);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", "\u00a0")
                .replace("&lrm;", "\u200e").replace("&rlm;", "\u200f").replace("&amp;", "&");
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("SUBTITLE_INVALID_FILE"); }
}

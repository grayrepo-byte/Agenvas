package dev.agenvas.provider.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Deterministic timing after the Agent has assigned exact video/audio versions to shots. */
final class VideoCompositionPlanner {
    static final int FPS = 30;
    static final int MAX_DURATION_MS = 60_000;
    static final double MAX_SPEED = 1.15;
    private static final int GAP_MS = 100;
    private static final int TAIL_MS = 40;
    // Short pauses may divide one spoken turn; never bridge a long silence to claim alignment.
    private static final int MAX_ACTIVITY_GAP_MS = 400;
    record Line(UUID audioVersionId, Integer startMs, AudioActivityAnalyzer.Analysis audio) {}
    record Shot(UUID videoVersionId, int sourceStartMs, int durationMs, double speed,
            AudioActivityAnalyzer.Analysis sourceActivity, List<Line> lines) {}
    record PlacedLine(UUID audioVersionId, int startMs, int endMs, int trimStartMs, int trimEndMs,
            double speed, String alignmentEvidence, int limitMs) {}
    record PlacedShot(UUID videoVersionId, int sourceStartMs, int sourceDurationMs, double speed, int durationMs, int timelineStartMs,
            int frames, List<AudioActivityAnalyzer.Window> sourceActivityWindows, List<PlacedLine> lines) {}
    record Plan(int durationMs, int frames, List<PlacedShot> shots, List<String> warnings) {}
    static final class DoesNotFit extends IllegalArgumentException {
        DoesNotFit() { super("VIDEO_COMPOSITION_AUDIO_OVERFLOW"); }
    }

    Plan plan(List<Shot> shots) {
        if (shots.isEmpty() || shots.size() > 12 || shots.stream().mapToInt(s -> s.lines().size()).sum() > 30)
            throw new IllegalArgumentException("Invalid composition bounds");
        List<PlacedShot> output = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int totalFrames = 0;
        for (int shotIndex = 0; shotIndex < shots.size(); shotIndex++) {
            Shot shot = shots.get(shotIndex);
            if (!Double.isFinite(shot.speed()) || shot.speed() < 0.5 || shot.speed() > 2 || shot.durationMs() < 1)
                throw new IllegalArgumentException("Invalid shot speed or source duration");
            int frames = (int) Math.floor(shot.durationMs() * FPS / (1000 * shot.speed()));
            int duration = frames * 1000 / FPS;
            if (frames < 1 || totalFrames + frames > MAX_DURATION_MS * FPS / 1000 || shot.sourceStartMs() < 0)
                throw new IllegalArgumentException("Invalid composition duration");
            if (Math.abs(duration - shot.durationMs() / shot.speed()) > 0.001)
                warnings.add("SHOT_" + (shotIndex + 1) + "_ROUNDED_TO_VIDEO_FRAME");
            var activity = scaledActivity(shot.sourceActivity(), shot.speed(), duration);
            boolean waveform = activity != null && activity.reliableWindows();
            if (!shot.lines().isEmpty() && !waveform)
                warnings.add("SHOT_" + (shotIndex + 1) + "_NO_RELIABLE_SOUND_WINDOWS_DURATION_FALLBACK");
            List<PlacedLine> lines = new ArrayList<>();
            int cursor = 0, windowIndex = 0;
            for (int index = 0; index < shot.lines().size(); index++) {
                Line line = shot.lines().get(index);
                var audio = line.audio();
                if (audio == null || audio.windows().isEmpty() || audio.trimEndMs() <= audio.trimStartMs())
                    throw new IllegalArgumentException("VIDEO_COMPOSITION_AUDIO_SILENT");
                int bound = duration - TAIL_MS;
                int reserve = 0;
                for (int next = index + 1; next < shot.lines().size(); next++) {
                    Line future = shot.lines().get(next);
                    if (future.startMs() != null) { bound = future.startMs() - GAP_MS; break; }
                    reserve += minimumDuration(future) + GAP_MS;
                }
                int latestEnd = bound - reserve;
                int start = line.startMs() == null ? cursor : line.startMs();
                String evidence = line.startMs() == null ? "DURATION_FALLBACK" : "EXPLICIT_SCRIPT_ANCHOR";
                if (line.startMs() == null && waveform) {
                    while (windowIndex < activity.windows().size()
                            && activity.windows().get(windowIndex).startMs() < cursor) windowIndex++;
                    boolean hasCandidate = false;
                    boolean matched = false;
                    for (int candidateIndex = windowIndex; candidateIndex < activity.windows().size(); candidateIndex++) {
                        int candidate = activity.windows().get(candidateIndex).startMs();
                        if (candidate >= latestEnd) break;
                        hasCandidate = true;
                        int finalWindow = candidateIndex;
                        int windowEnd = activity.windows().get(finalWindow).endMs();
                        while (candidate + minimumDuration(line) > Math.min(windowEnd - TAIL_MS, latestEnd)
                                && finalWindow + 1 < activity.windows().size()
                                && activity.windows().get(finalWindow + 1).startMs() - windowEnd <= MAX_ACTIVITY_GAP_MS) {
                            windowEnd = activity.windows().get(++finalWindow).endMs();
                        }
                        int candidateBound = Math.min(windowEnd - TAIL_MS, latestEnd);
                        if (candidate + minimumDuration(line) <= candidateBound) {
                            start = candidate;
                            latestEnd = candidateBound;
                            evidence = "SOURCE_SOUND_ACTIVITY";
                            windowIndex = finalWindow + 1;
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) warnings.add("SHOT_" + (shotIndex + 1) + "_LINE_" + (index + 1)
                            + (hasCandidate ? "_ACTIVITY_WINDOW_TOO_SHORT_DURATION_FALLBACK"
                                    : "_NO_REMAINING_SOUND_WINDOW_DURATION_FALLBACK"));
                }
                if (start < cursor || start < 0 || latestEnd <= start) throw new DoesNotFit();
                int trimmed = audio.trimEndMs() - audio.trimStartMs();
                int available = latestEnd - start;
                // Round upward so serialized speed never promises more time than the available interval.
                double speed = Math.max(1, Math.ceil(trimmed * 1_000_000.0 / available) / 1_000_000.0);
                if (speed > MAX_SPEED) throw new DoesNotFit();
                int end = start + (int) Math.ceil(trimmed / speed);
                if (end > latestEnd) throw new DoesNotFit();
                if (line.startMs() != null && waveform) {
                    int anchoredStart = start, anchoredEnd = end;
                    boolean withinActivity = activity.windows().stream()
                            .anyMatch(window -> anchoredStart >= window.startMs() && anchoredEnd <= window.endMs());
                    if (!withinActivity) warnings.add("SHOT_" + (shotIndex + 1) + "_LINE_" + (index + 1)
                            + "_EXPLICIT_ANCHOR_OUTSIDE_SOUND_WINDOW");
                }
                // Reserve a small atempo rounding margin, then remeasure the full rendered line.
                lines.add(new PlacedLine(line.audioVersionId(), start, end, audio.trimStartMs(), audio.trimEndMs(), speed, evidence,
                        Math.min(duration, latestEnd + TAIL_MS)));
                cursor = end + GAP_MS;
            }
            output.add(new PlacedShot(shot.videoVersionId(), shot.sourceStartMs(), shot.durationMs(), shot.speed(), duration, totalFrames * 1000 / FPS,
                    frames, activity == null ? List.of() : activity.windows(), List.copyOf(lines)));
            totalFrames += frames;
        }
        return new Plan(totalFrames * 1000 / FPS, totalFrames, List.copyOf(output), List.copyOf(warnings));
    }

    private AudioActivityAnalyzer.Analysis scaledActivity(AudioActivityAnalyzer.Analysis activity, double speed, int duration) {
        if (activity == null) return null;
        List<AudioActivityAnalyzer.Window> windows = activity.windows().stream()
                .map(window -> new AudioActivityAnalyzer.Window(Math.min(duration, (int) Math.floor(window.startMs() / speed)),
                        Math.min(duration, (int) Math.ceil(window.endMs() / speed))))
                .filter(window -> window.endMs() > window.startMs()).toList();
        return new AudioActivityAnalyzer.Analysis(duration, (int) Math.floor(activity.trimStartMs() / speed),
                Math.min(duration, (int) Math.ceil(activity.trimEndMs() / speed)), windows, activity.reliableWindows());
    }

    private int minimumDuration(Line line) {
        if (line.audio() == null) throw new IllegalArgumentException("Missing analyzed audio");
        return (int) Math.ceil((line.audio().trimEndMs() - line.audio().trimStartMs()) / MAX_SPEED);
    }
}

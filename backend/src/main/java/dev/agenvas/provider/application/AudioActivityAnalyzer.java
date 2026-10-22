package dev.agenvas.provider.application;

import java.util.ArrayList;
import java.util.List;

/** Bounded PCM energy analysis. Activity is evidence of sound, never a transcript or speaker identity. */
final class AudioActivityAnalyzer {
    static final int SAMPLE_RATE = 8_000;
    private static final int FRAME_SAMPLES = SAMPLE_RATE / 50;
    private static final int FRAME_MS = 20;
    record Window(int startMs, int endMs) {}
    record Analysis(int durationMs, int trimStartMs, int trimEndMs, List<Window> windows,
            boolean reliableWindows) {}

    Analysis analyze(byte[] pcm, boolean sourceVideo) {
        if (pcm.length < 2 || pcm.length % 2 != 0 || pcm.length > 2L * SAMPLE_RATE * 600)
            throw new IllegalArgumentException("Invalid bounded audio analysis input");
        int samples = pcm.length / 2;
        int duration = (int) Math.ceil(samples * 1000.0 / SAMPLE_RATE);
        double[] levels = new double[(samples + FRAME_SAMPLES - 1) / FRAME_SAMPLES];
        double peak = -120;
        for (int frame = 0; frame < levels.length; frame++) {
            int start = frame * FRAME_SAMPLES, end = Math.min(samples, start + FRAME_SAMPLES);
            double sum = 0;
            for (int index = start; index < end; index++) {
                short value = (short) ((pcm[index * 2] & 255) | pcm[index * 2 + 1] << 8);
                double normalized = value / 32768.0;
                sum += normalized * normalized;
            }
            levels[frame] = sum == 0 ? -120 : 10 * Math.log10(sum / (end - start));
            peak = Math.max(peak, levels[frame]);
        }
        double threshold = Math.max(sourceVideo ? -42 : -50, peak - (sourceVideo ? 24 : 36));
        List<Window> raw = new ArrayList<>();
        int start = -1, last = -1;
        for (int frame = 0; frame < levels.length; frame++) {
            if (levels[frame] >= threshold) {
                if (start < 0) start = frame;
                last = frame;
            } else if (start >= 0 && frame - last > 8) {
                append(raw, start, last, duration);
                start = -1;
            }
        }
        if (start >= 0) append(raw, start, last, duration);
        if (raw.isEmpty()) return new Analysis(duration, 0, duration, List.of(), false);
        // Only exterior silence is removed. Quiet pauses inside a line remain intact.
        int trimStart = Math.max(0, raw.getFirst().startMs() - 80);
        int trimEnd = Math.min(duration, raw.getLast().endMs() + 80);
        long activeMs = raw.stream().mapToLong(window -> window.endMs() - window.startMs()).sum();
        boolean reliable = sourceVideo && activeMs >= 120 && activeMs <= duration * 0.8
                && raw.stream().anyMatch(window -> window.endMs() - window.startMs() >= 120);
        return new Analysis(duration, trimStart, trimEnd, List.copyOf(raw), reliable);
    }

    private void append(List<Window> windows, int start, int last, int duration) {
        if ((last - start + 1) * FRAME_MS >= 60)
            windows.add(new Window(start * FRAME_MS, Math.min(duration, (last + 1) * FRAME_MS)));
    }
}

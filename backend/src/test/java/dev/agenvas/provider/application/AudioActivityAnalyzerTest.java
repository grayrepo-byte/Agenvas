package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioActivityAnalyzerTest {
    private final AudioActivityAnalyzer analyzer = new AudioActivityAnalyzer();

    @Test void sourceEnergyFindsSeparatedActivityButDoesNotTreatContinuousSoundAsReliableDialogue() {
        var result = analyzer.analyze(pcm(6000, List.of(new AudioActivityAnalyzer.Window(1000, 2000),
                new AudioActivityAnalyzer.Window(4000, 5000))), true);
        assertThat(result.reliableWindows()).isTrue();
        assertThat(result.windows()).containsExactly(new AudioActivityAnalyzer.Window(1000, 2000),
                new AudioActivityAnalyzer.Window(4000, 5000));
        assertThat(analyzer.analyze(pcm(6000, List.of(new AudioActivityAnalyzer.Window(0, 6000))), true).reliableWindows()).isFalse();
        assertThat(analyzer.analyze(pcm(6000, List.of()), true).windows()).isEmpty();
    }

    @Test void trimsOnlyExteriorSilenceAndRetainsPaddingAndInteriorPauses() {
        var result = analyzer.analyze(pcm(3000, List.of(new AudioActivityAnalyzer.Window(500, 1000),
                new AudioActivityAnalyzer.Window(1800, 2400))), false);
        assertThat(result.trimStartMs()).isEqualTo(420);
        assertThat(result.trimEndMs()).isEqualTo(2480);
        assertThat(result.durationMs()).isEqualTo(3000);
    }

    private byte[] pcm(int durationMs, List<AudioActivityAnalyzer.Window> windows) {
        int samples = durationMs * AudioActivityAnalyzer.SAMPLE_RATE / 1000;
        byte[] bytes = new byte[samples * 2];
        for (int index = 0; index < samples; index++) {
            int ms = index * 1000 / AudioActivityAnalyzer.SAMPLE_RATE;
            if (windows.stream().noneMatch(window -> ms >= window.startMs() && ms < window.endMs())) continue;
            short value = (short) (8000 * Math.sin(index * 2 * Math.PI * 440 / AudioActivityAnalyzer.SAMPLE_RATE));
            bytes[index * 2] = (byte) value;
            bytes[index * 2 + 1] = (byte) (value >> 8);
        }
        return bytes;
    }
}

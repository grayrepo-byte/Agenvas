package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class VideoCompositionLimitsTest {
    static VideoCompositionLimits configured(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("agenvas.video-composition.limits",
                VideoCompositionLimits.class).orElseThrow(() -> new IllegalArgumentException("Missing limits"));
    }
    @Test void deploymentCanLowerBudgetsAndCannotExceedArchiveOrProtocolCeilings() {
        var limits = configured(Map.of("agenvas.video-composition.limits.max-duration-ms", "120000",
                "agenvas.video-composition.limits.max-shots", "20", "agenvas.video-composition.limits.max-resolution", "1080P"));
        assertThat(limits.maxFrames()).isEqualTo(3600);
        assertThat(limits.resolutions()).containsExactly("720P", "1080P");
        for (var entry : Map.of("max-duration-ms", "2147483647", "max-shots", "121", "max-lines", "601",
                "max-subtitles", "5001", "max-output-bytes", "524288001", "max-scratch-bytes", "8589934593",
                "timeout", "PT2H", "preview-timeout", "PT3M", "scratch-root", "relative").entrySet()) {
            assertThatThrownBy(() -> configured(Map.of("agenvas.video-composition.limits." + entry.getKey(), entry.getValue())))
                    .isInstanceOf(RuntimeException.class);
        }
    }
    @Test void plannerUsesLoweredBudgetsAtTheSameFrameBoundary() {
        var limits = configured(Map.of("agenvas.video-composition.limits.max-duration-ms", "61000"));
        var planner = new VideoCompositionPlanner(limits);
        var id = java.util.UUID.randomUUID();
        var shot = new VideoCompositionPlanner.Shot(id, 0, 61_000, 1, null, java.util.List.of());
        assertThat(planner.plan(java.util.List.of(shot)).frames()).isEqualTo(1830);
        assertThatThrownBy(() -> planner.plan(java.util.List.of(shot,
                new VideoCompositionPlanner.Shot(id, 0, 100, 1, null, java.util.List.of())))).isInstanceOf(IllegalArgumentException.class);
    }
}

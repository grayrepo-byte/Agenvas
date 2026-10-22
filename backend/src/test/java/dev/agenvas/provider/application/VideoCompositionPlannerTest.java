package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoCompositionPlannerTest {
    private final VideoCompositionPlanner planner = new VideoCompositionPlanner();

    @Test void orderedDialogueUsesSourceActivityAndExplicitAnchorsOverrideItsPlacement() {
        var activity = new AudioActivityAnalyzer.Analysis(6000, 0, 6000,
                List.of(new AudioActivityAnalyzer.Window(1000, 2000), new AudioActivityAnalyzer.Window(4000, 5000)), true);
        var plan = planner.plan(List.of(shot(6000, activity, List.of(line(600, null), line(600, null)))));
        assertThat(plan.shots().getFirst().lines()).extracting(VideoCompositionPlanner.PlacedLine::startMs).containsExactly(1000, 4000);
        assertThat(plan.shots().getFirst().lines()).extracting(VideoCompositionPlanner.PlacedLine::alignmentEvidence)
                .containsOnly("SOURCE_SOUND_ACTIVITY");
        var anchored = planner.plan(List.of(shot(6000, activity, List.of(line(600, 500)))));
        assertThat(anchored.shots().getFirst().lines().getFirst().startMs()).isEqualTo(500);
        assertThat(anchored.shots().getFirst().lines().getFirst().alignmentEvidence()).isEqualTo("EXPLICIT_SCRIPT_ANCHOR");
        assertThat(anchored.warnings()).contains("SHOT_1_LINE_1_EXPLICIT_ANCHOR_OUTSIDE_SOUND_WINDOW");
    }

    @Test void fallbackKeepsCompleteLinesAndAcceleratesOnlyWithinTheLimit() {
        var plan = planner.plan(List.of(shot(2000, null, List.of(line(2100, null)))));
        var line = plan.shots().getFirst().lines().getFirst();
        assertThat(line.speed()).isGreaterThan(1).isLessThanOrEqualTo(1.15);
        assertThat(line.trimEndMs() - line.trimStartMs()).isEqualTo(2100);
        assertThat(plan.warnings()).containsExactly("SHOT_1_NO_RELIABLE_SOUND_WINDOWS_DURATION_FALLBACK");
        assertThatThrownBy(() -> planner.plan(List.of(shot(2000, null, List.of(line(2300, null))))))
                .isInstanceOf(VideoCompositionPlanner.DoesNotFit.class);
    }

    @Test void activityEndBoundsAccelerationAndShortWindowsAreNotMisrepresentedAsAlignment() {
        var activity = new AudioActivityAnalyzer.Analysis(6000, 0, 6000,
                List.of(new AudioActivityAnalyzer.Window(1000, 2000)), true);
        var aligned = planner.plan(List.of(shot(6000, activity, List.of(line(1050, null)))));
        var placed = aligned.shots().getFirst().lines().getFirst();
        assertThat(placed.startMs()).isEqualTo(1000);
        assertThat(placed.endMs()).isLessThanOrEqualTo(2000);
        assertThat(placed.limitMs()).isEqualTo(2000);
        assertThat(placed.speed()).isGreaterThan(1).isLessThanOrEqualTo(1.15);
        var fallback = planner.plan(List.of(shot(6000, activity, List.of(line(3000, null)))));
        assertThat(fallback.shots().getFirst().lines().getFirst().alignmentEvidence()).isEqualTo("DURATION_FALLBACK");
        assertThat(fallback.warnings()).contains("SHOT_1_LINE_1_ACTIVITY_WINDOW_TOO_SHORT_DURATION_FALLBACK");
    }

    @Test void shortActivityPausesMayBeSpannedButLongSilenceAndExhaustedWindowsProduceWarnings() {
        var activity = new AudioActivityAnalyzer.Analysis(6000, 0, 6000,
                List.of(new AudioActivityAnalyzer.Window(1000, 1600), new AudioActivityAnalyzer.Window(1800, 2400)), true);
        var merged = planner.plan(List.of(shot(6000, activity, List.of(line(1200, null), line(600, null)))));
        assertThat(merged.shots().getFirst().lines().getFirst().alignmentEvidence()).isEqualTo("SOURCE_SOUND_ACTIVITY");
        assertThat(merged.shots().getFirst().lines().getFirst().limitMs()).isEqualTo(2400);
        assertThat(merged.shots().getFirst().lines().get(1).alignmentEvidence()).isEqualTo("DURATION_FALLBACK");
        assertThat(merged.warnings()).contains("SHOT_1_LINE_2_NO_REMAINING_SOUND_WINDOW_DURATION_FALLBACK");
        var separated = new AudioActivityAnalyzer.Analysis(6000, 0, 6000,
                List.of(new AudioActivityAnalyzer.Window(1000, 1600), new AudioActivityAnalyzer.Window(3000, 3600)), true);
        var fallback = planner.plan(List.of(shot(6000, separated, List.of(line(1800, null)))));
        assertThat(fallback.shots().getFirst().lines().getFirst().alignmentEvidence()).isEqualTo("DURATION_FALLBACK");
        assertThat(fallback.warnings()).contains("SHOT_1_LINE_1_ACTIVITY_WINDOW_TOO_SHORT_DURATION_FALLBACK");
    }

    @Test void explicitOverlapAndOverlongTimelineAreRejected() {
        assertThatThrownBy(() -> planner.plan(List.of(shot(4000, null, List.of(line(1000, 1000), line(1000, 1500))))))
                .isInstanceOf(VideoCompositionPlanner.DoesNotFit.class);
        assertThatThrownBy(() -> planner.plan(List.of(shot(60_000, null, List.of()), shot(1000, null, List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sixTenSecondClipsProduceExactlySixtySecondsAndEighteenHundredFrames() {
        var shots = java.util.stream.IntStream.range(0, 6).mapToObj(index -> shot(10_000, null, List.of())).toList();
        var plan = planner.plan(shots);
        assertThat(plan.durationMs()).isEqualTo(60_000);
        assertThat(plan.frames()).isEqualTo(1800);
        assertThat(plan.shots()).extracting(VideoCompositionPlanner.PlacedShot::timelineStartMs)
                .containsExactly(0, 10_000, 20_000, 30_000, 40_000, 50_000);
    }

    @Test void shotSpeedScalesTheSoundWindowsButExplicitDialogueAnchorsUseOutputTime() {
        var activity = new AudioActivityAnalyzer.Analysis(6000, 0, 6000,
                List.of(new AudioActivityAnalyzer.Window(2000, 4000)), true);
        UUID id = UUID.randomUUID();
        var plan = planner.plan(List.of(new VideoCompositionPlanner.Shot(id, 0, 6000, 2, activity, List.of(line(600, null)))));
        var placed = plan.shots().getFirst();
        assertThat(plan.durationMs()).isEqualTo(3000);
        assertThat(placed.sourceDurationMs()).isEqualTo(6000);
        assertThat(placed.sourceActivityWindows()).containsExactly(new AudioActivityAnalyzer.Window(1000, 2000));
        assertThat(placed.lines().getFirst().startMs()).isEqualTo(1000);
        assertThat(placed.lines().getFirst().alignmentEvidence()).isEqualTo("SOURCE_SOUND_ACTIVITY");
        var anchored = planner.plan(List.of(new VideoCompositionPlanner.Shot(id, 0, 6000, 2, activity, List.of(line(600, 200)))));
        assertThat(anchored.shots().getFirst().lines().getFirst().startMs()).isEqualTo(200);
    }

    private VideoCompositionPlanner.Shot shot(int duration, AudioActivityAnalyzer.Analysis activity, List<VideoCompositionPlanner.Line> lines) {
        return new VideoCompositionPlanner.Shot(UUID.randomUUID(), 0, duration, 1, activity, lines);
    }
    private VideoCompositionPlanner.Line line(int duration, Integer start) {
        return new VideoCompositionPlanner.Line(UUID.randomUUID(), start, new AudioActivityAnalyzer.Analysis(duration, 0, duration,
                List.of(new AudioActivityAnalyzer.Window(0, duration)), false));
    }
}

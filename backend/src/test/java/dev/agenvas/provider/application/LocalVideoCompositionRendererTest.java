package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Runs installed FFmpeg against generated signals; no model output or private media is used. */
class LocalVideoCompositionRendererTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private MediaToolRunner tools;
    private LocalVideoCompositionRenderer renderer;

    @BeforeEach void requireLocalMediaTools() {
        var properties = new MediaToolsProperties(null, null, null);
        assumeTrue(Files.isExecutable(properties.ffmpeg()) && Files.isExecutable(properties.ffprobe()), "FFmpeg and ffprobe required");
        tools = new MediaToolRunner(properties);
        renderer = new LocalVideoCompositionRenderer(tools, mapper);
    }

    @Test void originalSoundWindowsPlaceWholeTrimmedLinesAndMuteOriginalAudio() throws Exception {
        Path source = video("source.mp4", "6", true, "if(between(t,1,2)+between(t,4,5),1,0)");
        Path voice = audio("voice.wav", "0.9", "if(between(t,0.2,0.7),1,0)");
        UUID videoId = UUID.randomUUID(), audioId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        var lines = addShot(request, videoId, 6000).withArray("lines");
        lines.addObject().put("audioVersionId", audioId.toString());
        lines.addObject().put("audioVersionId", audioId.toString());
        AtomicInteger ticks = new AtomicInteger();
        var result = renderer.render(request, Map.of(videoId, source(source, 6000), audioId, source(voice, 900)),
                scratch("render"), () -> false, ticks::incrementAndGet);
        JsonNode actual = result.schedule().path("shots").get(0).path("lines");
        assertThat(actual).hasSize(2);
        assertThat(actual.get(0).path("startMs").asInt()).isBetween(950, 1050);
        assertThat(actual.get(1).path("startMs").asInt()).isBetween(3950, 4050);
        for (JsonNode line : actual) {
            assertThat(line.path("alignmentEvidence").asText()).isEqualTo("SOURCE_SOUND_ACTIVITY");
            assertThat(line.path("trimStartMs").asInt()).isBetween(80, 180);
            assertThat(line.path("trimEndMs").asInt()).isBetween(760, 860);
            assertThat(line.path("speed").asDouble()).isEqualTo(1);
            assertThat(line.path("endMs").asInt()).isLessThanOrEqualTo(line.path("placementLimitMs").asInt());
        }
        assertThat(ticks.get()).isPositive();
        assertThat(result.schedule().path("durationMs").asInt()).isEqualTo(6000);
        byte[] pcm = decodeAudio(result.path(), "result.pcm");
        assertThat(rms(pcm, 100, 700)).isLessThan(0.0001);
        assertThat(rms(pcm, 1250, 1450)).isGreaterThan(0.02);
        // The source is still audible here, but the new shorter dialogue has ended.
        assertThat(rms(pcm, 1850, 1950)).isLessThan(0.0001);
    }

    @Test void previewSharesExportTimingAndDoesNotRenderAnOutput() throws Exception {
        Path source = video("preview-source.mp4", "6", true, "if(between(t,1,2)+between(t,4,5),1,0)");
        Path voice = audio("preview-voice.wav", "0.9", "if(between(t,0.2,0.7),1,0)");
        UUID videoId = UUID.randomUUID(), audioId = UUID.randomUUID();
        ObjectNode request = request("KEEP");
        addShot(request, videoId, 6000).put("speed", 2).withArray("lines").addObject().put("audioVersionId", audioId.toString());
        var sources = Map.of(videoId, source(source, 6000), audioId, source(voice, 900));
        Path previewDir = scratch("preview-only");
        var preview = renderer.preview(request, sources, previewDir);
        try (var files = Files.list(previewDir)) { assertThat(files.toList()).isEmpty(); }
        var output = renderer.render(request, sources, scratch("matching-export"), () -> false, () -> {});
        assertThat(preview.path("frames").asInt()).isEqualTo(90);
        var expected = output.schedule().path("shots").get(0).path("lines").get(0);
        var actual = preview.path("shots").get(0).path("lines").get(0);
        for (String field : List.of("audioVersionId", "startMs", "trimStartMs", "trimEndMs", "speed"))
            assertThat(actual.path(field)).isEqualTo(expected.path(field));
        assertThat(Math.abs(actual.path("endMs").asInt() - expected.path("endMs").asInt())).isLessThanOrEqualTo(40);
    }

    @Test void sixCroppedClipsHaveExactlySixtySecondsIncludingTheAacTail() throws Exception {
        Path source = video("fractional-source.mp4", "10.125", false, null);
        UUID videoId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        for (int index = 0; index < 6; index++) addShot(request, videoId, 10_000);
        var result = renderer.render(request, Map.of(videoId, source(source, 10_125)), scratch("sixty-seconds"), () -> false, () -> {});
        JsonNode probe = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-count_frames", "-show_entries",
                "stream=codec_type,width,height,duration,nb_read_frames:format=duration", "-of", "json", result.path().toString())));
        assertThat(probe.at("/format/duration").asDouble()).isEqualTo(60.0);
        JsonNode video = probe.path("streams").valueStream().filter(s -> "video".equals(s.path("codec_type").asText())).findFirst().orElseThrow();
        JsonNode audio = probe.path("streams").valueStream().filter(s -> "audio".equals(s.path("codec_type").asText())).findFirst().orElseThrow();
        assertThat(video.path("nb_read_frames").asInt()).isEqualTo(1800);
        assertThat(video.path("width").asInt()).isEqualTo(1280);
        assertThat(video.path("height").asInt()).isEqualTo(720);
        assertThat(video.path("duration").asDouble()).isEqualTo(60.0);
        assertThat(audio.path("duration").asDouble()).isEqualTo(60.0);
        assertThat(result.schedule().path("durationMs").asInt()).isEqualTo(60_000);
        assertThat(result.schedule().path("shots")).hasSize(6);
        // Decode both streams all the way to EOF, not just the container header.
        tools.ffmpeg(List.of("-v", "error", "-xerror", "-i", result.path().toString(), "-f", "null", "-"));
    }

    @Test void backgroundKeepsOriginalSoundAtTheDeclaredGainAndWarnsAboutOriginalDialogue() throws Exception {
        Path source = video("background.mp4", "2", true, "1");
        UUID videoId = UUID.randomUUID();
        ObjectNode request = request("BACKGROUND");
        addShot(request, videoId, 2000);
        var result = renderer.render(request, Map.of(videoId, source(source, 2000)), scratch("background-render"), () -> false, () -> {});
        assertThat(result.schedule().path("warnings").valueStream().map(JsonNode::asText))
                .contains("ORIGINAL_AUDIO_AT_0_15_INCLUDES_ANY_ORIGINAL_DIALOGUE");
        double output = rms(decodeAudio(result.path(), "background-result.pcm"), 300, 1500);
        // FFmpeg uses equal-power coefficients when converting mono center to stereo. Compare
        // gain after the same channel conversion, otherwise the baseline differs by sqrt(1/2).
        Path stereoBaseline = temp.resolve("background-stereo-baseline.wav");
        tools.ffmpeg(List.of("-v", "error", "-i", source.toString(), "-vn", "-af",
                "aresample=48000,aformat=channel_layouts=stereo", "-c:a", "pcm_s16le", "-y", stereoBaseline.toString()));
        double original = rms(decodeAudio(stereoBaseline, "background-source.pcm"), 300, 1500);
        assertThat(output / original).isBetween(0.14, 0.16);
    }

    @Test void slightlyLongVoiceUsesBoundedTempoAndRetainsTheCompleteLine() throws Exception {
        Path source = video("tempo-source.mp4", "2", false, null);
        Path voice = audio("tempo-voice.wav", "2.1", "1");
        UUID videoId = UUID.randomUUID(), audioId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        addShot(request, videoId, 2000).withArray("lines").addObject().put("audioVersionId", audioId.toString());
        var result = renderer.render(request, Map.of(videoId, source(source, 2000), audioId, source(voice, 2100)),
                scratch("tempo-render"), () -> false, () -> {});
        JsonNode line = result.schedule().at("/shots/0/lines/0");
        assertThat(line.path("trimStartMs").asInt()).isZero();
        assertThat(line.path("trimEndMs").asInt()).isEqualTo(2100);
        assertThat(line.path("speed").asDouble()).isGreaterThan(1).isLessThanOrEqualTo(1.15);
        assertThat(line.path("endMs").asInt()).isBetween(1900, 2000);
        assertThat(line.path("alignmentEvidence").asText()).isEqualTo("DURATION_FALLBACK");
        assertThat(result.schedule().path("warnings").valueStream().map(JsonNode::asText))
                .contains("SHOT_1_NO_RELIABLE_SOUND_WINDOWS_DURATION_FALLBACK");
    }

    @Test void overflowAndCancellationFailWithoutProducingAComposedFile() throws Exception {
        Path source = video("short.mp4", "2", false, null);
        Path voice = audio("long.wav", "3", "1");
        UUID videoId = UUID.randomUUID(), audioId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        addShot(request, videoId, 2000).withArray("lines").addObject().put("audioVersionId", audioId.toString());
        var sources = Map.of(videoId, source(source, 2000), audioId, source(voice, 3000));
        Path overflowScratch = scratch("overflow");
        assertThatThrownBy(() -> renderer.render(request, sources, overflowScratch, () -> false, () -> {}))
                .isInstanceOf(VideoCompositionPlanner.DoesNotFit.class);
        assertThat(Files.exists(overflowScratch.resolve("composition.mp4"))).isFalse();
        Path canceledScratch = scratch("canceled");
        assertThatThrownBy(() -> renderer.render(request, sources, canceledScratch, () -> true, () -> {}))
                .isInstanceOf(CancellationException.class);
        try (var files = Files.list(canceledScratch)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void longerContainerAudioCannotHideAnIncompleteVideoTimeline() throws Exception {
        Path source = temp.resolve("one-frame-short.mp4");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=navy:s=160x90:r=30:d=1.9666666667",
                "-f", "lavfi", "-i", "sine=duration=2:sample_rate=48000", "-c:v", "libx264", "-c:a", "aac", "-y", source.toString()));
        UUID videoId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        addShot(request, videoId, 2000);
        assertThatThrownBy(() -> renderer.render(request, Map.of(videoId, source(source, 2000)),
                scratch("incomplete-video"), () -> false, () -> {}))
                .isInstanceOf(IllegalStateException.class).hasMessage("Composition ended before the complete media timeline");
    }

    @Test void postproductionSpeedKeepsOriginalSoundAndChangesTheOutputCanvasWithoutDialogue() throws Exception {
        Path source = video("speed-source.mp4", "4", true, "1");
        UUID videoId = UUID.randomUUID();
        ObjectNode request = request("KEEP");
        addShot(request, videoId, 4000).put("speed", 2).put("fadeMs", 100);
        request.putObject("output").put("resolution", "720P").put("aspectRatio", "9:16");
        var result = renderer.render(request, Map.of(videoId, source(source, 4000)), scratch("postproduction"), () -> false, () -> {});
        assertThat(result.schedule().path("durationMs").asInt()).isEqualTo(2000);
        assertThat(result.schedule().path("width").asInt()).isEqualTo(720);
        assertThat(result.schedule().path("height").asInt()).isEqualTo(1280);
        assertThat(result.schedule().at("/shots/0/sourceDurationMs").asInt()).isEqualTo(4000);
        assertThat(result.schedule().at("/shots/0/speed").asDouble()).isEqualTo(2);
        assertThat(result.schedule().at("/shots/0/fadeMs").asInt()).isEqualTo(100);
        assertThat(rms(decodeAudio(result.path(), "kept-sound.pcm"), 300, 1500)).isGreaterThan(0.02);
    }

    @Test void postproductionMusicStartsFromItsPinnedOffsetAndMayEndBeforeTheTimeline() throws Exception {
        Path source = video("music-source.mp4", "3", false, null);
        Path music = audio("music.wav", "2", "if(between(t,1,2),1,0)");
        UUID videoId = UUID.randomUUID(), musicId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        addShot(request, videoId, 3000);
        request.putObject("music").put("audioVersionId", musicId.toString()).put("sourceStartMs", 1000).put("volume", 0.5);
        var result = renderer.render(request, Map.of(videoId, source(source, 3000), musicId, source(music, 2000)),
                scratch("music-render"), () -> false, () -> {});
        assertThat(result.schedule().at("/music/audioVersionId").asText()).isEqualTo(musicId.toString());
        assertThat(result.schedule().at("/music/sourceStartMs").asInt()).isEqualTo(1000);
        byte[] pcm = decodeAudio(result.path(), "music-result.pcm");
        assertThat(rms(pcm, 200, 700)).isGreaterThan(0.01);
        assertThat(rms(pcm, 1800, 2500)).isLessThan(0.0001);
        assertThat(result.schedule().path("durationMs").asInt()).isEqualTo(3000);
    }

    @Test void postproductionSlowMotionRetainsThePlannedFramesAndFadesToBlack() throws Exception {
        Path source = video("slow-source.mp4", "2", false, null);
        UUID videoId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        addShot(request, videoId, 2000).put("speed", 0.5).put("fadeMs", 500);
        request.putObject("output").put("resolution", "1080P").put("aspectRatio", "1:1");
        var result = renderer.render(request, Map.of(videoId, source(source, 2000)), scratch("slow-render"), () -> false, () -> {});
        assertThat(result.schedule().path("durationMs").asInt()).isEqualTo(4000);
        assertThat(result.schedule().path("width").asInt()).isEqualTo(1080);
        assertThat(result.schedule().path("height").asInt()).isEqualTo(1080);
        Path frame = temp.resolve("fade-frame.png");
        tools.ffmpeg(List.of("-v", "error", "-i", result.path().toString(), "-frames:v", "1", "-y", frame.toString()));
        var image = javax.imageio.ImageIO.read(frame.toFile());
        assertThat(image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 0xffffff).isLessThan(0x050505);
    }

    @ParameterizedTest
    @CsvSource({"24,0,1033,1,30,1000", "30,100,1050,1,31,1033", "24,125,1049,0.5,62,2066",
            "30,300,1033,2,15,500", "30,150,1300,1.15,33,1100"})
    void fractionalShotDurationsCutAtThePlannedFrameBoundary(int sourceFps, int startMs, int durationMs,
            double speed, int firstBlueFrame, int secondShotStartMs) throws Exception {
        Path red = temp.resolve("red.mp4"), blue = temp.resolve("blue.mp4");
        for (var entry : Map.of(red, "red", blue, "blue").entrySet()) {
            tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i",
                    "color=c=" + entry.getValue() + ":s=160x90:r=" + sourceFps + ":d=2",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-y", entry.getKey().toString()));
        }
        UUID redId = UUID.randomUUID(), blueId = UUID.randomUUID();
        ObjectNode request = request("MUTE");
        addShot(request, redId, durationMs).put("sourceStartMs", startMs).put("speed", speed);
        addShot(request, blueId, durationMs).put("sourceStartMs", startMs).put("speed", speed);
        var result = renderer.render(request, Map.of(redId, source(red, 2000), blueId, source(blue, 2000)),
                scratch("fractional-render"), () -> false, () -> {});
        assertThat(result.schedule().at("/shots/1/timelineStartMs").asInt()).isEqualTo(secondShotStartMs);
        Path pixels = temp.resolve("frames.rgb");
        tools.ffmpeg(List.of("-v", "error", "-i", result.path().toString(), "-vf", "scale=1:1", "-pix_fmt", "rgb24",
                "-f", "rawvideo", "-y", pixels.toString()));
        byte[] rgb = Files.readAllBytes(pixels);
        assertThat(rgb).hasSize(firstBlueFrame * 2 * 3);
        for (int frame = 0; frame < firstBlueFrame * 2; frame++) {
            int redChannel = rgb[frame * 3] & 255, blueChannel = rgb[frame * 3 + 2] & 255;
            assertThat(frame < firstBlueFrame ? redChannel : blueChannel).as("dominant color at frame %s", frame).isGreaterThan(200);
            assertThat(frame < firstBlueFrame ? blueChannel : redChannel).as("opposite color at frame %s", frame).isLessThan(20);
        }
    }

    @ParameterizedTest
    @CsvSource({"KEEP", "BACKGROUND"})
    void fractionalCutsKeepOriginalAudioOnTheSameTimeline(String mode) throws Exception {
        Path red = temp.resolve("red-sound.mp4"), blue = temp.resolve("blue-sound.mp4");
        for (var entry : Map.of(red, "red", blue, "blue").entrySet()) {
            String frequency = entry.getKey().equals(red) ? "440" : "880";
            tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i",
                    "color=c=" + entry.getValue() + ":s=160x90:r=30:d=2", "-f", "lavfi", "-i",
                    "sine=frequency=" + frequency + ":sample_rate=48000:duration=2", "-t", "2",
                    "-c:a", "aac", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-y", entry.getKey().toString()));
        }
        UUID redId = UUID.randomUUID(), blueId = UUID.randomUUID();
        ObjectNode request = request(mode);
        addShot(request, redId, 1050).put("sourceStartMs", 137);
        addShot(request, blueId, 1050).put("sourceStartMs", 137);
        var result = renderer.render(request, Map.of(redId, source(red, 2000), blueId, source(blue, 2000)),
                scratch("fractional-sound"), () -> false, () -> {});
        assertThat(result.schedule().at("/shots/1/timelineStartMs").asInt()).isEqualTo(1033);
        Path frame = temp.resolve("first-blue-with-sound.png");
        tools.ffmpeg(List.of("-v", "error", "-i", result.path().toString(), "-vf", "select=eq(n\\,31)",
                "-frames:v", "1", "-y", frame.toString()));
        var image = javax.imageio.ImageIO.read(frame.toFile());
        assertThat(image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 255).isGreaterThan(200);
        byte[] pcm = decodeAudio(result.path(), "cut-sound.pcm");
        assertThat(positiveCrossings(pcm, 850, 950)).as("440Hz before the cut").isBetween(42, 46);
        assertThat(positiveCrossings(pcm, 1130, 1230)).as("880Hz after the cut").isBetween(86, 90);
    }

    private int positiveCrossings(byte[] pcm, int startMs, int endMs) {
        int crossings = 0;
        for (int sample = startMs * 8 + 1; sample < endMs * 8; sample++) {
            short previous = (short) ((pcm[2 * sample - 2] & 255) | pcm[2 * sample - 1] << 8);
            short current = (short) ((pcm[2 * sample] & 255) | pcm[2 * sample + 1] << 8);
            if (previous <= 0 && current > 0) crossings++;
        }
        return crossings;
    }

    private Path video(String name, String duration, boolean sound, String volume) {
        Path result = temp.resolve(name);
        List<String> args = new ArrayList<>(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=navy:s=160x90:r=24:d=" + duration));
        if (sound) args.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + duration,
                "-af", "volume='" + volume + "':eval=frame", "-c:a", "aac"));
        args.addAll(List.of("-t", duration, "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-y", result.toString()));
        tools.ffmpeg(args);
        return result;
    }
    private Path audio(String name, String duration, String volume) {
        Path result = temp.resolve(name);
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=" + duration,
                "-af", "volume='" + volume + "':eval=frame", "-c:a", "pcm_s16le", "-y", result.toString()));
        return result;
    }
    private byte[] decodeAudio(Path media, String name) throws Exception {
        Path pcm = temp.resolve(name);
        tools.ffmpeg(List.of("-v", "error", "-i", media.toString(), "-map", "0:a:0", "-ac", "1", "-ar", "8000",
                "-c:a", "pcm_s16le", "-f", "s16le", "-y", pcm.toString()));
        return Files.readAllBytes(pcm);
    }
    private double rms(byte[] pcm, int startMs, int endMs) {
        int first = startMs * 8, last = Math.min(endMs * 8, pcm.length / 2);
        double sum = 0;
        for (int sample = first; sample < last; sample++) {
            short value = (short) ((pcm[2 * sample] & 255) | pcm[2 * sample + 1] << 8);
            sum += Math.pow(value / 32768.0, 2);
        }
        return Math.sqrt(sum / (last - first));
    }
    private ObjectNode request(String mode) {
        ObjectNode request = mapper.createObjectNode().put("title", "Synthetic composition")
                .put("rationale", "Verify complete media rendering").put("originalAudioMode", mode);
        request.putArray("shots");
        return request;
    }
    private ObjectNode addShot(ObjectNode request, UUID video, int durationMs) {
        ObjectNode shot = request.withArray("shots").addObject().put("videoVersionId", video.toString())
                .put("sourceStartMs", 0).put("durationMs", durationMs);
        shot.putArray("lines");
        return shot;
    }
    private LocalVideoCompositionRenderer.Source source(Path path, int durationMs) {
        return new LocalVideoCompositionRenderer.Source(path, durationMs);
    }
    private Path scratch(String name) throws Exception { return Files.createDirectory(temp.resolve(name)); }
}

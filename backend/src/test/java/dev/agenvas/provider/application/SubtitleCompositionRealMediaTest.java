package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Actual FFmpeg/libass, hand-authored corrected captions and synthetic video. No ASR model runs. */
class SubtitleCompositionRealMediaTest {
    @TempDir Path temp;
    private Path workspace;
    @AfterEach void cleanup() throws Exception {
        if (workspace == null) return;
        try (var files = Files.walk(workspace)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    @Test void correctedChineseCaptionAppearsOnlyAtItsLateCompositionOffset() throws Exception {
        var defaults = new MediaToolsProperties(null, null, null);
        Path binary = Path.of(System.getProperty("agenvas.test.subtitle-ffmpeg", defaults.ffmpeg().toString()));
        Process probe = new ProcessBuilder(binary.toString(), "-hide_banner", "-filters").redirectErrorStream(true).start();
        String filters = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(probe.waitFor()).isZero();
        assumeTrue(filters.contains("Render ASS subtitles"), "FFmpeg/libass is required; this is not an ASR test");
        String root = System.getProperty("agenvas.test.subtitle-workspace");
        Path work = temp;
        if (root != null) { workspace = Files.createTempDirectory(Path.of(root).toRealPath(), "late-subtitle-"); work = workspace; }
        var nativeTools = new MediaToolRunner(defaults);
        var tools = new MediaToolRunner(new MediaToolsProperties(binary, defaults.ffprobe(), null));
        Path first = work.resolve("black-90s.mp4"), second = work.resolve("black-4s.mp4");
        for (var source : List.of(Map.entry(first, "90"), Map.entry(second, "4")))
            nativeTools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=black:s=160x90:r=30:d=" + source.getValue(),
                    "-an", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-y", source.getKey().toString()));
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        var mapper = new ObjectMapper();
        var request = mapper.createObjectNode().put("title", "Synthetic corrected subtitle export").put("originalAudioMode", "MUTE");
        var shots = request.putArray("shots");
        shots.addObject().put("videoVersionId", firstId.toString()).put("sourceStartMs", 0).put("durationMs", 90000).putArray("lines");
        shots.addObject().put("videoVersionId", secondId.toString()).put("sourceStartMs", 0).put("durationMs", 4000).putArray("lines");
        request.putArray("subtitles").addObject().put("text", "校正后的中文 Corrected").put("startMs", 91500).put("endMs", 93500);
        Path scratch = Files.createDirectory(work.resolve("render"));
        var renderer = new LocalVideoCompositionRenderer(tools, mapper, new VideoCompositionRenderProperties("Noto Sans CJK SC"));
        var result = renderer.render(request, Map.of(firstId, new LocalVideoCompositionRenderer.Source(first, 90000),
                secondId, new LocalVideoCompositionRenderer.Source(second, 4000)), scratch, () -> false, () -> {});
        assertThat(result.schedule().path("durationMs").asInt()).isEqualTo(94000);
        assertThat(result.schedule().path("subtitles")).isEqualTo(request.path("subtitles"));
        assertThat(brightPixels(nativeTools, result.path(), work, "91.0", "before")).isZero();
        assertThat(brightPixels(nativeTools, result.path(), work, "92.0", "during")).isGreaterThan(100);
        assertThat(brightPixels(nativeTools, result.path(), work, "93.8", "after")).isZero();
    }
    private int brightPixels(MediaToolRunner tools, Path video, Path work, String seconds, String label) throws Exception {
        Path frame = work.resolve(label + ".png");
        tools.ffmpeg(List.of("-v", "error", "-ss", seconds, "-i", video.toString(), "-frames:v", "1", "-y", frame.toString()));
        var picture = ImageIO.read(frame.toFile());
        int bright = 0;
        for (int y = picture.getHeight() / 2; y < picture.getHeight(); y++)
            for (int x = 0; x < picture.getWidth(); x++) if ((picture.getRGB(x, y) & 0xff) > 180) bright++;
        return bright;
    }
}

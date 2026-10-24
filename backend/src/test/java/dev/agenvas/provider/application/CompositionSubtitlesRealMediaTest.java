package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Requires an FFmpeg build with libass; a local override can select a deployment-equivalent binary. */
class CompositionSubtitlesRealMediaTest {
    @TempDir Path defaultTemp;
    private Path mountedTemp;

    @AfterEach void removeExplicitWorkspace() throws Exception {
        if (mountedTemp == null) return;
        try (var files = Files.walk(mountedTemp)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @Test void rendersVisibleSubtitlesIntoTheArchivedPictureWithoutInterpretingAssOverrides() throws Exception {
        var defaults = new MediaToolsProperties(null, null, null);
        String configured = System.getProperty("agenvas.test.subtitle-ffmpeg");
        Path ffmpeg = configured == null ? defaults.ffmpeg() : Path.of(configured);
        var check = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-filters").redirectErrorStream(true);
        check.environment().clear();
        Process process = check.start();
        String filters = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        assumeTrue(filters.contains("Render ASS subtitles"), "FFmpeg with libass required for subtitle rendering");
        // Surefire may set java.io.tmpdir after the JDK has cached its default. Docker-backed
        // binaries therefore need an explicit workspace under their known bind mount.
        String workspace = System.getProperty("agenvas.test.subtitle-workspace");
        Path temp = defaultTemp;
        if (workspace != null) {
            Path base = Path.of(workspace);
            assertThat(base.isAbsolute()).isTrue();
            mountedTemp = Files.createTempDirectory(base.toRealPath(), "subtitle-");
            temp = mountedTemp;
        }
        var tools = new MediaToolRunner(new MediaToolsProperties(ffmpeg, defaults.ffprobe(), null));
        var mapper = new ObjectMapper();
        var renderer = new LocalVideoCompositionRenderer(tools, mapper, new VideoCompositionRenderProperties("DejaVu Sans"));
        Path source = temp.resolve("black.mp4");
        tools.ffmpeg(List.of("-v", "error", "-f", "lavfi", "-i", "color=c=black:s=160x90:r=30:d=2",
                "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", source.toString()));
        UUID sourceId = UUID.randomUUID();
        var request = mapper.createObjectNode().put("title", "Subtitle smoke").put("originalAudioMode", "MUTE");
        request.putArray("shots").addObject().put("videoVersionId", sourceId.toString()).put("sourceStartMs", 0)
                .put("durationMs", 2000).putArray("lines");
        request.putArray("subtitles").addObject().put("text", "Visible text {\\p1}\\N safe").put("startMs", 0).put("endMs", 2000);
        Path scratch = Files.createDirectory(temp.resolve("render"));
        var result = renderer.render(request, Map.of(sourceId, new LocalVideoCompositionRenderer.Source(source, 2000)),
                scratch, () -> false, () -> {});
        Path frame = temp.resolve("caption-frame.png");
        tools.ffmpeg(List.of("-v", "error", "-ss", "1", "-i", result.path().toString(), "-frames:v", "1", "-y", frame.toString()));
        var picture = ImageIO.read(frame.toFile());
        int bright = 0;
        for (int y = picture.getHeight() / 2; y < picture.getHeight(); y++) for (int x = 0; x < picture.getWidth(); x++)
            if ((picture.getRGB(x, y) & 0xff) > 180) bright++;
        assertThat(bright).isGreaterThan(100);
        assertThat(result.schedule().path("subtitles")).isEqualTo(request.path("subtitles"));
        tools.ffmpeg(List.of("-v", "error", "-xerror", "-i", result.path().toString(), "-f", "null", "-"));
    }
}

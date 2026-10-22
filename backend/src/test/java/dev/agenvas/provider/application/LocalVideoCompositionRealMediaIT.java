package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit local-media acceptance, not a Provider or semantic-planning test.
 * The private JSON has request (the exact composition object), sources
 * [{versionId,path,durationMs}], and outputDirectory. No fixture paths are committed.
 */
@EnabledIfSystemProperty(named = "agenvas.test.composition.real-config", matches = ".+")
class LocalVideoCompositionRealMediaIT {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void renderRealExistingMediaAndPreservePrivateAuditableSchedule() throws Exception {
        Path configPath = Path.of(System.getProperty("agenvas.test.composition.real-config")).toAbsolutePath().normalize();
        assertOutsideRepository(configPath.toRealPath());
        assertThat(Files.size(configPath)).isLessThanOrEqualTo(65_536);
        JsonNode config = mapper.readTree(Files.readString(configPath));
        assertThat(config.path("request").isObject()).isTrue();
        assertThat(config.path("sources").isArray()).isTrue();
        Map<UUID, LocalVideoCompositionRenderer.Source> sources = new HashMap<>();
        long sourceBytes = 0;
        var counted = new java.util.HashSet<Path>();
        for (JsonNode source : config.path("sources")) {
            UUID version = UUID.fromString(source.path("versionId").asText());
            Path path = Path.of(source.path("path").asText());
            assertThat(path.isAbsolute()).isTrue();
            path = path.toRealPath();
            assertOutsideRepository(path);
            assertThat(Files.isRegularFile(path)).isTrue();
            int duration = source.path("durationMs").asInt();
            assertThat(duration).isBetween(1, 600_000);
            if (counted.add(path)) sourceBytes = Math.addExact(sourceBytes, Files.size(path));
            assertThat(sources.put(version, new LocalVideoCompositionRenderer.Source(path, duration))).isNull();
        }
        assertThat(sourceBytes).isBetween(1L, 1024L * 1024 * 1024);
        assertThat(sources).isNotEmpty();
        Path output = Path.of(config.path("outputDirectory").asText());
        assertThat(output.isAbsolute()).isTrue();
        assertOutsideRepository(output.normalize());
        Files.createDirectories(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        output = output.toRealPath();
        assertOutsideRepository(output);
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.exists(output.resolve("composition.mp4"))).isFalse();
        assertThat(Files.exists(output.resolve("schedule.json"))).isFalse();
        Path scratch = Files.createTempDirectory(output, "render-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            MediaToolRunner tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));
            var renderer = new LocalVideoCompositionRenderer(tools, mapper);
            var rendered = renderer.render(config.path("request"), Map.copyOf(sources), scratch, () -> false, () -> {});
            JsonNode probe = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries",
                    "stream=codec_type,width,height,duration:format=duration", "-of", "json", rendered.path().toString())));
            double actualDuration = probe.at("/format/duration").asDouble();
            assertThat(actualDuration).isGreaterThan(0).isLessThanOrEqualTo(60.0);
            JsonNode video = probe.path("streams").valueStream()
                    .filter(stream -> "video".equals(stream.path("codec_type").asText())).findFirst().orElseThrow();
            assertThat(video.path("width").asInt()).isEqualTo(1280);
            assertThat(video.path("height").asInt()).isEqualTo(720);
            assertThat(rendered.schedule().path("durationMs").asInt()).isEqualTo((int) Math.round(actualDuration * 1000));
            for (JsonNode shot : rendered.schedule().path("shots")) {
                int lastEnd = 0;
                for (JsonNode line : shot.path("lines")) {
                    assertThat(line.path("startMs").asInt()).isGreaterThanOrEqualTo(lastEnd);
                    lastEnd = line.path("endMs").asInt();
                    assertThat(lastEnd).isLessThanOrEqualTo(shot.path("durationMs").asInt());
                    assertThat(line.path("speed").asDouble()).isBetween(1.0, 1.15);
                    assertThat(line.path("alignmentEvidence").asText()).isIn("SOURCE_SOUND_ACTIVITY", "EXPLICIT_SCRIPT_ANCHOR", "DURATION_FALLBACK");
                }
            }
            tools.ffmpeg(List.of("-v", "error", "-xerror", "-i", rendered.path().toString(), "-f", "null", "-"));
            Path videoOutput = Files.copy(rendered.path(), output.resolve("composition.mp4"));
            Files.setPosixFilePermissions(videoOutput, PosixFilePermissions.fromString("rw-------"));
            Path scheduleOutput = Files.createFile(output.resolve("schedule.json"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(scheduleOutput, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rendered.schedule()), StandardOpenOption.WRITE);
            assertThat(Files.size(videoOutput)).isPositive();
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private void assertOutsideRepository(Path path) {
        Path repository = Path.of("").toAbsolutePath().normalize();
        while (repository != null && !Files.exists(repository.resolve(".git"))) repository = repository.getParent();
        if (repository == null) throw new IllegalStateException("Cannot locate repository boundary for private-media acceptance");
        assertThat(path.startsWith(repository)).as("Real fixtures and results must remain outside the repository").isFalse();
    }
}

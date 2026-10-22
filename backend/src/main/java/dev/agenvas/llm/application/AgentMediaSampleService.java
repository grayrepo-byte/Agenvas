package dev.agenvas.llm.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;

/** Authorized, bounded samples are decoded only at model dispatch, never in a tool transaction.
 * Checkpoints keep the exact version and requested times, not media bytes or temporary paths. */
@Service
public class AgentMediaSampleService {
    public static final String METADATA_KEY = "agentMediaSamples";
    public static final int MAX_FRAMES = 4;
    public static final int MAX_AUDIO_MS = 15_000;
    private static final int EDGE = 512;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaToolRunner tools;

    public AgentMediaSampleService(ArtifactService artifacts, AssetService assets, MediaToolRunner tools) {
        this.artifacts = artifacts; this.assets = assets; this.tools = tools;
    }

    public record Sample(UUID artifactId, UUID versionId, List<Integer> timestampsMs,
            Integer audioStartMs, Integer audioDurationMs) {}

    public static Sample parse(JsonNode value) {
        try {
            if (!value.isObject() || !java.util.Set.of("artifactId", "versionId", "timestampsMs", "audioStartMs", "audioDurationMs")
                    .containsAll(value.propertyNames())) throw invalid();
            UUID artifact = UUID.fromString(value.path("artifactId").asText());
            UUID version = UUID.fromString(value.path("versionId").asText());
            List<Integer> frames = new ArrayList<>();
            if (value.has("timestampsMs")) {
                if (!value.path("timestampsMs").isArray() || value.path("timestampsMs").size() > MAX_FRAMES) throw invalid();
                for (JsonNode time : value.path("timestampsMs")) {
                    int timestamp = integer(time, 0, 599_999);
                    if (frames.contains(timestamp)) throw invalid();
                    frames.add(timestamp);
                }
            }
            Integer start = value.hasNonNull("audioStartMs") ? integer(value.path("audioStartMs"), 0, 599_999) : null;
            Integer duration = value.hasNonNull("audioDurationMs") ? integer(value.path("audioDurationMs"), 1, MAX_AUDIO_MS) : null;
            if ((start == null) != (duration == null) || frames.isEmpty() && start == null) throw invalid();
            return new Sample(artifact, version, List.copyOf(frames), start, duration);
        } catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    /** Used by the read tool before committing its sample manifest; it performs no media I/O. */
    public Asset authorize(UUID owner, UUID project, JsonNode snapshot, Sample sample) {
        var version = artifacts.requireAgentReadableVersion(owner, project, snapshot, sample.versionId());
        if (!sample.artifactId().equals(version.artifactId())) throw invalid();
        Asset asset = assets.metadata(owner, project, UUID.fromString(version.content().path("assetId").asText()));
        if (!project.equals(asset.projectId()) || asset.durationMs() == null || asset.durationMs() < 1
                || asset.mediaKind() != Asset.MediaKind.VIDEO && asset.mediaKind() != Asset.MediaKind.AUDIO
                || asset.mediaKind() != Asset.MediaKind.VIDEO && !sample.timestampsMs().isEmpty()) throw invalid();
        if (sample.timestampsMs().stream().anyMatch(time -> time >= asset.durationMs())
                || sample.audioStartMs() != null && (long) sample.audioStartMs() + sample.audioDurationMs() > asset.durationMs()) throw invalid();
        return asset;
    }

    public List<Media> hydrate(UUID owner, UUID project, JsonNode snapshot, Sample sample) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Media sample dispatch cannot hold a database transaction");
        Asset asset = authorize(owner, project, snapshot, sample);
        Path directory = null;
        try {
            Path source = assets.get(owner, project, asset.id()).path();
            // Recheck local bytes too: remote caches already verify their own archived digest.
            if (asset.byteSize() < 1 || asset.byteSize() > 500L * 1024 * 1024 || Files.size(source) != asset.byteSize()) throw invalid();
            try (var stream = Files.newInputStream(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[32 * 1024]; int read; long total = 0;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    if (total > asset.byteSize()) throw invalid();
                    digest.update(buffer, 0, read);
                }
                if (total != asset.byteSize()) throw invalid();
                if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(asset.sha256())) throw invalid();
            } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
            directory = Files.createTempDirectory("agenvas-agent-sample-", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
            List<Media> output = new ArrayList<>();
            if (!sample.timestampsMs().isEmpty()) {
                BufferedImage sheet = new BufferedImage(EDGE * Math.min(2, sample.timestampsMs().size()),
                        (EDGE + 28) * ((sample.timestampsMs().size() + 1) / 2), BufferedImage.TYPE_INT_RGB);
                var graphics = sheet.createGraphics();
                try {
                    graphics.setColor(Color.BLACK); graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
                    graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
                    for (int i = 0; i < sample.timestampsMs().size(); i++) {
                        Path frame = directory.resolve("frame-" + i + ".png");
                        List<String> arguments = input(source, sample.timestampsMs().get(i));
                        arguments.addAll(List.of("-map", "0:v:0", "-frames:v", "1", "-vf",
                                "scale=512:512:force_original_aspect_ratio=decrease", "-threads", "1", "-y", frame.toString()));
                        tools.ffmpeg(arguments);
                        if (Files.size(frame) > MAX_BYTES) throw invalid();
                        BufferedImage image = ImageIO.read(frame.toFile());
                        if (image == null || image.getWidth() > EDGE || image.getHeight() > EDGE) throw invalid();
                        int x = (i % 2) * EDGE, y = (i / 2) * (EDGE + 28);
                        graphics.drawImage(image, x + (EDGE - image.getWidth()) / 2, y, null);
                        graphics.setColor(Color.WHITE);
                        graphics.drawString(String.format(Locale.ROOT, "%.3f s", sample.timestampsMs().get(i) / 1000.0), x + 8, y + EDGE + 21);
                        Files.delete(frame);
                    }
                } finally { graphics.dispose(); }
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(sheet, "png", bytes);
                if (bytes.size() > MAX_BYTES) throw invalid();
                output.add(media("image/png", bytes.toByteArray()));
            }
            if (sample.audioStartMs() != null) {
                Path audio = directory.resolve("sample.wav");
                List<String> arguments = input(source, sample.audioStartMs());
                arguments.addAll(List.of("-t", seconds(sample.audioDurationMs()), "-map", "0:a:0", "-vn",
                        "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-fs", Integer.toString(MAX_BYTES), "-y", audio.toString()));
                tools.ffmpeg(arguments);
                if (Files.size(audio) <= 44 || Files.size(audio) > MAX_BYTES) throw invalid();
                output.add(media("audio/wav", Files.readAllBytes(audio)));
            }
            return List.copyOf(output);
        } catch (IOException failure) { throw new IllegalStateException("Media sample could not be decoded", failure); }
        finally {
            if (directory != null) try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            } catch (IOException failure) { throw new IllegalStateException("Media sample cleanup failed", failure); }
        }
    }

    private static List<String> input(Path source, int milliseconds) {
        return new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-threads", "1",
                "-protocol_whitelist", "file,pipe", "-ss", seconds(milliseconds), "-i", source.toString()));
    }
    private static String seconds(int value) { return String.format(Locale.ROOT, "%.3f", value / 1000.0); }
    private static Media media(String type, byte[] bytes) {
        return new Media(MimeTypeUtils.parseMimeType(type), new ByteArrayResource(bytes));
    }
    private static int integer(JsonNode value, int min, int max) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) throw invalid();
        return value.intValue();
    }
    private static ApiProblemException invalid() {
        var message = ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid");
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "TOOL_ARGUMENT_INVALID", message, message, false);
    }
}

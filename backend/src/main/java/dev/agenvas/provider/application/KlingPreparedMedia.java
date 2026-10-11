package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.RunningHubDefinition;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves model slots to exact authorized versions, never a live node or creator-provided URL. */
@Component
public class KlingPreparedMedia {
    private static final String PREFLIGHT_VIDEO_URL = "https://reference.example.invalid/" + "x".repeat(8000);
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaRelayService relay;
    private final MediaToolRunner tools;
    private final ObjectMapper mapper;

    public KlingPreparedMedia(ArtifactService artifacts, AssetService assets, MediaRelayService relay,
            MediaToolRunner tools, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.relay = relay; this.tools = tools; this.mapper = mapper;
    }

    public Map<String, String> load(AttemptContext context, KlingModels.Profile profile, JsonNode values, boolean submitting) {
        KlingModels.validateValues(profile, values, true);
        var images = FrozenMediaInputs.images(context.lease());
        var videos = FrozenMediaInputs.videos(context.lease());
        if (!FrozenMediaInputs.audios(context.lease()).isEmpty()) throw KlingProtocol.invalid();
        Map<UUID, FrozenMediaInputs.Image> imageVersions = references(images, "REFERENCE");
        Map<UUID, FrozenMediaInputs.Image> videoVersions = references(videos, "VIDEO_REFERENCE");
        Set<UUID> assigned = new HashSet<>();
        Map<String, String> result = new HashMap<>();
        for (var field : KlingModels.inputs(profile)) {
            if (!field.media() || !KlingModels.present(values, field.key())) continue;
            UUID version = UUID.fromString(values.path(field.key()).asText());
            if (!assigned.add(version)) throw KlingProtocol.invalid();
            boolean video = field.type() == RunningHubDefinition.FieldType.VIDEO;
            var reference = (video ? videoVersions : imageVersions).get(version);
            if (reference == null) throw KlingProtocol.invalid();
            Asset asset = asset(context, reference, video ? Asset.MediaKind.VIDEO : Asset.MediaKind.IMAGE);
            KlingModels.validateMetadata(profile, field.key(), asset, values);
            if (video) {
                UUID relayProfile = relayProfile(context, reference.order());
                relay.preflight(asset, relayProfile);
                validateVideo(context, profile, field.key(), values, asset);
                // Signing/uploading is outside database transactions and only happens before the first POST.
                result.put(field.key(), submitting
                        ? relay.signedVideo(context.ownerId(), context.lease().projectId(), asset.id(), relayProfile)
                        : PREFLIGHT_VIDEO_URL);
            } else result.put(field.key(), originalImage(context, profile, asset));
        }
        if (assigned.size() != imageVersions.size() + videoVersions.size()) throw KlingProtocol.invalid();
        return Map.copyOf(result);
    }

    private static Map<UUID, FrozenMediaInputs.Image> references(List<FrozenMediaInputs.Image> inputs, String role) {
        Map<UUID, FrozenMediaInputs.Image> versions = new HashMap<>();
        for (var input : inputs) if (!role.equals(input.role()) || versions.put(input.versionId(), input) != null)
            throw KlingProtocol.invalid();
        return versions;
    }

    private Asset asset(AttemptContext context, FrozenMediaInputs.Image reference, Asset.MediaKind kind) {
        var version = artifacts.requireVersion(context.ownerId(), context.lease().projectId(),
                reference.artifactId(), reference.versionId());
        return assets.requireReadyMedia(context.ownerId(), context.lease().projectId(),
                UUID.fromString(version.content().path("assetId").asText()), kind);
    }

    private String originalImage(AttemptContext context, KlingModels.Profile profile, Asset asset) {
        var file = assets.get(context.ownerId(), context.lease().projectId(), asset.id());
        int maximum = KlingModels.newVideo(profile) ? KlingProtocol.MAX_VIDEO_IMAGE_BYTES : KlingProtocol.MAX_IMAGE_BYTES;
        try (var source = Files.newInputStream(file.path())) {
            byte[] bytes = source.readNBytes(maximum + 1);
            if (bytes.length != asset.byteSize() || bytes.length > maximum
                    || !asset.sha256().equals(hash(bytes))) throw KlingProtocol.invalid();
            try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (imageInput == null) throw KlingProtocol.invalid();
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw KlingProtocol.invalid();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpg", "jpeg").contains(format)
                            || "png".equals(format) != "image/png".equals(asset.contentType())) throw KlingProtocol.invalid();
                    reader.setInput(imageInput, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    KlingProtocol.requireDimensions(width, height);
                    if (width != asset.width() || height != asset.height() || reader.read(0) == null) throw KlingProtocol.invalid();
                } finally { reader.dispose(); }
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException unavailable) { throw KlingProtocol.invalid(); }
    }

    private void validateVideo(AttemptContext context, KlingModels.Profile profile, String fieldKey, JsonNode values, Asset asset) {
        var file = assets.get(context.ownerId(), context.lease().projectId(), asset.id());
        try {
            if (Files.size(file.path()) != asset.byteSize() || !asset.sha256().equals(hash(file.path()))) throw KlingProtocol.invalid();
        } catch (IOException unavailable) { throw KlingProtocol.invalid(); }
        var probe = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type,width,height,avg_frame_rate:format=format_name,duration", "-of", "json", file.path().toString())));
        double seconds = probe.path("format").path("duration").asDouble(-1);
        String format = probe.path("format").path("format_name").asText();
        if (!format.contains("mov") && !format.contains("mp4") || !Double.isFinite(seconds)
                || seconds <= 0 || Math.abs(seconds * 1000 - asset.durationMs()) > 100) throw KlingProtocol.invalid();
        boolean found = false;
        for (var stream : probe.path("streams")) if ("video".equals(stream.path("codec_type").asText())) {
            if (found || stream.path("width").asInt(-1) != asset.width() || stream.path("height").asInt(-1) != asset.height())
                throw KlingProtocol.invalid();
            found = true;
            // Motion-control documents impose no fps range. Omni/O1 explicitly require 24–60 fps.
            if (!KlingModels.motion(profile)) {
                String[] rate = stream.path("avg_frame_rate").asText().split("/");
                double fps;
                try { fps = Double.parseDouble(rate[0]) / (rate.length == 2 ? Double.parseDouble(rate[1]) : 1); }
                catch (NumberFormatException invalid) { throw KlingProtocol.invalid(); }
                if (!Double.isFinite(fps) || fps < 24 || fps > 60) throw KlingProtocol.invalid();
            }
        }
        if (!found) throw KlingProtocol.invalid();
        // Validate the probed duration too: metadata rounding cannot admit an out-of-range source.
        var measured = new Asset(asset.id(), asset.projectId(), asset.mediaKind(), asset.objectKey(), asset.contentType(),
                asset.byteSize(), asset.sha256(), asset.width(), asset.height(), Math.toIntExact(Math.round(seconds * 1000)),
                asset.thumbnailKey(), asset.thumbnailByteSize(), asset.thumbnailSha256(), asset.createdAt());
        KlingModels.validateMetadata(profile, fieldKey, measured, values);
    }

    private static UUID relayProfile(AttemptContext context, int order) {
        var node = context.lease().input().path("mediaInput").path("videos").path(order).path("relayProfileId");
        return node.isMissingNode() || node.isNull() ? null : UUID.fromString(node.asText());
    }

    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static String hash(Path file) throws IOException {
        var digest = digest();
        try (var source = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}

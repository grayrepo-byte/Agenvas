package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.provider.domain.*;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Resolves only exact frozen, authorized versions and probes original bytes before a billable POST. */
@Component
public final class NativeMediaReferences {
    public record Input(Asset asset, JsonNode sourceContent, byte[] bytes, String url) {
        public String contentType() { return asset.contentType(); }
        public String base64() { return Base64.getEncoder().encodeToString(bytes); }
        public String dataUrl() { return "data:" + contentType() + ";base64," + base64(); }
    }
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaRelayService relay;
    private final MediaToolRunner tools;
    private final ObjectMapper mapper;
    public NativeMediaReferences(ArtifactService artifacts, AssetService assets, MediaRelayService relay,
            MediaToolRunner tools, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.relay = relay; this.tools = tools; this.mapper = mapper;
    }
    public Map<String, Input> load(AttemptContext context, NativeMediaProfiles.Selection selected, JsonNode values, boolean submitting) {
        NativeMediaProfiles.validateValues(selected, values, true);
        Map<UUID, FrozenMediaInputs.Image> versions = new HashMap<>();
        add(versions, FrozenMediaInputs.images(context.lease()), "REFERENCE");
        add(versions, FrozenMediaInputs.videos(context.lease()), "VIDEO_REFERENCE");
        add(versions, FrozenMediaInputs.audios(context.lease()), "AUDIO_REFERENCE");
        Map<String, Input> prepared = new LinkedHashMap<>();
        Map<UUID, String> assigned = new HashMap<>();
        for (var field : selected.inputs()) if (field.media() && NativeMediaProfiles.present(values, field.key())) {
            UUID id = UUID.fromString(values.path(field.key()).asText());
            var reference = versions.get(id);
            Asset.MediaKind kind = switch (field.type()) {
                case VIDEO -> Asset.MediaKind.VIDEO;
                case AUDIO -> Asset.MediaKind.AUDIO;
                default -> Asset.MediaKind.IMAGE;
            };
            String role = kind == Asset.MediaKind.IMAGE ? "REFERENCE" : kind == Asset.MediaKind.VIDEO ? "VIDEO_REFERENCE" : "AUDIO_REFERENCE";
            String previous = assigned.putIfAbsent(id, field.key());
            if (previous != null && !NativeMediaProfiles.canReuse(selected, previous, field.key())
                    || reference == null || !role.equals(reference.role())) throw NativeMediaProfiles.invalid();
            var version = artifacts.requireVersion(context.ownerId(), context.lease().projectId(), reference.artifactId(), id);
            selected.protocol().validateSource(selected.profile(), field.key(), version);
            Asset asset = assets.requireReadyMedia(context.ownerId(), context.lease().projectId(),
                    UUID.fromString(version.content().path("assetId").asText()), kind);
            selected.protocol().validateMetadata(selected.profile(), field.key(), asset, values);
            var file = assets.get(context.ownerId(), context.lease().projectId(), asset.id());
            byte[] bytes;
            int max = (kind == Asset.MediaKind.VIDEO ? 200 : 50) * 1024 * 1024;
            // URL-only audio/video references are hashed as streams; keep inline bytes only when needed.
            boolean inline = kind == Asset.MediaKind.IMAGE || !selected.protocol().usesMediaUrl(selected.profile(), field);
            try (var source = Files.newInputStream(file.path())) {
                if (asset.byteSize() < 1 || asset.byteSize() > max) throw NativeMediaProfiles.invalid();
                if (inline) {
                    bytes = source.readNBytes(max + 1);
                    if (bytes.length != asset.byteSize() || !asset.sha256().equals(hash(bytes))) throw NativeMediaProfiles.invalid();
                } else {
                    var digest = digest();
                    byte[] buffer = new byte[8192];
                    long count = 0; int read;
                    while ((read = source.read(buffer)) != -1) {
                        count += read;
                        if (count > max) throw NativeMediaProfiles.invalid();
                        digest.update(buffer, 0, read);
                    }
                    if (count != asset.byteSize() || !asset.sha256().equals(HexFormat.of().formatHex(digest.digest()))) throw NativeMediaProfiles.invalid();
                    bytes = new byte[0];
                }
            } catch (IOException unavailable) { throw NativeMediaProfiles.invalid(); }
            JsonNode probe = mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries",
                    "stream=codec_type,codec_name,pix_fmt,width,height,avg_frame_rate:format=format_name,duration", "-of", "json", file.path().toString())));
            validateProbe(asset, probe);
            // Decode complete raster files, including WebP, without changing their native format or alpha channel.
            if (kind == Asset.MediaKind.IMAGE) tools.ffmpeg(List.of("-v", "error", "-xerror", "-i", file.path().toString(), "-f", "null", "-"));
            selected.protocol().validateProbe(selected.profile(), field.key(), values, probe);
            prepared.put(field.key(), new Input(asset, version.content().deepCopy(), bytes, null));
        }
        if (assigned.size() != versions.size()) throw NativeMediaProfiles.invalid();
        Map<String, Asset> measured = new HashMap<>();
        prepared.forEach((slot, input) -> measured.put(slot, input.asset()));
        selected.protocol().validatePrepared(selected.profile(), values, measured);
        // Only after all validation succeeds may the original assets be exposed through temporary signatures.
        for (var field : selected.inputs()) if (prepared.containsKey(field.key()) && selected.protocol().usesMediaUrl(selected.profile(), field)) {
            Input input = prepared.get(field.key());
            var reference = versions.get(UUID.fromString(values.path(field.key()).asText()));
            Asset asset = input.asset();
            String group = asset.mediaKind() == Asset.MediaKind.IMAGE ? "images" : asset.mediaKind() == Asset.MediaKind.VIDEO ? "videos" : "audios";
            JsonNode pinned = context.lease().input().path("mediaInput").path(group).path(reference.order()).path("relayProfileId");
            UUID profileId = pinned.isMissingNode() || pinned.isNull() ? null : UUID.fromString(pinned.asText());
            relay.preflight(asset, profileId);
            String url = submitting ? relay.signedMedia(context.ownerId(), context.lease().projectId(), asset.id(), profileId, asset.mediaKind())
                    : "https://reference.example.invalid/" + "x".repeat(8000);
            prepared.put(field.key(), new Input(asset, input.sourceContent(), input.bytes(), url));
        }
        return Map.copyOf(prepared);
    }
    private static void add(Map<UUID, FrozenMediaInputs.Image> target, List<FrozenMediaInputs.Image> inputs, String role) {
        for (var input : inputs) if (!role.equals(input.role()) || target.put(input.versionId(), input) != null) throw NativeMediaProfiles.invalid();
    }
    private static void validateProbe(Asset asset, JsonNode probe) {
        String expected = asset.mediaKind() == Asset.MediaKind.AUDIO ? "audio" : "video";
        JsonNode stream = null;
        for (var entry : probe.path("streams")) if (expected.equals(entry.path("codec_type").asText())) {
            if (stream != null) throw NativeMediaProfiles.invalid();
            stream = entry;
        }
        if (stream == null) throw NativeMediaProfiles.invalid();
        if (asset.mediaKind() != Asset.MediaKind.AUDIO && (stream.path("width").asInt(-1) != asset.width()
                || stream.path("height").asInt(-1) != asset.height())) throw NativeMediaProfiles.invalid();
        if (asset.mediaKind() != Asset.MediaKind.IMAGE) {
            double seconds = probe.path("format").path("duration").asDouble(-1);
            if (!Double.isFinite(seconds) || seconds <= 0 || asset.durationMs() == null
                    || Math.abs(seconds * 1000 - asset.durationMs()) > 100) throw NativeMediaProfiles.invalid();
        } else {
            String codec = stream.path("codec_name").asText();
            String mime = switch (codec) { case "png" -> "image/png"; case "mjpeg" -> "image/jpeg"; case "webp" -> "image/webp"; case "bmp" -> "image/bmp"; default -> ""; };
            if (!mime.equals(asset.contentType())) throw NativeMediaProfiles.invalid();
        }
    }
    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}

package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MiniMaxH3Protocol;
import dev.agenvas.provider.infrastructure.MiniMaxH3Client.Reference;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Exact frozen bytes; video relay preparation happens before the first billable POST. */
@Component
public class MiniMaxH3ReferenceLoader {
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaRelayService relay;
    private final MediaToolRunner mediaTools;
    private final ObjectMapper mapper;

    public MiniMaxH3ReferenceLoader(ArtifactService artifacts, AssetService assets, MediaRelayService relay,
            MediaToolRunner mediaTools, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.relay = relay; this.mediaTools = mediaTools; this.mapper = mapper;
    }

    public List<Reference> load(AttemptContext context, boolean submitting) {
        var images = FrozenMediaInputs.images(context.lease());
        var videos = FrozenMediaInputs.videos(context.lease());
        var audios = FrozenMediaInputs.audios(context.lease());
        if (images.size() > 9 || videos.size() > 3 || audios.size() > 3) throw MiniMaxH3Protocol.invalid();
        String mode = context.lease().input().path("mediaInput").path("mode").asText();
        if (!Set.of("TEXT", "START_END", "GENERAL_REFERENCE").contains(mode)
                || "TEXT".equals(mode) && !(images.isEmpty() && videos.isEmpty() && audios.isEmpty())
                || !"GENERAL_REFERENCE".equals(mode) && !(videos.isEmpty() && audios.isEmpty())
                || "GENERAL_REFERENCE".equals(mode) && images.isEmpty() && videos.isEmpty() && audios.isEmpty()
                || "START_END".equals(mode) && images.stream().noneMatch(image -> "START_FRAME".equals(image.role())))
            throw MiniMaxH3Protocol.invalid();
        List<Reference> result = new ArrayList<>();
        for (var image : images) {
            String role = switch (image.role()) {
                case "START_FRAME" -> "first_frame";
                case "END_FRAME" -> "last_frame";
                case "REFERENCE" -> "reference_image";
                default -> throw MiniMaxH3Protocol.invalid();
            };
            if ("GENERAL_REFERENCE".equals(mode) != "reference_image".equals(role)) throw MiniMaxH3Protocol.invalid();
            result.add(inline(context, image, Asset.MediaKind.IMAGE, "image_url", role));
        }
        long totalAudio = 0;
        for (var audio : audios) {
            if (!"AUDIO_REFERENCE".equals(audio.role())) throw MiniMaxH3Protocol.invalid();
            Asset asset = asset(context, audio, Asset.MediaKind.AUDIO);
            totalAudio += asset.durationMs();
            result.add(inline(context, audio, Asset.MediaKind.AUDIO, "audio_url", "reference_audio"));
        }
        long totalVideo = 0;
        for (var video : videos) {
            if (!"VIDEO_REFERENCE".equals(video.role())) throw MiniMaxH3Protocol.invalid();
            Asset asset = asset(context, video, Asset.MediaKind.VIDEO);
            totalVideo += asset.durationMs();
            UUID profile = profile(context, video.order());
            relay.preflight(asset, profile);
            probeVideo(context, asset);
            result.add(new Reference("video_url", "reference_video", submitting
                    ? relay.signedVideo(context.ownerId(), context.lease().projectId(), asset.id(), profile)
                    : "https://reference.example.invalid/" + "x".repeat(8192)));
        }
        if (totalAudio > MiniMaxH3Protocol.MAX_REFERENCE_DURATION_MS || totalVideo > MiniMaxH3Protocol.MAX_REFERENCE_DURATION_MS)
            throw MiniMaxH3Protocol.invalid();
        return List.copyOf(result);
    }

    private Reference inline(AttemptContext context, FrozenMediaInputs.Image input, Asset.MediaKind kind, String type, String role) {
        Asset asset = asset(context, input, kind);
        var file = assets.get(context.ownerId(), context.lease().projectId(), asset.id());
        try (var stream = Files.newInputStream(file.path())) {
            byte[] bytes = stream.readNBytes(Math.toIntExact(asset.byteSize()) + 1);
            if (bytes.length != asset.byteSize()) throw MiniMaxH3Protocol.invalid();
            return new Reference(type, role, "data:" + ("audio/mpeg".equals(asset.contentType()) ? "audio/mp3" : asset.contentType()) + ";base64," + Base64.getEncoder().encodeToString(bytes));
        } catch (IOException failure) { throw MiniMaxH3Protocol.invalid(); }
    }

    private Asset asset(AttemptContext context, FrozenMediaInputs.Image input, Asset.MediaKind kind) {
        var version = artifacts.requireVersion(context.ownerId(), context.lease().projectId(), input.artifactId(), input.versionId());
        Asset asset = assets.requireReadyMedia(context.ownerId(), context.lease().projectId(),
                UUID.fromString(version.content().path("assetId").asText()), kind);
        MiniMaxH3Protocol.validateMetadata(asset);
        return asset;
    }

    private void probeVideo(AttemptContext context, Asset asset) {
        var file = assets.get(context.ownerId(), context.lease().projectId(), asset.id());
        var probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type,codec_name,avg_frame_rate", "-of", "json", file.path().toString())));
        boolean found = false;
        for (var stream : probe.path("streams")) {
            String type = stream.path("codec_type").asText(), codec = stream.path("codec_name").asText();
            if ("video".equals(type)) {
                found = true;
                String[] rate = stream.path("avg_frame_rate").asText().split("/");
                double fps;
                try { fps = Double.parseDouble(rate[0]) / (rate.length == 2 ? Double.parseDouble(rate[1]) : 1); }
                catch (NumberFormatException invalid) { throw MiniMaxH3Protocol.invalid(); }
                if (!Set.of("h264", "hevc").contains(codec) || !Double.isFinite(fps) || fps < 23.976 || fps > 60)
                    throw MiniMaxH3Protocol.invalid();
            } else if ("audio".equals(type) && !Set.of("aac", "mp3").contains(codec)) throw MiniMaxH3Protocol.invalid();
        }
        if (!found) throw MiniMaxH3Protocol.invalid();
    }

    private static UUID profile(AttemptContext context, int order) {
        var id = context.lease().input().path("mediaInput").path("videos").path(order).path("relayProfileId");
        return id.isMissingNode() || id.isNull() ? null : UUID.fromString(id.asText());
    }
}

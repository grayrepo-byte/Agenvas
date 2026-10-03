package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.SeedanceVideoReferences;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static dev.agenvas.provider.domain.MediaAdapterRegistry.*;

/** Probe exact frozen bytes before submitting; only signed URLs reach Ark. */
@Component
public class SeedanceVideoReferenceLoader {
    private final ArtifactService artifacts;
    private final AssetService assets;
    private final MediaRelayService relay;
    private final MediaToolRunner mediaTools;
    private final ObjectMapper mapper;
    public SeedanceVideoReferenceLoader(ArtifactService artifacts, AssetService assets, MediaRelayService relay,
            MediaToolRunner mediaTools, ObjectMapper mapper) {
        this.artifacts = artifacts; this.assets = assets; this.relay = relay; this.mediaTools = mediaTools; this.mapper = mapper;
    }
    public void preflight(AttemptContext context) {
        long total = 0;
        var videos = FrozenMediaInputs.videos(context.lease());
        if (videos.size() > SEEDANCE_MAX_REFERENCE_VIDEOS) throw SeedanceVideoReferences.invalid();
        for (var video : videos) {
            Asset asset = asset(context, video);
            SeedanceVideoReferences.validateMetadata(asset);
            total += asset.durationMs();
            relay.preflight(asset, profile(context, video));
            var file = assets.get(context.ownerId(), context.lease().projectId(), asset.id());
            var probe = mapper.readTree(mediaTools.ffprobe(List.of("-v", "error", "-show_entries",
                    "stream=codec_type,codec_name,avg_frame_rate", "-of", "json", file.path().toString())));
            boolean found = false;
            for (var stream : probe.path("streams")) {
                String type = stream.path("codec_type").asText();
                String codec = stream.path("codec_name").asText();
                if ("video".equals(type)) {
                    found = true;
                    String[] rate = stream.path("avg_frame_rate").asText().split("/");
                    double fps;
                    try { fps = Double.parseDouble(rate[0]) / (rate.length == 2 ? Double.parseDouble(rate[1]) : 1); }
                    catch (NumberFormatException invalid) { throw SeedanceVideoReferences.invalid(); }
                    if (!Set.of("h264", "hevc").contains(codec) || !Double.isFinite(fps)
                            || fps < SEEDANCE_MIN_VIDEO_FPS || fps > SEEDANCE_MAX_VIDEO_FPS) throw SeedanceVideoReferences.invalid();
                } else if ("audio".equals(type) && !Set.of("aac", "mp3").contains(codec)) throw SeedanceVideoReferences.invalid();
            }
            if (!found) throw SeedanceVideoReferences.invalid();
        }
        if (total > SEEDANCE_MAX_VIDEO_DURATION_MS) throw SeedanceVideoReferences.invalid();
    }
    public List<ArkSeedanceClient.Reference> load(AttemptContext context) {
        return FrozenMediaInputs.videos(context.lease()).stream().map(video -> {
            Asset asset = asset(context, video);
            return ArkSeedanceClient.Reference.video(relay.signedVideo(context.ownerId(), context.lease().projectId(),
                    asset.id(), profile(context, video)));
        }).toList();
    }
    private Asset asset(AttemptContext context, FrozenMediaInputs.Image video) {
        if (!"VIDEO_REFERENCE".equals(video.role())) throw SeedanceVideoReferences.invalid();
        var version = artifacts.requireVersion(context.ownerId(), context.lease().projectId(), video.artifactId(), video.versionId());
        return assets.requireReadyMedia(context.ownerId(), context.lease().projectId(),
                UUID.fromString(version.content().path("assetId").asText()), Asset.MediaKind.VIDEO);
    }
    private UUID profile(AttemptContext context, FrozenMediaInputs.Image video) {
        var id = context.lease().input().path("mediaInput").path("videos").path(video.order()).path("relayProfileId");
        return id.isNull() || id.isMissingNode() ? null : UUID.fromString(id.asText());
    }
}

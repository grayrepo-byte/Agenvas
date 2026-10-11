package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class KlingPreparedMediaTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final MediaRelayService relay = mock(MediaRelayService.class);
    private final MediaToolRunner tools = mock(MediaToolRunner.class);
    private final KlingPreparedMedia loader = new KlingPreparedMedia(artifacts, assets, relay, tools, mapper);
    private final ObjectNode input = mapper.createObjectNode();

    @Test void loadsOnlyAssignedAuthorizedOriginalImageVersionsAsRawBase64() throws Exception {
        var profile = KlingModels.resolve("kling-3.0-omni", "GENERATE", Task.Kind.VIDEO_GENERATION);
        var context = context(profile);
        var values = values(profile);
        byte[] first = png(300, 300), second = png(301, 300);
        var firstRef = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/png", first, 300, 300, null);
        var secondRef = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/png", second, 301, 300, null);
        // Frozen array order differs from explicit slot assignment. Slots must resolve by exact version UUID.
        values.put("first_frame", secondRef.version().toString()).put("image_1", firstRef.version().toString());
        var loaded = loader.load(context, profile, values, false);
        assertThat(loaded).containsEntry("first_frame", Base64.getEncoder().encodeToString(second))
                .containsEntry("image_1", Base64.getEncoder().encodeToString(first));
        assertThat(loaded.values()).allSatisfy(value -> assertThat(value).doesNotStartWith("data:"));
        verify(artifacts).requireVersion(owner, project, firstRef.artifact(), firstRef.version());
        verify(artifacts).requireVersion(owner, project, secondRef.artifact(), secondRef.version());
        verify(relay, never()).signedVideo(any(), any(), any(), any());
    }

    @Test void rejectsUnassignedDuplicateWrongRoleOrUnavailableVersions() throws Exception {
        var profile = KlingModels.resolve("kling-v3-omni", "GENERATE", Task.Kind.IMAGE_GENERATION);
        var context = context(profile);
        var values = values(profile);
        var image = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/png", png(300, 300), 300, 300, null);
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
        values.put("image_1", image.version().toString()).put("image_2", image.version().toString());
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
        values.remove("image_2");
        ((ObjectNode) input.at("/mediaInput/images/0")).put("role", "START_FRAME");
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.at("/mediaInput/images/0")).put("role", "REFERENCE");
        values.put("image_1", UUID.randomUUID().toString());
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
    }

    @Test void validatesOriginalHashActualEncodingAndActualDimensions() throws Exception {
        var profile = KlingModels.resolve("kling-v3", "GENERATE", Task.Kind.IMAGE_GENERATION);
        var context = context(profile);
        var values = values(profile);
        var image = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/png", png(300, 300), 300, 300, null);
        values.put("image_1", image.version().toString());
        Files.write(image.path(), png(301, 300));
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).putArray("images");
        var wrongMime = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/jpeg", png(300, 300), 300, 300, null);
        values.put("image_1", wrongMime.version().toString());
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).putArray("images");
        var wrongDimensions = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/png", png(300, 300), 301, 300, null);
        values.put("image_1", wrongDimensions.version().toString());
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
    }

    @Test void preflightPinsRelayWithoutUploadingAndSubmitSignsOriginalVideo() throws Exception {
        var profile = KlingModels.resolve("kling-3.0-omni", "GENERATE", Task.Kind.VIDEO_GENERATION);
        var context = context(profile);
        var values = values(profile).put("audio", "off");
        var video = reference("videos", "VIDEO_REFERENCE", Asset.MediaKind.VIDEO, "video/mp4", new byte[128], 700, 700, 5000);
        UUID pinnedRelay = UUID.randomUUID();
        ((ObjectNode) input.at("/mediaInput/videos/0")).put("relayProfileId", pinnedRelay.toString());
        values.put("feature_video", video.version().toString());
        probe(5, 700, 700, "24/1");
        var preflight = loader.load(context, profile, values, false);
        assertThat(preflight.get("feature_video")).startsWith("https://reference.example.invalid/");
        verify(relay).preflight(video.asset(), pinnedRelay);
        verify(relay, never()).signedVideo(any(), any(), any(), any());
        when(relay.signedVideo(owner, project, video.asset().id(), pinnedRelay)).thenReturn("https://public-storage.example/video.mp4?synthetic=signature");
        assertThat(loader.load(context, profile, values, true)).containsEntry("feature_video", "https://public-storage.example/video.mp4?synthetic=signature");
        verify(relay).signedVideo(owner, project, video.asset().id(), pinnedRelay);
    }

    @Test void enforcesOmniFpsButDoesNotInventTheSameMotionControlRestriction() throws Exception {
        var omni = KlingModels.resolve("kling-o1", "GENERATE", Task.Kind.VIDEO_GENERATION);
        var context = context(omni);
        var values = values(omni);
        var video = reference("videos", "VIDEO_REFERENCE", Asset.MediaKind.VIDEO, "video/mp4", new byte[128], 700, 700, 5000);
        values.put("feature_video", video.version().toString());
        probe(5, 700, 700, "12/1");
        assertThatThrownBy(() -> loader.load(context, omni, values, false)).isInstanceOf(ApiProblemException.class);
        var motion = KlingModels.resolve("kling-3.0", "MOTION_CONTROL", Task.Kind.VIDEO_GENERATION);
        var motionValues = values(motion).put("motion_video", video.version().toString());
        var image = reference("images", "REFERENCE", Asset.MediaKind.IMAGE, "image/png", png(300, 300), 300, 300, null);
        motionValues.put("image_1", image.version().toString());
        assertThat(loader.load(context, motion, motionValues, false)).containsKey("motion_video");
    }

    @Test void rejectsProbedDurationOutsideContractEvenWhenRoundedMetadataIsInside() throws Exception {
        var profile = KlingModels.resolve("kling-3.0-omni", "GENERATE", Task.Kind.VIDEO_GENERATION);
        var context = context(profile);
        var values = values(profile).put("audio", "off");
        var video = reference("videos", "VIDEO_REFERENCE", Asset.MediaKind.VIDEO, "video/mp4", new byte[128], 700, 700, 15500);
        values.put("feature_video", video.version().toString());
        probe(15.59, 700, 700, "24/1");
        assertThatThrownBy(() -> loader.load(context, profile, values, false)).isInstanceOf(ApiProblemException.class);
        verify(relay, never()).signedVideo(any(), any(), any(), any());
    }

    private AttemptContext context(KlingModels.Profile profile) {
        var media = input.putObject("mediaInput");
        media.putArray("images"); media.putArray("videos"); media.putArray("audios");
        Task task = mock(Task.class);
        when(task.projectId()).thenReturn(project); when(task.input()).thenReturn(input); when(task.kind()).thenReturn(profile.kind());
        return new AttemptContext(task, null, owner, "synthetic-request-key", null);
    }

    private ObjectNode values(KlingModels.Profile profile) {
        return KlingModels.effectiveValues(profile, mapper.createObjectNode(), "A synthetic landscape", null, mapper);
    }

    private Reference reference(String group, String role, Asset.MediaKind kind, String mime, byte[] bytes,
            int width, int height, Integer duration) throws Exception {
        UUID artifact = UUID.randomUUID(), version = UUID.randomUUID(), assetId = UUID.randomUUID();
        var inputs = (tools.jackson.databind.node.ArrayNode) input.path("mediaInput").path(group);
        inputs.addObject().put("artifactId", artifact.toString()).put("versionId", version.toString())
                .put("role", role).put("order", inputs.size() - 1);
        ArtifactVersion artifactVersion = mock(ArtifactVersion.class);
        when(artifactVersion.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireVersion(owner, project, artifact, version)).thenReturn(artifactVersion);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Asset asset = new Asset(assetId, project, kind, "synthetic", mime, bytes.length, hash,
                width, height, duration, null, null, null, Instant.now());
        Path file = directory.resolve(assetId.toString()); Files.write(file, bytes);
        when(assets.requireReadyMedia(owner, project, assetId, kind)).thenReturn(asset);
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, file));
        return new Reference(artifact, version, asset, file);
    }

    private void probe(double duration, int width, int height, String fps) {
        var probe = mapper.createObjectNode();
        probe.putObject("format").put("format_name", "mov,mp4").put("duration", Double.toString(duration));
        probe.putArray("streams").addObject().put("codec_type", "video").put("width", width).put("height", height)
                .put("avg_frame_rate", fps);
        when(tools.ffprobe(anyList())).thenReturn(mapper.writeValueAsString(probe));
    }

    private static byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    private record Reference(UUID artifact, UUID version, Asset asset, Path path) {}
}

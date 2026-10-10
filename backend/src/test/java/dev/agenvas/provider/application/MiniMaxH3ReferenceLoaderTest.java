package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reference preparation with frozen asset identities and synthetic probe data; no cloud traffic. */
class MiniMaxH3ReferenceLoaderTest {
    @TempDir Path root;
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final MediaRelayService relay = mock(MediaRelayService.class);
    private final MediaToolRunner tools = mock(MediaToolRunner.class);
    private final MiniMaxH3ReferenceLoader loader = new MiniMaxH3ReferenceLoader(artifacts, assets, relay, tools, mapper);
    private final ObjectNode input = mapper.createObjectNode();

    @Test void usesTheFrozenRelayProfileAndSignsOnlyAtSubmission() throws Exception {
        var context = context();
        Asset video = reference(Asset.MediaKind.VIDEO, "video/mp4", 2_000, 3, "VIDEO_REFERENCE");
        UUID profile = UUID.randomUUID();
        ((ObjectNode) input.at("/mediaInput/videos/0")).put("relayProfileId", profile.toString());
        when(tools.ffprobe(anyList())).thenReturn(probe("h264", "24/1", "aac"));
        when(relay.signedVideo(owner, project, video.id(), profile)).thenReturn("https://store.example.com/video.mp4?signature=synthetic");
        assertThat(loader.load(context, false)).singleElement().satisfies(ref -> {
            assertThat(ref.type()).isEqualTo("video_url");
            assertThat(ref.role()).isEqualTo("reference_video");
        });
        verify(relay, never()).signedVideo(owner, project, video.id(), profile);
        assertThat(loader.load(context, true).getFirst().url()).contains("signature=synthetic");
        verify(relay).signedVideo(owner, project, video.id(), profile);
    }

    @Test void rejectsUnsupportedVideoCodecsAudioCodecsAndFrameRatesBeforeSigning() throws Exception {
        var context = context();
        Asset video = reference(Asset.MediaKind.VIDEO, "video/mp4", 2_000, 3, "VIDEO_REFERENCE");
        for (String invalid : java.util.List.of(probe("vp9", "24/1", "aac"), probe("h264", "0/0", "aac"),
                probe("h264", "61/1", "aac"), probe("h264", "24/1", "opus"))) {
            when(tools.ffprobe(anyList())).thenReturn(invalid);
            assertThatThrownBy(() -> loader.load(context, true)).isInstanceOf(ApiProblemException.class);
        }
        verify(relay, never()).signedVideo(owner, project, video.id(), null);
    }

    @Test void sendsExactMp3BytesWithTheOfficialInlineMimeWithoutRequiringVisualInput() throws Exception {
        var context = context();
        reference(Asset.MediaKind.AUDIO, "audio/mpeg", 2_000, 3, "AUDIO_REFERENCE");
        assertThat(loader.load(context, true)).singleElement().satisfies(ref -> {
            assertThat(ref.type()).isEqualTo("audio_url");
            assertThat(ref.role()).isEqualTo("reference_audio");
            assertThat(ref.url()).isEqualTo("data:audio/mp3;base64,AQID");
        });
    }

    @Test void rejectsAggregateDurationAndChangedArchivedBytes() throws Exception {
        var context = context();
        reference(Asset.MediaKind.AUDIO, "audio/wav", 8_000, 3, "AUDIO_REFERENCE");
        reference(Asset.MediaKind.AUDIO, "audio/wav", 8_000, 3, "AUDIO_REFERENCE");
        assertThatThrownBy(() -> loader.load(context, true)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).putArray("audios");
        reference(Asset.MediaKind.IMAGE, "image/png", null, 2, "REFERENCE"); // Three stored bytes differ from the frozen size.
        assertThatThrownBy(() -> loader.load(context, true)).isInstanceOf(ApiProblemException.class);
    }

    private AttemptContext context() {
        ObjectNode media = input.putObject("mediaInput").put("mode", "GENERAL_REFERENCE");
        media.putArray("images"); media.putArray("audios"); media.putArray("videos");
        Task task = mock(Task.class);
        when(task.projectId()).thenReturn(project); when(task.input()).thenReturn(input);
        return new AttemptContext(task, null, owner, "synthetic-request", null);
    }

    private Asset reference(Asset.MediaKind kind, String mime, Integer duration, int declaredBytes, String role) throws Exception {
        UUID artifactId = UUID.randomUUID(), versionId = UUID.randomUUID(), assetId = UUID.randomUUID();
        var group = (tools.jackson.databind.node.ArrayNode) input.at("/mediaInput/" + switch (kind) {
            case IMAGE -> "images"; case AUDIO -> "audios"; case VIDEO -> "videos";
        });
        group.addObject().put("artifactId", artifactId.toString()).put("versionId", versionId.toString())
                .put("role", role).put("order", group.size() - 1);
        var version = mock(ArtifactVersion.class);
        when(version.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireVersion(owner, project, artifactId, versionId)).thenReturn(version);
        var asset = new Asset(assetId, project, kind, "synthetic", mime, declaredBytes, "a".repeat(64),
                kind == Asset.MediaKind.AUDIO ? null : 256, kind == Asset.MediaKind.AUDIO ? null : 256,
                duration, null, null, null, Instant.now());
        when(assets.requireReadyMedia(owner, project, assetId, kind)).thenReturn(asset);
        Path file = root.resolve(assetId.toString()); Files.write(file, new byte[] {1, 2, 3});
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, file));
        return asset;
    }

    private static String probe(String videoCodec, String rate, String audioCodec) {
        return "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"" + videoCodec + "\",\"avg_frame_rate\":\"" + rate
                + "\"},{\"codec_type\":\"audio\",\"codec_name\":\"" + audioCodec + "\"}]}";
    }
}

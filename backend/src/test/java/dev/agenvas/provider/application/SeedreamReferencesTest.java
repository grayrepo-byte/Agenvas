package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.asset.storage.MediaRelayService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.SeedreamModels;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exercises Seedream against the common exact-version loader, with synthetic local media. */
class SeedreamReferencesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;

    @Test void preservesOriginalPngBytesAndRejectsMutationOrUnboundSlot() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), artifact = UUID.randomUUID();
        UUID versionId = UUID.randomUUID(), assetId = UUID.randomUUID();
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), "png", output);
        byte[] bytes = output.toByteArray();
        Path file = directory.resolve("synthetic.png"); Files.write(file, bytes);
        Asset asset = new Asset(assetId, project, Asset.MediaKind.IMAGE, "synthetic", "image/png", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), 512, 512, null, null, null, null, Instant.EPOCH);
        ArtifactService artifacts = mock(ArtifactService.class);
        AssetService assets = mock(AssetService.class);
        MediaToolRunner tools = mock(MediaToolRunner.class);
        var loader = new NativeMediaReferences(artifacts, assets, mock(MediaRelayService.class), tools, mapper);
        var version = new ArtifactVersion(versionId, project, artifact, 4, 1, null, null,
                mapper.createObjectNode().put("assetId", assetId.toString()), List.of(), ArtifactVersion.CreatedByKind.USER, null, Instant.EPOCH);
        when(artifacts.requireVersion(owner, project, artifact, versionId)).thenReturn(version);
        when(assets.requireReadyMedia(owner, project, assetId, Asset.MediaKind.IMAGE)).thenReturn(asset);
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, file));
        when(tools.ffprobe(any())).thenReturn("{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"png\",\"pix_fmt\":\"rgba\",\"width\":512,\"height\":512}],\"format\":{\"format_name\":\"png_pipe\"}}");
        var selected = NativeMediaProfiles.resolve(SeedreamModels.ADAPTER_ID,
                mapper.createObjectNode().put("model", SeedreamModels.PRO).put("operation", SeedreamModels.GENERATE));
        ObjectNode values = mapper.createObjectNode().put("prompt", "Synthetic edit").put("image1", versionId.toString());
        ObjectNode input = mapper.createObjectNode();
        var media = input.putObject("mediaInput");
        media.putArray("images").addObject().put("artifactId", artifact.toString()).put("versionId", versionId.toString()).put("role", "REFERENCE").put("order", 0);
        media.putArray("videos"); media.putArray("audios");
        Task task = mock(Task.class); when(task.input()).thenReturn(input); when(task.projectId()).thenReturn(project);
        var context = new AttemptContext(task, new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                SeedreamModels.ADAPTER_ID, "a".repeat(64)), owner, "local-request", null);
        var loaded = loader.load(context, selected, values, false);
        assertThat(loaded.get("image1").bytes()).containsExactly(bytes);
        assertThat(loaded.get("image1").dataUrl()).startsWith("data:image/png;base64,");
        verify(artifacts).requireVersion(owner, project, artifact, versionId);
        values.put("image1", UUID.randomUUID().toString());
        assertThatThrownBy(() -> loader.load(context, selected, values, true)).isInstanceOf(RuntimeException.class);
        values.put("image1", versionId.toString());
        bytes[bytes.length - 1] ^= 1; Files.write(file, bytes);
        assertThatThrownBy(() -> loader.load(context, selected, values, true)).isInstanceOf(RuntimeException.class);
    }
}

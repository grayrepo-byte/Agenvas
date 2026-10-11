package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class KlingReferenceLoaderTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final KlingReferenceLoader loader = new KlingReferenceLoader(artifacts, assets);
    private final ObjectNode input = mapper.createObjectNode();

    @Test void sendsExactOriginalPngAsRawBase64ForAuthorizedVersion() throws Exception {
        var context = context("START_END");
        byte[] png = png();
        reference("START_FRAME", "image/png", png, 300, 300);
        assertThat(loader.load(context, true)).singleElement().satisfies(ref -> {
            assertThat(ref.role()).isEqualTo("START_FRAME");
            assertThat(ref.base64()).isEqualTo(Base64.getEncoder().encodeToString(png));
            assertThat(ref.toString()).doesNotContain(ref.base64());
        });
    }

    @Test void rejectsOnlyTailUnsupportedRolesAndNonImageReferences() throws Exception {
        var context = context("START_END");
        reference("END_FRAME", "image/png", png(), 300, 300);
        assertThatThrownBy(() -> loader.load(context, true)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).put("mode", "TEXT");
        assertThatThrownBy(() -> loader.load(context, true)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).putArray("images");
        ((ObjectNode) input.path("mediaInput")).putArray("videos").addObject().put("order", 0)
                .put("artifactId", UUID.randomUUID().toString()).put("versionId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> loader.load(context, true)).isInstanceOf(ApiProblemException.class);
    }

    @Test void validatesActualEncodingAndDecodedDimensionsBeforeSubmitting() throws Exception {
        var context = context("GENERAL_REFERENCE");
        reference("REFERENCE", "image/jpeg", png(), 300, 300);
        assertThatThrownBy(() -> loader.load(context, false)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).putArray("images");
        reference("REFERENCE", "image/png", png(), 301, 300);
        assertThatThrownBy(() -> loader.load(context, false)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) input.path("mediaInput")).putArray("images");
        reference("REFERENCE", "image/png", new byte[20], 300, 300);
        assertThatThrownBy(() -> loader.load(context, false)).isInstanceOf(ApiProblemException.class);
    }

    private AttemptContext context(String mode) {
        var media = input.putObject("mediaInput").put("mode", mode);
        media.putArray("images"); media.putArray("videos"); media.putArray("audios");
        Task task = mock(Task.class);
        when(task.projectId()).thenReturn(project); when(task.input()).thenReturn(input);
        return new AttemptContext(task, null, owner, "synthetic", null);
    }

    private void reference(String role, String mime, byte[] bytes, int width, int height) throws Exception {
        UUID artifact = UUID.randomUUID(), version = UUID.randomUUID(), assetId = UUID.randomUUID();
        var images = (tools.jackson.databind.node.ArrayNode) input.at("/mediaInput/images");
        images.addObject().put("artifactId", artifact.toString()).put("versionId", version.toString())
                .put("role", role).put("order", images.size() - 1);
        ArtifactVersion artifactVersion = mock(ArtifactVersion.class);
        when(artifactVersion.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireVersion(owner, project, artifact, version)).thenReturn(artifactVersion);
        Asset asset = new Asset(assetId, project, Asset.MediaKind.IMAGE, "synthetic", mime, bytes.length,
                "a".repeat(64), width, height, null, null, null, null, Instant.now());
        Path file = directory.resolve(assetId.toString()); Files.write(file, bytes);
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(asset, file));
    }

    private byte[] png() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }
}

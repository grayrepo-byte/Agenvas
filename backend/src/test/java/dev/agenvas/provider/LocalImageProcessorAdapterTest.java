package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.application.LocalImageProcessorAdapter;
import dev.agenvas.provider.application.LocalImageProperties;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class LocalImageProcessorAdapterTest {
    @TempDir Path temporary;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private Path sourceFile;

    @BeforeEach
    void sourceImage() throws Exception {
        sourceFile = temporary.resolve("source.png");
        BufferedImage source = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            source.setRGB(x, y, new Color(x * 40, y * 60, 100).getRGB());
        }
        ImageIO.write(source, "png", sourceFile.toFile());
        ObjectNode content = mapper.createObjectNode();
        content.put("sourceType", "UPLOAD");
        content.put("assetId", assetId.toString());
        ArtifactVersion version = new ArtifactVersion(versionId, projectId, artifactId, 1, 1,
                null, null, content, List.of(), ArtifactVersion.CreatedByKind.USER, null,
                Instant.now());
        when(artifacts.requireImageVersionForTask(ownerId, projectId, versionId))
                .thenReturn(version);
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.IMAGE, "source.png",
                "image/png", 1, "0".repeat(64), 4, 3, null, "thumb.png", 1L,
                "1".repeat(64), Instant.now());
        when(assets.get(ownerId, projectId, assetId))
                .thenReturn(new AssetService.AssetFile(asset, sourceFile));
    }

    @Test
    void upscalesAndReturnsPngWithoutNetwork() throws Exception {
        LocalImageProcessorAdapter adapter = adapter("");
        AttemptContext context = context("UPSCALE", parameters().put("scale", 2));

        Submission.Completed completed = (Submission.Completed) adapter.submit(context);
        try (var payload = completed.payload()) {
            BufferedImage output = ImageIO.read(payload.stream());
            assertThat(output.getWidth()).isEqualTo(8);
            assertThat(output.getHeight()).isEqualTo(6);
            assertThat(payload.declaredContentType()).isEqualTo("image/png");
        }
    }

    @Test
    void cropsNormalizedBounds() throws Exception {
        LocalImageProcessorAdapter adapter = adapter("");
        ObjectNode parameters = parameters().put("x", 0.25).put("y", 0.0)
                .put("width", 0.5).put("height", 1.0);

        Submission.Completed completed = (Submission.Completed) adapter.submit(
                context("CROP", parameters));
        try (var payload = completed.payload()) {
            BufferedImage output = ImageIO.read(payload.stream());
            assertThat(output.getWidth()).isEqualTo(2);
            assertThat(output.getHeight()).isEqualTo(3);
        }
    }

    @Test
    void missingDepthModelBlocksBeforeSubmission() {
        LocalImageProcessorAdapter adapter = adapter("");

        assertThat(adapter.preflightFailure(context("DEPTH_MAP", parameters())))
                .isEqualTo("LOCAL_DEPTH_MODEL_UNAVAILABLE");
    }

    @Test
    void runsConfiguredDepthAnythingModel() throws Exception {
        String model = System.getProperty("agenvas.test.depth-model", "");
        Assumptions.assumeTrue(!model.isBlank(), "No local depth model supplied");
        LocalImageProcessorAdapter adapter = adapter(model);

        assertThat(adapter.preflightFailure(context("DEPTH_MAP", parameters()))).isNull();
        Submission.Completed completed = (Submission.Completed) adapter.submit(
                context("DEPTH_MAP", parameters()));
        try (var payload = completed.payload()) {
            BufferedImage output = ImageIO.read(payload.stream());
            assertThat(output.getWidth()).isEqualTo(4);
            assertThat(output.getHeight()).isEqualTo(3);
            assertThat(output.getRaster().getNumBands()).isGreaterThanOrEqualTo(1);
        } finally {
            adapter.close();
        }
    }

    private LocalImageProcessorAdapter adapter(String depthModel) {
        return new LocalImageProcessorAdapter(artifacts, assets,
                new LocalImageProperties(depthModel));
    }

    private ObjectNode parameters() { return mapper.createObjectNode(); }

    private AttemptContext context(String operation, ObjectNode parameters) {
        ObjectNode input = mapper.createObjectNode();
        input.put("artifactId", artifactId.toString());
        ObjectNode imageOperation = input.putObject("imageOperation");
        imageOperation.put("name", operation);
        imageOperation.put("sourceVersionId", versionId.toString());
        imageOperation.set("parameters", parameters);
        Instant now = Instant.now();
        Task task = new Task(UUID.randomUUID(), projectId, null, UUID.randomUUID().toString(),
                Task.Kind.IMAGE_GENERATION, Task.Status.SUBMITTING, false, input, "0".repeat(64),
                null, null, 1, now, null, null, 1, 0, null, now, now, null);
        MediaCapabilityBinding binding = new MediaCapabilityBinding(UUID.randomUUID(), 1,
                UUID.randomUUID(), 1, "LOCAL_IMAGE_PROCESSOR", "1".repeat(64));
        return new AttemptContext(task, binding, ownerId, UUID.randomUUID().toString(), null);
    }
}

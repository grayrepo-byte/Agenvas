package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.infrastructure.KlingClient.Reference;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** Reads the exact authorized archived image bytes before any billable submission. */
@Component
public class KlingReferenceLoader {
    private final ArtifactService artifacts;
    private final AssetService assets;

    public KlingReferenceLoader(ArtifactService artifacts, AssetService assets) {
        this.artifacts = artifacts;
        this.assets = assets;
    }

    public List<Reference> load(AttemptContext context, boolean videoGeneration) {
        var task = context.lease();
        var inputs = FrozenMediaInputs.images(task);
        String mode = task.input().path("mediaInput").path("mode").asText();
        if (!FrozenMediaInputs.videos(task).isEmpty() || !FrozenMediaInputs.audios(task).isEmpty()
                || inputs.size() > (videoGeneration ? 2 : 1)) throw KlingProtocol.invalid();
        if (videoGeneration) {
            if (!Set.of("TEXT", "START_END").contains(mode)
                    || "TEXT".equals(mode) && !inputs.isEmpty()
                    || "START_END".equals(mode) && (inputs.isEmpty()
                        || inputs.stream().filter(image -> "START_FRAME".equals(image.role())).count() != 1
                        || inputs.stream().filter(image -> "END_FRAME".equals(image.role())).count() > 1))
                throw KlingProtocol.invalid();
        }
        List<Reference> references = new ArrayList<>();
        for (var input : inputs) {
            if (!(videoGeneration ? Set.of("START_FRAME", "END_FRAME") : Set.of("REFERENCE")).contains(input.role()))
                throw KlingProtocol.invalid();
            var version = artifacts.requireVersion(context.ownerId(), task.projectId(), input.artifactId(), input.versionId());
            UUID assetId = UUID.fromString(version.content().path("assetId").asText());
            var file = assets.get(context.ownerId(), task.projectId(), assetId);
            KlingProtocol.validateMetadata(file.asset(), videoGeneration);
            int maximum = videoGeneration ? KlingProtocol.MAX_VIDEO_IMAGE_BYTES : KlingProtocol.MAX_IMAGE_BYTES;
            try (var stream = Files.newInputStream(file.path())) {
                byte[] bytes = stream.readNBytes(maximum + 1);
                if (bytes.length != file.asset().byteSize() || bytes.length > maximum)
                    throw KlingProtocol.invalid();
                try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    if (imageInput == null) throw KlingProtocol.invalid();
                    var readers = ImageIO.getImageReaders(imageInput);
                    if (!readers.hasNext()) throw KlingProtocol.invalid();
                    var reader = readers.next();
                    try {
                        String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                        if (!Set.of("jpeg", "jpg", "png").contains(format)
                                || "png".equals(format) != "image/png".equals(file.asset().contentType()))
                            throw KlingProtocol.invalid();
                        reader.setInput(imageInput, true, true);
                        int width = reader.getWidth(0), height = reader.getHeight(0);
                        KlingProtocol.requireDimensions(width, height);
                        if (width != file.asset().width() || height != file.asset().height()
                                || reader.read(0) == null) throw KlingProtocol.invalid();
                    } finally { reader.dispose(); }
                }
                references.add(new Reference(input.role(), Base64.getEncoder().encodeToString(bytes)));
            } catch (IOException unreadable) { throw KlingProtocol.invalid(); }
        }
        return List.copyOf(references);
    }
}

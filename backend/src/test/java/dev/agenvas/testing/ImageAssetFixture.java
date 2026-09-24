package dev.agenvas.testing;

import dev.agenvas.asset.application.AssetService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Creates real, decoder-validated private bytes for integration tests of media references. */
public final class ImageAssetFixture {

    private ImageAssetFixture() {}

    /** Archives a tiny PNG through the production validation and metadata path. */
    public static UUID archive(AssetService assets, UUID ownerId, UUID projectId) {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG encoder unavailable in integration test");
            }
            return assets.archiveImage(ownerId, projectId,
                    new ByteArrayInputStream(output.toByteArray())).id();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create integration-test PNG", exception);
        }
    }
}

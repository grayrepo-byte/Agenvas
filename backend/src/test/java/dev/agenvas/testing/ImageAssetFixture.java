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
        return assets.archiveImage(ownerId, projectId, new ByteArrayInputStream(encoded("png", 2, 2))).id();
    }

    /** Real synthetic raster bytes; dimensions can deliberately exceed the thumbnail ceiling. */
    public static byte[] encoded(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(width - 1, height - 1, 0x38caca);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("Image encoder unavailable in integration test");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create integration-test PNG", exception);
        }
    }
}

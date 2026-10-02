package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MediaStyleThumbnailCodecTest {
    private final MediaStyleThumbnailCodec codec = new MediaStyleThumbnailCodec();

    @Test void decodesActualImageAndResizesRatherThanTrustingFilenameOrMime() throws Exception {
        BufferedImage image = new BufferedImage(1280, 960, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", original);
        byte[] png = codec.decode(new ByteArrayInputStream(original.toByteArray()));
        assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(resized.getWidth()).isEqualTo(640);
        assertThat(resized.getHeight()).isEqualTo(480);
    }

    @Test void rejectsEmptyUnsupportedAndOversizedUploads() {
        for (byte[] input : new byte[][] {new byte[0], "<svg><script/></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[MediaStyleThumbnailCodec.MAX_UPLOAD_BYTES + 1]}) {
            assertThatThrownBy(() -> codec.decode(new ByteArrayInputStream(input)))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class)
                    .satisfies(error -> assertThat(((dev.agenvas.shared.error.ApiProblemException) error).code())
                            .isEqualTo("MEDIA_STYLE_INVALID_THUMBNAIL"));
        }
    }

    @Test void rejectsPixelBombBeforeDecoding() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", encoded);
        byte[] png = encoded.toByteArray();
        // PNG IHDR width/height are checked by the reader before allocating the decoded bitmap.
        java.nio.ByteBuffer.wrap(png, 16, 8).putInt(10_000).putInt(10_000);
        assertThatThrownBy(() -> codec.decode(new ByteArrayInputStream(png)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
}

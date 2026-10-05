package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.project.domain.Project;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ComfyUiInputImageTest {
    @Test
    void retainsTheFixedImageTemplateDimensions() {
        assertThat(ComfyUiInputImage.imageDimensions(Project.AspectRatio.LANDSCAPE_16_9))
                .isEqualTo(new ComfyUiInputImage.Dimensions(1024, 576));
        assertThat(ComfyUiInputImage.imageDimensions(Project.AspectRatio.PORTRAIT_9_16))
                .isEqualTo(new ComfyUiInputImage.Dimensions(576, 1024));
        assertThat(ComfyUiInputImage.imageDimensions(Project.AspectRatio.SQUARE_1_1))
                .isEqualTo(new ComfyUiInputImage.Dimensions(768, 768));
    }

    @Test
    void centersTheFullReferenceWithoutStretchingOrCropping() throws IOException {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0xff0000);
        source.setRGB(1, 0, 0x0000ff);
        BufferedImage image = decode(ComfyUiInputImage.png(source, 8, 8));
        assertThat(image.getWidth()).isEqualTo(8);
        assertThat(image.getHeight()).isEqualTo(8);
        assertThat(image.getRGB(0, 0)).isEqualTo(0xff7f7f7f);
        assertThat(image.getRGB(7, 7)).isEqualTo(0xff7f7f7f);
        assertThat(image.getRGB(0, 3)).isEqualTo(0xffff0000);
        assertThat(image.getRGB(7, 3)).isEqualTo(0xff0000ff);
    }

    @Test
    void flattensTransparentPixelsOverTheSameNeutralBackground() throws IOException {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00ff0000);
        BufferedImage image = decode(ComfyUiInputImage.png(source, 4, 4));
        assertThat(image.getColorModel().hasAlpha()).isFalse();
        assertThat(image.getRGB(2, 2)).isEqualTo(0xff7f7f7f);
    }

    @Test
    void createsTheNeutralTextToImageCanvasWithoutAReference() throws IOException {
        BufferedImage image = decode(ComfyUiInputImage.png(null, 8, 4));
        assertThat(image.getWidth()).isEqualTo(8);
        assertThat(image.getHeight()).isEqualTo(4);
        assertThat(image.getRGB(0, 0)).isEqualTo(0xff7f7f7f);
        assertThat(image.getRGB(7, 3)).isEqualTo(0xff7f7f7f);
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}

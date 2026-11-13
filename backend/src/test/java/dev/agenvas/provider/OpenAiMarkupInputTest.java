package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.provider.domain.OpenAiMarkupInput;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class OpenAiMarkupInputTest {
    private byte[] overlay(int width, int height, boolean opaque, boolean empty) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (opaque)
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                    image.setRGB(x, y, 0xffff0000);
        if (!empty)
            image.setRGB(0, 0, 0xffff0000);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
    @Test
    void preservesOriginalDimensionsAndSeparateTransparentInk() throws Exception {
        var pair = OpenAiMarkupInput.prepare(ImageAssetFixture.encoded("png", 30, 10), overlay(30, 10, false, false));
        var source = ImageIO.read(new ByteArrayInputStream(pair.source()));
        var markup = ImageIO.read(new ByteArrayInputStream(pair.markup()));
        assertThat(source.getWidth()).isEqualTo(30);
        assertThat(source.getHeight()).isEqualTo(10);
        assertThat(markup.getRGB(0, 0)).isEqualTo(0xffff0000);
        assertThat(markup.getRGB(1, 0) >>> 24).isZero();
        assertThat(source.getRGB(0, 0)).isNotEqualTo(markup.getRGB(0, 0));
    }
    @Test
    void rejectsWrongSizeOpaqueEmptyAndNonPng() throws Exception {
        byte[] source = ImageAssetFixture.encoded("png", 30, 10);
        for (byte[] bad : new byte[][]{overlay(10, 30, false, false), overlay(30, 10, true, false),
                overlay(30, 10, false, true), ImageAssetFixture.encoded("jpg", 30, 10)})
            assertThatThrownBy(() -> OpenAiMarkupInput.prepare(source, bad))
                    .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void rejectsTooManyPixelsBeforeDecoding() throws Exception {
        assertThatThrownBy(() -> OpenAiMarkupInput.prepare(ImageAssetFixture.encoded("png", 4097, 4096),
                overlay(2, 2, false, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void rotatesExifJpegToMatchBrowserCoordinates() throws Exception {
        byte[] jpg = ImageAssetFixture.encoded("jpg", 30, 10);
        byte[] exif = {(byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 18,
                1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0};
        var bytes = new ByteArrayOutputStream();
        bytes.write(jpg, 0, 2);
        bytes.write(exif);
        bytes.write(jpg, 2, jpg.length - 2);
        var pair = OpenAiMarkupInput.prepare(bytes.toByteArray(), overlay(10, 30, false, false));
        var image = ImageIO.read(new ByteArrayInputStream(pair.source()));
        assertThat(image.getWidth()).isEqualTo(10);
        assertThat(image.getHeight()).isEqualTo(30);
    }
    @Test
    void orientsAndStripsOverlayExifBeforeMatchingTheSource() throws Exception {
        byte[] marked = overlay(3, 2, false, false);
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0};
        var out = new ByteArrayOutputStream();
        out.write(marked, 0, 33);
        var chunk = new DataOutputStream(out);
        chunk.writeInt(tiff.length);
        chunk.writeBytes("eXIf");
        chunk.write(tiff);
        var crc = new java.util.zip.CRC32();
        crc.update(new byte[]{'e', 'X', 'I', 'f'});
        crc.update(tiff);
        chunk.writeInt((int) crc.getValue());
        out.write(marked, 33, marked.length - 33);
        var pair = OpenAiMarkupInput.prepare(ImageAssetFixture.encoded("png", 2, 3), out.toByteArray());
        var image = ImageIO.read(new ByteArrayInputStream(pair.markup()));
        assertThat(image.getWidth()).isEqualTo(2);
        assertThat(image.getHeight()).isEqualTo(3);
        assertThat(image.getRGB(1, 0)).isEqualTo(0xffff0000);
        assertThat(new String(pair.markup(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("eXIf");
    }

}

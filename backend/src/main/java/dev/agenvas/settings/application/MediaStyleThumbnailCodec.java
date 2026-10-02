package dev.agenvas.settings.application;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Actual format and dimensions are checked before decoding; fresh PNG output omits metadata. */
@Component
public class MediaStyleThumbnailCodec {
    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    public static final int MAX_STORED_BYTES = 1024 * 1024;
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_SIDE = 640;
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "jpg", "webp");

    public byte[] decode(InputStream source) {
        try {
            byte[] bytes = source.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) throw invalid();
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw invalid();
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) throw invalid();
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw invalid();
                    double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(width, height));
                    BufferedImage preview = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                            Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = preview.createGraphics();
                    try {
                        graphics.setColor(java.awt.Color.WHITE);
                        graphics.fillRect(0, 0, preview.getWidth(), preview.getHeight());
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(decoded, 0, 0, preview.getWidth(), preview.getHeight(), null);
                    } finally { graphics.dispose(); }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!ImageIO.write(preview, "png", output) || output.size() > MAX_STORED_BYTES) throw invalid();
                    return output.toByteArray();
                } finally { reader.dispose(); }
            }
        } catch (IOException failure) { throw invalid(); }
    }

    private static dev.agenvas.shared.error.ApiProblemException invalid() {
        return MediaStyleService.problem(HttpStatus.BAD_REQUEST, "MEDIA_STYLE_INVALID_THUMBNAIL", MediaStyleService.Error.THUMBNAIL);
    }
}

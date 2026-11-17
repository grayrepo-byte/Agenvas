package dev.agenvas.referencecatalog.application;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.springframework.http.HttpStatus;

/** Limits apply before image allocation; file paths never accept a remote URL or Git traversal. */
public final class ReferenceCatalogValidation {
    public static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private ReferenceCatalogValidation() {
    }
    public static String path(String value) {
        if (value == null || value.length() > 512 || !value.matches("[A-Za-z0-9_./ -]+") || value.startsWith("/")
                || value.endsWith("/") || java.util.Arrays.stream(value.split("/", -1))
                        .anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw invalid();
        return value;
    }
    public static String ref(String value) {
        if (value == null || value.length() > 160 || !value.matches("[A-Za-z0-9_./-]+") || value.startsWith("/")
                || value.endsWith("/")
                || value.contains("..") || value.contains("//"))
            throw invalid();
        return value;
    }
    public static String slug(String value) {
        if (value == null || value.length() > 100 || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*") || value.equals(".")
                || value.equals(".."))
            throw invalid();
        return value;
    }
    public static void image(byte[] data, String expectedHash, long expectedSize, String expectedType) {
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES || data.length != expectedSize || expectedHash == null
                || !expectedHash.matches("[a-f0-9]{64}") || !hash(data).equals(expectedHash))
            throw invalid();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext())
                throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String actual = switch (format) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "webp" -> "image/webp";
                    default -> throw invalid();
                };
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!actual.equals(expectedType) || width <= 0 || height <= 0 || (long) width * height > 40_000_000L
                        || reader.getNumImages(true) != 1 || reader.read(0) == null)
                    throw invalid();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }
    public static String hash(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    public static ApiProblemException invalid() {
        return problem(HttpStatus.BAD_REQUEST, "REFERENCE_CATALOG_INVALID", "api.reference-catalog.invalid");
    }
    public static ApiProblemException problem(HttpStatus status, String code, String key) {
        return new ApiProblemException(status, code, ApiMessage.of(key), ApiMessage.of(key), false);
    }
}

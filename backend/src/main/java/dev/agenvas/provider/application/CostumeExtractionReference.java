package dev.agenvas.provider.application;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Versioned application-owned presentation reference, never an arbitrary client URL or file path.
 * Keep published resource versions unchanged and available when adding a later display standard.
 */
public final class CostumeExtractionReference {
    public static final String ID = "costume-display-v1";
    private static final String RESOURCE = "builtin-media/costume-display-v1.png";
    private CostumeExtractionReference() {
    }

    private static final class Loaded {
        private static final byte[] PNG = load();
        private static final String SHA256 = digest(PNG);
    }

    /** Called before acceptance takes database locks; the descriptor is part of the frozen input hash. */
    public static ObjectNode snapshot(ObjectMapper mapper) {
        return mapper.createObjectNode().put("id", ID).put("sha256", Loaded.SHA256);
    }

    /** A changed/missing built-in file fails preparation instead of silently changing a recovered task. */
    public static byte[] read(JsonNode frozen) {
        if (!ID.equals(frozen.path("id").asText()) || !Loaded.SHA256.equals(frozen.path("sha256").asText()))
            throw new IllegalArgumentException("Pinned costume display reference differs");
        return Loaded.PNG.clone();
    }

    private static byte[] load() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            byte[] bytes = input.readNBytes(5 * 1024 * 1024 + 1);
            if (bytes.length < 8 || bytes.length > 5 * 1024 * 1024
                    || bytes[0] != (byte) 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G')
                throw new IllegalStateException("Invalid built-in costume display PNG");
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("Built-in costume display reference unavailable", failure);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}

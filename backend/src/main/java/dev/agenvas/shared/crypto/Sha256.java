package dev.agenvas.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact text digests shared by idempotency records, frozen inputs and capability identities. */
public final class Sha256 {
    private static final String ALGORITHM = "SHA-256";

    private Sha256() {}

    /** Hashes the original UTF-8 text without normalization and returns lowercase hexadecimal. */
    public static String hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", unavailable);
        }
    }
}

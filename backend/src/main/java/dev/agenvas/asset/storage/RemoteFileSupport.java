package dev.agenvas.asset.storage;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;

/** Validation and bounded integrity checks shared by the two remote file protocols. */
final class RemoteFileSupport {
    private static final int BUFFER_BYTES = 64 * 1024;
    private RemoteFileSupport() {}
    static String endpoint(StorageProfile.Provider provider, String requested) {
        try {
            URI uri = URI.create(requested == null ? "" : requested.trim());
            String scheme = provider == StorageProfile.Provider.SMB ? "smb" : "https";
            if (!scheme.equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.toASCIIString().length() > 512) throw new IllegalArgumentException();
            String path = uri.getPath();
            for (String raw : uri.getRawPath().split("/", -1)) {
                if (raw.isEmpty()) continue;
                String segment = URI.create("/" + raw).getPath().substring(1);
                if (segment.equals(".") || segment.equals("..") || segment.contains("/")
                        || segment.contains("\\") || segment.contains("%") || segment.matches("(?s).*[\\p{Cntrl}:<>\"|?*].*"))
                    throw new IllegalArgumentException();
            }
            if (path.contains("//") || provider == StorageProfile.Provider.SMB && (path.isEmpty() || path.equals("/")))
                throw new IllegalArgumentException();
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (IllegalArgumentException failure) {
            throw StorageSettingsService.invalid(ApiMessage.of("api.remote-storage.invalid-endpoint"));
        }
    }
    static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_./-]+") || key.contains("..") || key.contains("//")
                || key.startsWith("/") || key.endsWith("/")) throw new IllegalArgumentException("Invalid archive key");
    }
    static boolean digestMatches(InputStream input, long size, String hash) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            long count = 0;
            int read;
            while ((read = input.read(buffer, 0, (int) Math.min(buffer.length, size - count + 1))) != -1) {
                count += read;
                if (count > size) return false;
                digest.update(buffer, 0, read);
            }
            return count == size && hash.equals(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    static ApiProblemException unavailable() {
        // Protocol exceptions include hosts, paths and usernames. Keep them out of public errors/logs.
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "REMOTE_STORAGE_UNAVAILABLE",
                ApiMessage.of("api.remote-storage.unavailable"), ApiMessage.of("api.remote-storage.check-connection"), true);
    }
    static ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "STORAGE_OBJECT_CONFLICT",
                ApiMessage.of("api.object-storage-client.archive-object-conflict"),
                ApiMessage.of("api.object-storage-client.the-object-content-is-different-from-the-archive-record-and"), false);
    }
}

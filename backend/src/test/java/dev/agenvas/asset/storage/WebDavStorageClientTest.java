package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDavStorageClientTest {
    @TempDir Path root;
    static StorageProfile profile() {
        return new StorageProfile(UUID.randomUUID(), "Synthetic DAV", StorageProfile.Provider.WEBDAV,
                "https://dav.example.com/remote.php/dav/files/demo", "", "", "agenvas", false, 1, null, "masked", Instant.EPOCH);
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private WebDavStorageClient client(FakeWebDavServer server) {
        var settings = mock(StorageSettingsService.class);
        when(settings.credentials(any())).thenReturn(new String[] { "synthetic-user", "synthetic-password" });
        return new WebDavStorageClient(settings, server.client());
    }
    @Test void immutablePublicationRecoveryHeadAndRangeUseTheBaseCollection() throws Exception {
        try (var server = new FakeWebDavServer()) {
            var client = client(server); var p = profile(); byte[] bytes = "synthetic-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path source = Files.write(root.resolve("source"), bytes);
            client.put(p, "archive/project/file.png", source, "image/png", bytes.length, hash(bytes));
            assertThat(server.files.keySet()).containsExactly("/remote.php/dav/files/demo/archive/project/file.png");
            int puts = server.puts.get(), gets = server.gets.get();
            client.verify(p, "archive/project/file.png", bytes.length, hash(bytes));
            assertThat(server.gets.get()).isEqualTo(gets);
            client.put(p, "archive/project/file.png", source, "image/png", bytes.length, hash(bytes));
            assertThat(server.puts.get()).isEqualTo(puts);
            try (var input = client.open(p, "archive/project/file.png", 2, 4, bytes.length)) { assertThat(input.readAllBytes()).isEqualTo(java.util.Arrays.copyOfRange(bytes, 2, 6)); }
            server.ignoreRange = true;
            try (var input = client.open(p, "archive/project/file.png", 2, 4, bytes.length)) { assertThat(input.readAllBytes()).isEqualTo(java.util.Arrays.copyOfRange(bytes, 2, 6)); }
            assertThatThrownBy(() -> client.put(p, "archive/project/file.png", source, "image/png", bytes.length, "0".repeat(64)))
                    .isInstanceOf(ApiProblemException.class).satisfies(e -> assertThat(((ApiProblemException)e).code()).isEqualTo("STORAGE_OBJECT_CONFLICT"));
            assertThat(server.puts.get()).isEqualTo(puts);
            client.delete(p, "archive/project/file.png"); client.delete(p, "archive/project/file.png");
            assertThat(server.files).isEmpty();
        }
    }
    @Test void lostMoveResponseRecoversWithoutOverwritingAndFailedUploadCleansStaging() throws Exception {
        try (var server = new FakeWebDavServer()) {
            var client = client(server); var p = profile(); byte[] bytes = { 1, 2, 3, 4 };
            Path source = Files.write(root.resolve("source"), bytes);
            server.failMoveAfterCommit = true;
            assertThatThrownBy(() -> client.put(p, "project/file.png", source, "image/png", 4, hash(bytes))).isInstanceOf(ApiProblemException.class);
            client.put(p, "project/file.png", source, "image/png", 4, hash(bytes));
            assertThat(server.puts.get()).isEqualTo(1);
            server.failPut = true;
            assertThatThrownBy(() -> client.put(p, "project/other.png", source, "image/png", 4, hash(bytes))).isInstanceOf(ApiProblemException.class);
            assertThat(server.files.keySet()).noneMatch(key -> key.contains(".upload-"));
        }
    }
    @Test void redirectAndUnsafeKeysNeverReachAnotherDestination() throws Exception {
        try (var server = new FakeWebDavServer()) {
            var client = client(server); var p = profile(); server.redirect = true;
            assertThatThrownBy(() -> client.matches(p, "project/file.png", 4, "hash")).isInstanceOf(ApiProblemException.class)
                    .hasMessageNotContaining("other.example.com");
            for (String key : new String[] { "../file", "/file", "a//b", "a\\b" })
                assertThatThrownBy(() -> WebDavStorageClient.url(p, key)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

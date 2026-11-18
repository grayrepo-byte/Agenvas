package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArchiveStorageClientTest {
    @TempDir Path temporary;
    private final MemoryBackend objects = new MemoryBackend(Set.of(StorageProfile.Provider.S3,
            StorageProfile.Provider.ALIYUN_OSS, StorageProfile.Provider.TENCENT_COS));
    private final MemoryBackend files = new MemoryBackend(Set.of(StorageProfile.Provider.SMB, StorageProfile.Provider.WEBDAV));

    @ParameterizedTest
    @EnumSource(StorageProfile.Provider.class)
    void everyArchiveOperationUsesTheBackendDeclaredForThePinnedProfile(StorageProfile.Provider provider) throws Exception {
        var router = new ArchiveStorageClient(List.of(objects, files));
        var profile = profile(provider);
        Path original = temporary.resolve("input.txt");
        Files.writeString(original, "synthetic");
        router.put(profile, "immutable", original, "text/plain", 9, "synthetic-hash");
        assertThat(router.matches(profile, "immutable", 9, "synthetic-hash")).isTrue();
        router.verify(profile, "immutable", 9, "synthetic-hash");
        try (var input = router.open(profile, "immutable", 1, 2, 9)) {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("yn");
        }
        assertThat((provider.supportsRelay() ? files : objects).bytes).isNull();
        router.delete(profile, "immutable");
        assertThat(router.matches(profile, "immutable", 9, "synthetic-hash")).isFalse();
    }
    @Test
    void ambiguousAndIncompleteAssemblyFailsBeforeStorageIo() {
        assertThatThrownBy(() -> new ArchiveStorageClient(List.of(objects, objects, files)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new ArchiveStorageClient(List.of(objects)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing");
        assertThatThrownBy(() -> new ArchiveStorageClient(List.of(objects, files, new MemoryBackend(Set.of()))))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void springDiscoversBackendsWithoutCollectingTheRouterAsItsOwnDependency() {
        new ApplicationContextRunner().withBean("objects", ArchiveStorageBackend.class, () -> objects)
                .withBean("files", ArchiveStorageBackend.class, () -> files)
                .withBean(ArchiveStorageClient.class).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ArchiveStorageClient.class);
                    assertThat(context.getBean(ArchiveStorageClient.class)).isNotNull();
                });
    }
    private static StorageProfile profile(StorageProfile.Provider provider) {
        return new StorageProfile(UUID.randomUUID(), "Synthetic", provider, "http://storage.example.test",
                "", "bucket", "", true, 1, null, "", Instant.EPOCH);
    }
    private static class MemoryBackend implements ArchiveStorageBackend {
        private final Set<StorageProfile.Provider> providers;
        byte[] bytes;
        String hash;
        MemoryBackend(Set<StorageProfile.Provider> providers) { this.providers = providers; }
        public Set<StorageProfile.Provider> providers() { return providers; }
        public void put(StorageProfile profile, String key, Path file, String mime, long size, String hash) {
            try { bytes = Files.readAllBytes(file); this.hash = hash; } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        public boolean matches(StorageProfile profile, String key, long size, String hash) {
            return bytes != null && bytes.length == size && this.hash.equals(hash);
        }
        public InputStream open(StorageProfile profile, String key, long start, long length, long total) {
            assertThat(total).isEqualTo(bytes.length);
            return new ByteArrayInputStream(Arrays.copyOfRange(bytes, (int) start, (int) (start + length)));
        }
        public void delete(StorageProfile profile, String key) { bytes = null; }
    }
}

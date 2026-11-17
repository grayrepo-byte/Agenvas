package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.referencecatalog.application.ReferenceCatalogCacheProperties;
import dev.agenvas.referencecatalog.application.ReferenceCatalogValidation;
import dev.agenvas.referencecatalog.infrastructure.ReferenceCatalogImageCache;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceCatalogImageCacheTest {
    @TempDir
    Path root;
    byte[] image() throws Exception {
        return Files.readAllBytes(Path.of("../examples/reference-pack/images/neutral-front.png"));
    }
    ReferenceCatalogImageCache cache(long quota) {
        return new ReferenceCatalogImageCache(new ReferenceCatalogCacheProperties(root.resolve("cache"), quota),
                new AssetProperties(root));
    }
    @Test
    void persistsAcrossInstancesAndOfflineReuseAndSerializesConcurrentMisses() throws Exception {
        byte[] image = image();
        String sha = ReferenceCatalogValidation.hash(image);
        var count = new AtomicInteger();
        var first = cache(100000);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<byte[]> get = () -> first.get(sha, image.length, "image/png", true, () -> {
            }, () -> {
                count.incrementAndGet();
                return image;
            });
            var a = pool.submit(get);
            var b = pool.submit(get);
            assertThat(a.get()).containsExactly(image);
            assertThat(b.get()).containsExactly(image);
        }
        assertThat(count.get()).isEqualTo(1);
        assertThat(cache(100000).get(sha, image.length, "image/png", false, () -> {
        }, () -> {
            throw new AssertionError("offline must not fetch");
        })).containsExactly(image);
        assertThat(cache(100000).stats().fileCount()).isEqualTo(1);
        assertThat(cache(100000).stats().usedBytes()).isEqualTo(image.length);
    }
    @Test
    void authorizationRunsForCachedReadsAndCorruptionCannotBeServed() throws Exception {
        byte[] image = image();
        String sha = ReferenceCatalogValidation.hash(image);
        var cache = cache(100000);
        cache.get(sha, image.length, "image/png", true, () -> {
        }, () -> image);
        assertThatThrownBy(() -> cache.get(sha, image.length, "image/png", false, () -> {
            throw new IllegalStateException("revoked");
        }, () -> image)).hasMessage("revoked");
        Files.write(root.resolve("cache").resolve(sha + ".image"), new byte[]{1, 2, 3});
        assertThatThrownBy(() -> cache.get(sha, image.length, "image/png", false, () -> {
        }, () -> image)).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_CORRUPT");
        assertThat(cache.get(sha, image.length, "image/png", true, () -> {
        }, () -> image)).containsExactly(image);
    }
    @Test
    void quotaDiskFailureAndSymlinkDoNotPublishWrongBytes() throws Exception {
        byte[] image = image();
        String sha = ReferenceCatalogValidation.hash(image);
        assertThatThrownBy(() -> cache(1).get(sha, image.length, "image/png", true, () -> {
        }, () -> image)).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_FULL");
        assertThat(cache(1).stats().fileCount()).isZero();
        Path outside = Files.write(root.resolve("outside.png"), image);
        Files.createSymbolicLink(root.resolve("cache").resolve(sha + ".image"), outside);
        assertThatThrownBy(() -> cache(100000).get(sha, image.length, "image/png", true, () -> {
        }, () -> image)).isInstanceOf(RuntimeException.class);
        assertThat(Files.readAllBytes(outside)).containsExactly(image);
        var failed = new ReferenceCatalogImageCache(
                new ReferenceCatalogCacheProperties(outside.resolve("cache"), 100000), new AssetProperties(root));
        assertThatThrownBy(() -> failed.get(sha, image.length, "image/png", true, () -> {
        }, () -> image)).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_STORAGE_FAILED");
    }
    @Test
    void conflictingCallerMetadataCannotEvictChecksumValidSharedBytes() throws Exception {
        byte[] image = image();
        String sha = ReferenceCatalogValidation.hash(image);
        var cache = cache(100000);
        cache.get(sha, image.length, "image/png", true, () -> {
        }, () -> image);
        assertThatThrownBy(() -> cache.get(sha, image.length + 1, "image/png", true, () -> {
        }, () -> image)).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_METADATA_CONFLICT");
        assertThat(cache.get(sha, image.length, "image/png", false, () -> {
        }, () -> {
            throw new AssertionError("valid cache must survive metadata conflict");
        })).containsExactly(image);
        assertThatThrownBy(() -> cache.get(sha, image.length, "image/jpeg", true, () -> {
        }, () -> image)).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_METADATA_CONFLICT");
        assertThat(cache.get(sha, image.length, "image/png", false, () -> {
        }, () -> {
            throw new AssertionError();
        })).containsExactly(image);
        assertThat(cache.stats().usedBytes()).isEqualTo(image.length);
        assertThat(cache.stats().fileCount()).isEqualTo(1);
    }
    @Test
    void readOnlyMissDoesNotCreateDirectoriesOrDownload() throws Exception {
        var cache = cache(100000);
        byte[] image = image();
        String sha = ReferenceCatalogValidation.hash(image);
        assertThatThrownBy(() -> cache.get(sha, image.length, "image/png", false, () -> {
        }, () -> {
            throw new AssertionError();
        })).extracting("code").isEqualTo("REFERENCE_CATALOG_CACHE_MISS");
        assertThat(Files.exists(root.resolve("cache"))).isFalse();
    }
}

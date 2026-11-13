package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.application.AssetProperties;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Real decoder-supported bytes protect recovery and cleanup after a prepared install. */
class LocalAssetStorageRecoveryTest {
    private static final byte[] WEBP = Base64.getDecoder().decode(
            "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoEAAQAAkA4JaQAA3AA/vucwAA=");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoversInstalledWebpAndRebuildsOnlyAMissingThumbnail(boolean keepThumbnail) throws Exception {
        var storage = storage();
        var stored = storage.storeImage(PROJECT, ASSET, new ByteArrayInputStream(WEBP));
        if (!keepThumbnail)
            Files.delete(storage.checkedPath(stored.thumbnailKey()));

        assertThat(storage.recoverImage(PROJECT, ASSET)).contains(stored);
        assertThat(Files.readAllBytes(storage.checkedPath(stored.objectKey()))).isEqualTo(WEBP);
        assertThat(Files.isRegularFile(storage.checkedPath(stored.thumbnailKey()))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {".png", ".jpg"})
    void refusesAmbiguousOriginalsWithoutChangingAnyFile(String otherExtension) throws Exception {
        var storage = storage();
        var stored = storage.storeImage(PROJECT, ASSET, new ByteArrayInputStream(WEBP));
        Path other = root.resolve(PROJECT.toString()).resolve(ASSET + otherExtension);
        Files.write(other, new byte[]{1, 2, 3});

        assertThatThrownBy(() -> storage.recoverImage(PROJECT, ASSET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting task image files require investigation");
        assertThat(Files.readAllBytes(storage.checkedPath(stored.objectKey()))).isEqualTo(WEBP);
        assertThat(Files.readAllBytes(other)).containsExactly(1, 2, 3);
    }

    @Test
    void refusesANonRegularWebpPath() throws Exception {
        var storage = storage();
        Files.createDirectories(root.resolve(PROJECT.toString()).resolve(ASSET + ".webp"));
        assertThatThrownBy(() -> storage.recoverImage(PROJECT, ASSET))
                .isInstanceOf(IllegalStateException.class).hasMessage("Task image path is not a regular file");
    }

    @Test
    void discardsPreparedWebpAndPreviewWithoutTouchingAnotherAsset() throws Exception {
        var storage = storage();
        var stored = storage.storeImage(PROJECT, ASSET, new ByteArrayInputStream(WEBP));
        Path other = root.resolve(PROJECT.toString()).resolve("other.webp");
        Files.write(other, WEBP);

        storage.discardPreparedImage(PROJECT, ASSET);

        assertThat(storage.checkedPath(stored.objectKey())).doesNotExist();
        assertThat(storage.checkedPath(stored.thumbnailKey())).doesNotExist();
        assertThat(Files.readAllBytes(other)).isEqualTo(WEBP);
    }

    private LocalAssetStorage storage() {
        return new LocalAssetStorage(new AssetProperties(root), null, new ObjectMapper());
    }
}

package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.GifFixture;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LocalAssetStorageGifTest {
    @TempDir Path root;
    private final UUID project = UUID.randomUUID(), asset = UUID.randomUUID();

    @Test void archivesAndRecoversAnimationWithoutChangingFramesTimingLoopOrBytes() throws Exception {
        var storage = storage();
        byte[] animation = GifFixture.animation(2);
        var stored = storage.storeImage(project, asset, new ByteArrayInputStream(animation));
        assertThat(stored.contentType()).isEqualTo("image/gif");
        assertThat(stored.objectKey()).endsWith(".gif");
        assertThat(stored.width()).isEqualTo(8);
        assertThat(stored.height()).isEqualTo(8);
        assertThat(Files.readAllBytes(storage.checkedPath(stored.objectKey()))).isEqualTo(animation);
        try (var input = ImageIO.createImageInputStream(storage.checkedPath(stored.objectKey()).toFile())) {
            var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                assertThat(reader.getNumImages(true)).isEqualTo(2);
                assertThat(reader.read(0).getRGB(0, 0) & 0xffffff).isEqualTo(0xff0000);
                assertThat(reader.read(1).getRGB(0, 0) & 0xffffff).isEqualTo(0x00ff00);
            } finally { reader.dispose(); }
        }
        Files.delete(storage.checkedPath(stored.thumbnailKey()));
        assertThat(storage.recoverImage(project, asset)).contains(stored);
        assertThat(Files.readAllBytes(storage.checkedPath(stored.objectKey()))).isEqualTo(animation);
        assertThat(ImageIO.read(storage.checkedPath(stored.thumbnailKey()).toFile())).isNotNull();
        storage.discardPreparedImage(project, asset);
        assertThat(storage.checkedPath(stored.objectKey())).doesNotExist();
    }

    @Test void rejectsTooManyFramesBeforeInstallingAnArchive() throws Exception {
        assertThatThrownBy(() -> storage().storeImage(project, asset, new ByteArrayInputStream(GifFixture.animation(257))))
                .isInstanceOf(ApiProblemException.class);
        assertNoInstalledFiles();
    }

    @Test void boundsLogicalScreenAndCumulativeDecodedCanvasPixels() throws Exception {
        for (int side : new int[] {65535, 2600}) {
            byte[] animation = GifFixture.animation(25);
            animation[6] = animation[8] = (byte) (side & 255);
            animation[7] = animation[9] = (byte) (side >> 8);
            assertThatThrownBy(() -> storage().storeImage(project, asset, new ByteArrayInputStream(animation)))
                    .isInstanceOf(ApiProblemException.class);
            assertNoInstalledFiles();
        }
    }

    @Test void rejectsTruncationAndACorruptLaterFrameInsteadOfAcceptingOnlyTheFirst() throws Exception {
        byte[] animation = GifFixture.animation(2);
        byte[] truncated = java.util.Arrays.copyOf(animation, animation.length - 1);
        assertThatThrownBy(() -> storage().storeImage(project, asset, new ByteArrayInputStream(truncated)))
                .isInstanceOf(ApiProblemException.class);
        assertNoInstalledFiles();
        int descriptor = -1;
        for (int index = 13; index < animation.length - 10; index++) {
            if (animation[index] == 0x2c && animation[index + 5] == 8 && animation[index + 7] == 8) descriptor = index;
        }
        assertThat(descriptor).isPositive();
        int packed = animation[descriptor + 9] & 255;
        int localTableBytes = (packed & 128) != 0 ? 3 * (2 << (packed & 7)) : 0;
        animation[descriptor + 10 + localTableBytes] = (byte) 255;
        assertThatThrownBy(() -> storage().storeImage(project, asset, new ByteArrayInputStream(animation)))
                .isInstanceOf(ApiProblemException.class);
        assertNoInstalledFiles();
    }

    private void assertNoInstalledFiles() throws Exception {
        try (var files = Files.walk(root)) { assertThat(files.filter(Files::isRegularFile)).isEmpty(); }
    }

    private LocalAssetStorage storage() { return new LocalAssetStorage(new AssetProperties(root), null, new ObjectMapper()); }
}

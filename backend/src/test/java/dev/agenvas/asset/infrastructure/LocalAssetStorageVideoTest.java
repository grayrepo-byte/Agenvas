package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Synthetic real MP4s verify the common upload/provider archive and its recovery path. */
class LocalAssetStorageVideoTest {
    @TempDir Path root;
    private final MediaToolRunner tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));

    @ParameterizedTest
    @ValueSource(ints = {61, 148, 600})
    void archivesAndRecoversLongVideosWithoutChangingOriginalBytes(int seconds) throws Exception {
        Path source = video(seconds);
        var storage = storage();
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        LocalAssetStorage.StoredVideo archived;
        try (var input = Files.newInputStream(source)) {
            archived = storage.storeVideo(project, asset, input);
        }
        assertThat(archived.durationMs()).isEqualTo(seconds * 1000);
        assertThat(Files.mismatch(source, storage.checkedPath(archived.objectKey()))).isEqualTo(-1);
        Files.delete(storage.checkedPath(archived.thumbnailKey()));
        assertThat(storage.recoverVideo(project, asset)).contains(archived);
    }

    @Test
    void rejectsVideosBeyondTenMinutesBeforeInstallingAnyArchive() throws Exception {
        Path source = video(601);
        var storage = storage();
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        try (var input = Files.newInputStream(source)) {
            assertThatThrownBy(() -> storage.storeVideo(project, asset, input))
                    .isInstanceOfSatisfying(ApiProblemException.class,
                            failure -> assertThat(failure.code()).isEqualTo("ASSET_INVALID_VIDEO"));
        }
        try (var files = Files.list(root.resolve("archive").resolve(project.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    private LocalAssetStorage storage() {
        return new LocalAssetStorage(new AssetProperties(root.resolve("archive")), tools, new ObjectMapper());
    }

    private Path video(int seconds) {
        Path output = root.resolve("synthetic-" + seconds + ".mp4");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
                "-i", "color=c=blue:s=16x16:r=1", "-t", Integer.toString(seconds), "-an",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-y", output.toString()));
        return output;
    }
}

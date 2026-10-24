package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.application.MediaToolsProperties;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Real MP3s distinguish decoded audio duration from encoder/container padding. */
class LocalAssetStorageAudioTest {
    @TempDir Path root;
    private final MediaToolRunner tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));

    @Test
    void archivesAndRecoversTenMinuteMp3WithoutChangingOriginalBytes() throws Exception {
        Path source = mp3("600");
        var storage = storage();
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        LocalAssetStorage.StoredAudio archived;
        try (var input = Files.newInputStream(source)) {
            archived = storage.storeAudio(project, asset, input);
        }
        assertThat(archived.durationMs()).isEqualTo(600_000);
        assertThat(archived.contentType()).isEqualTo("audio/mpeg");
        assertThat(Files.mismatch(source, storage.checkedPath(archived.objectKey()))).isEqualTo(-1);
        assertThat(storage.recoverAudio(project, asset)).contains(archived);
        try (var files = Files.list(root.resolve("archive").resolve(project.toString()))) {
            assertThat(files).containsExactly(storage.checkedPath(archived.objectKey()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.099", "600.001", "602"})
    void rejectsDecodedAudioOutsideBoundsWithoutInstallingAnyArchive(String seconds) throws Exception {
        try (var input = Files.newInputStream(mp3(seconds))) {
            assertInvalidAndEmptyArchive(input);
        }
    }

    @Test
    void rejectsMalformedAudioWithoutInstallingAnyArchive() throws Exception {
        assertInvalidAndEmptyArchive(new ByteArrayInputStream(new byte[] {0, 1, 2, 3}));
    }

    private void assertInvalidAndEmptyArchive(InputStream input) throws Exception {
        UUID project = UUID.randomUUID();
        var storage = storage();
        assertThatThrownBy(() -> storage.storeAudio(project, UUID.randomUUID(), input))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ASSET_INVALID_AUDIO"));
        try (var files = Files.list(root.resolve("archive").resolve(project.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    private LocalAssetStorage storage() {
        return new LocalAssetStorage(new AssetProperties(root.resolve("archive")), tools, new ObjectMapper());
    }

    private Path mp3(String seconds) {
        Path output = root.resolve("synthetic-" + seconds + ".mp3");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
                "-i", "sine=frequency=440:sample_rate=48000", "-t", seconds,
                "-c:a", "libmp3lame", "-b:a", "192k", "-y", output.toString()));
        return output;
    }
}

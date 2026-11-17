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
    @TempDir
    Path root;
    private final MediaToolRunner tools = new MediaToolRunner(new MediaToolsProperties(null, null, null));

    @ParameterizedTest
    @ValueSource(ints = {61, 148, 600})
    void archivesAndRecoversLongVideosWithoutChangingOriginalBytes(int seconds) throws Exception {
        Path source = video(seconds);
        var storage = storage();
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        LocalAssetStorage.StoredVideo archived;
        try (var input = Files.newInputStream(source)) {
            archived = storage.storeUploadedVideo(project, asset, input);
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

    @Test
    void importsStreamingWebmWithoutDurationAsRealMp4() throws Exception {
        Path source = webm(2, "webm", "16x16");
        assertThat(tools.ffprobe(List.of("-v", "error", "-show_entries", "format=duration",
                "-of", "json", source.toString()))).doesNotContain("duration");
        var storage = storage();
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        LocalAssetStorage.StoredVideo archived;
        try (var input = Files.newInputStream(source)) {
            archived = storage.storeUploadedVideo(project, asset, input);
        }
        assertThat(archived.durationMs()).isEqualTo(2000);
        assertThat(archived.objectKey()).endsWith(".mp4");
        assertThat(tools.ffprobe(List.of("-v", "error", "-show_entries", "format=format_name:stream=codec_name",
                "-of", "json", storage.checkedPath(archived.objectKey()).toString())))
                .contains("mp4", "h264").doesNotContain("vp8");
        assertThat(archived.byteSize()).isEqualTo(Files.size(storage.checkedPath(archived.objectKey())));
        assertThat(storage.recoverVideo(project, asset)).contains(archived);
    }

    @Test
    void keepsProviderArchiveMp4Only() throws Exception {
        Path source = webm(2, "webm", "16x16");
        try (var input = Files.newInputStream(source)) {
            assertThatThrownBy(() -> storage().storeVideo(UUID.randomUUID(), UUID.randomUUID(), input))
                    .isInstanceOf(ApiProblemException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "large", "matroska"})
    void rejectsUnsupportedWebmWithoutInstallingAnything(String kind) throws Exception {
        Path source = webm(kind.equals("long") ? 122 : 2,
                kind.equals("matroska") ? "matroska" : "webm", kind.equals("large") ? "962x16" : "16x16");
        UUID project = UUID.randomUUID();
        try (var input = Files.newInputStream(source)) {
            assertThatThrownBy(() -> storage().storeUploadedVideo(project, UUID.randomUUID(), input))
                    .isInstanceOf(ApiProblemException.class);
        }
        try (var files = Files.list(root.resolve("archive").resolve(project.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "agenvas.test.directorWebm", matches = ".+")
    void importsActualBrowserDirectorWebm() throws Exception {
        Path source = Path.of(System.getProperty("agenvas.test.directorWebm"));
        var storage = storage();
        UUID project = UUID.randomUUID(), asset = UUID.randomUUID();
        try (var input = Files.newInputStream(source)) {
            var archived = storage.storeUploadedVideo(project, asset, input);
            assertThat(archived.durationMs()).isBetween(1, 120_000);
            assertThat(archived.width()).isBetween(1, 960);
            assertThat(archived.height()).isBetween(1, 960);
            assertThat(storage.recoverVideo(project, asset)).contains(archived);
            System.out.println("Director browser WebM archived as MP4: " + archived.width() + "x"
                    + archived.height() + ", " + archived.durationMs() + " ms, " + archived.byteSize() + " bytes");
        }
    }

    @Test
    void rejectsWebmWithAudioRatherThanSilentlyDroppingIt() throws Exception {
        Path source = root.resolve("audio.webm");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
                "-i", "testsrc2=s=16x16:r=2", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "1", "-c:v", "libvpx", "-c:a", "libopus", "-y", source.toString()));
        UUID project = UUID.randomUUID();
        try (var input = Files.newInputStream(source)) {
            assertThatThrownBy(() -> storage().storeUploadedVideo(project, UUID.randomUUID(), input))
                    .isInstanceOf(ApiProblemException.class);
        }
        try (var files = Files.list(root.resolve("archive").resolve(project.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void acceptsRecorderTailWithoutTruncatingTheMotion() throws Exception {
        Path source = webm(120.5, "webm", "16x16");
        try (var input = Files.newInputStream(source)) {
            var archived = storage().storeUploadedVideo(UUID.randomUUID(), UUID.randomUUID(), input);
            assertThat(archived.durationMs()).isEqualTo(120_500);
        }
    }

    private Path webm(double seconds, String format, String dimensions) {
        Path output = root.resolve("synthetic.webm");
        tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi",
                "-i", "testsrc2=s=" + dimensions + ":r=2", "-t", Double.toString(seconds), "-an",
                "-c:v", "libvpx", "-deadline", "realtime", "-f", format, "-live", "1", "-y", output.toString()));
        return output;
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

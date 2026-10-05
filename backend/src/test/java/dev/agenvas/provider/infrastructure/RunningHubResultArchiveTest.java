package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic ZIPs only; actual media decoding remains the Asset archive's responsibility. */
class RunningHubResultArchiveTest {
    @Test void extractsSupportedMediaAndReleasesPrivateFilesOnClose() throws Exception {
        List<Path> paths = new ArrayList<>();
        var archive = RunningHubResultArchive.open(payload(zip(List.of("output/", "output/图片.PNG", "clip.mp4", "voice.wav", "readme.txt"), 12)));
        Path directory;
        try (archive) {
            assertThat(archive.members()).extracting(RunningHubResultArchive.Member::kind)
                    .containsExactly(RunningHubDefinition.OutputKind.IMAGE, RunningHubDefinition.OutputKind.VIDEO, RunningHubDefinition.OutputKind.AUDIO);
            directory = archive.members().getFirst().file().getParent();
            for (var member : archive.members()) {
                paths.add(member.file());
                assertThat(member.file().getFileName().toString()).startsWith("output-");
                assertThat(member.entry().sha256()).matches("[0-9a-f]{64}");
                try (var result = archive.download(member.entry())) { assertThat(result.stream().readAllBytes()).hasSize(12); }
            }
            assertThatThrownBy(() -> archive.download(new ProviderResultManifest.ArchiveEntry("clip.mp4", "0".repeat(64))))
                    .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        }
        paths.forEach(path -> assertThat(path).doesNotExist());
        assertThat(directory).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = { "../outside.png", "/outside.png", "C:/outside.png", "dir/../outside.png", "dir\\outside.png", "dir//outside.png", "dir//", "dir/./outside.png", "nested.zip" })
    void rejectsUnsafeMemberPathsAndNestedArchives(String name) throws Exception {
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(zip(List.of(name), 1))))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    @Test void rejectsTruncationAndDuplicateNames() throws Exception {
        byte[] bytes = zip(List.of("a.png", "b.png"), 1);
        for (int i = 0; i < bytes.length - 4; i++) {
            if (bytes[i] == 'b' && bytes[i + 1] == '.' && bytes[i + 2] == 'p' && bytes[i + 3] == 'n' && bytes[i + 4] == 'g') bytes[i] = 'a';
        }
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(bytes))).isInstanceOf(RunningHubClient.ProtocolFailure.class);
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length - 22);
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(truncated))).isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    @Test void enforcesCompressedExpandedEntryAndEntryCountBoundsIncludingIgnoredFiles() throws Exception {
        byte[] bytes = zip(List.of("a.png", "metadata.txt"), 64);
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(bytes), new RunningHubResultArchive.Limits(bytes.length - 1, 1024, 1024, 10)))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(bytes), new RunningHubResultArchive.Limits(1024, 100, 1024, 10)))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(bytes), new RunningHubResultArchive.Limits(1024, 1024, 63, 10)))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(bytes), new RunningHubResultArchive.Limits(1024, 1024, 1024, 1)))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    @Test void doesNotTrustDeclaredExpandedSizeAndValidatesCrc() throws Exception {
        byte[] bytes = zip(List.of("a.png"), 64);
        for (int i = 0; i < bytes.length - 24; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 24] = 1; // Forge the central directory's uncompressed size; the actual stream is longer.
                break;
            }
        }
        byte[] invalidSize = bytes;
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(invalidSize), new RunningHubResultArchive.Limits(1024, 1024, 32, 10)))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        bytes = zip(List.of("a.png"), 64);
        for (int i = 0; i < bytes.length - 16; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) { bytes[i + 16] ^= 1; break; }
        }
        byte[] invalidCrc = bytes;
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(invalidCrc))).isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    @Test void closesDownloadWhenTheArchiveIsInvalid() {
        var input = new ByteArrayInputStream(new byte[] { 1, 2, 3 }) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        assertThatThrownBy(() -> RunningHubResultArchive.open(new MediaPayload(input, "application/zip")))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        assertThat(input.closed).isTrue();
    }

    @Test void rejectsMoreThanTheGlobalMediaOutputLimit() throws Exception {
        List<String> names = java.util.stream.IntStream.rangeClosed(0, RunningHubDefinition.MAX_OUTPUTS)
                .mapToObj(index -> index + ".png").toList();
        assertThatThrownBy(() -> RunningHubResultArchive.open(payload(zip(names, 1))))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    private static MediaPayload payload(byte[] bytes) { return new MediaPayload(new ByteArrayInputStream(bytes), "application/zip"); }
    private static byte[] zip(List<String> names, int size) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(new byte[size]);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}

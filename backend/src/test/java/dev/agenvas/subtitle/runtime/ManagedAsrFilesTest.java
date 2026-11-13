package dev.agenvas.subtitle.runtime;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedAsrFilesTest {
    @TempDir
    Path root;
    @Test
    void verifiedFixturePublishesAtomicallyAndCorruptOrCanceledAttemptsKeepThePriorModel() throws Exception {
        byte[] bytes = "controlled model fixture; not speech recognition"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var model = new ManagedAsrCatalog.Model("fixture", "model.bin", bytes.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                java.net.URI.create("https://huggingface.co/fixture"));
        var files = new ManagedAsrFiles(new ManagedAsrProperties(root, null));
        files.download(model, ignored -> new ByteArrayInputStream(bytes), () -> {
        }, ignored -> {
        }, files::publish);
        assertThat(files.verified(model)).isTrue();
        assertThat(Files.readAllBytes(root.resolve("model.bin"))).isEqualTo(bytes);
        assertThatThrownBy(
                () -> files.download(model, ignored -> new ByteArrayInputStream(new byte[bytes.length]), () -> {
                }, ignored -> {
                }, files::publish))
                .isInstanceOfSatisfying(AsrDownloadException.class,
                        error -> assertThat(error.code()).isEqualTo(AsrDownloadException.Code.CHECKSUM_MISMATCH));
        assertThatThrownBy(() -> files.download(model, ignored -> new ByteArrayInputStream(bytes), () -> {
            throw new CancellationException();
        }, ignored -> {
        }, files::publish))
                .isInstanceOf(CancellationException.class);
        assertThat(files.verified(model)).isTrue();
        try (var paths = Files.list(root)) {
            assertThat(paths.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("model.bin",
                    ".download.lock", "LICENSE-whisper-model.txt");
        }
    }
}

package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Storage diagnostics never create data and reject files or symlink archive roots. */
class SystemDiagnosticsServiceTest {

    @TempDir
    Path directory;

    @Test
    void absentRootUsesWritableParentWithoutCreatingIt() {
        Path root = directory.resolve("new-assets");
        assertThat(SystemDiagnosticsService.storageAvailable(root)).isTrue();
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void fileOrSymlinkRootIsNotReportedAsUsable() throws Exception {
        Path file = Files.createFile(directory.resolve("not-a-directory"));
        assertThat(SystemDiagnosticsService.storageAvailable(file)).isFalse();
        Path link = Files.createSymbolicLink(directory.resolve("linked-assets"), directory);
        assertThat(SystemDiagnosticsService.storageAvailable(link)).isFalse();
    }
}

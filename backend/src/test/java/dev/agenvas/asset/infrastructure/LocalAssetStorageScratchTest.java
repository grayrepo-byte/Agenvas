package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.agenvas.asset.application.AssetProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Crash-orphan cleanup never removes an active encoder or unknown project files. */
class LocalAssetStorageScratchTest {

    @TempDir Path root;

    @Test
    void cleansOnlyOldUnlockedKnownExportScratch() throws Exception {
        LocalAssetStorage storage = new LocalAssetStorage(new AssetProperties(root),
                mock(MediaToolRunner.class), mock(ObjectMapper.class));
        UUID projectId = UUID.randomUUID();
        Path project = Files.createDirectories(root.resolve(projectId.toString()));
        Path stableAsset = Files.writeString(project.resolve("asset.mp4"), "stable");
        Instant cutoff = Instant.now().minusSeconds(24 * 60 * 60);

        try (var active = storage.createExportWorkDirectory(projectId)) {
            Path activeDirectory = active.directory();
            Files.writeString(activeDirectory.resolve("silent-export.mp4"), "working");
            Files.setLastModifiedTime(activeDirectory,
                    FileTime.from(cutoff.minusSeconds(60)));
            assertThat(storage.cleanupStaleExportWorkDirectories(cutoff, 100)).isZero();
            assertThat(Files.exists(activeDirectory)).isTrue();
            Files.delete(activeDirectory.resolve("silent-export.mp4"));
        }

        Path orphan = Files.createTempDirectory(project, ".export-");
        Files.writeString(orphan.resolve(".active.lock"), "");
        Files.writeString(orphan.resolve("silent-export.mp4"), "partial");
        Files.setLastModifiedTime(orphan, FileTime.from(cutoff.minusSeconds(60)));
        Path unknown = Files.createTempDirectory(project, ".export-");
        Files.writeString(unknown.resolve(".active.lock"), "");
        Files.writeString(unknown.resolve("notes.txt"), "do not delete");
        Files.setLastModifiedTime(unknown, FileTime.from(cutoff.minusSeconds(60)));
        Path recent = Files.createTempDirectory(project, ".export-");
        Files.writeString(recent.resolve(".active.lock"), "");
        Files.writeString(recent.resolve("silent-export.mp4"), "recent");

        assertThat(storage.cleanupStaleExportWorkDirectories(cutoff, 1)).isEqualTo(1);
        assertThat(Files.exists(orphan)).isFalse();
        assertThat(Files.readString(stableAsset)).isEqualTo("stable");
        assertThat(Files.exists(unknown.resolve("notes.txt"))).isTrue();
        assertThat(Files.exists(recent.resolve("silent-export.mp4"))).isTrue();
        assertThat(storage.cleanupStaleExportWorkDirectories(cutoff, 100)).isZero();
    }
}

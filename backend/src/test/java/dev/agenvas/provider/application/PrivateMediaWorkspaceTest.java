package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateMediaWorkspaceTest {
    @TempDir
    Path temp;

    @Test
    void closeRemovesOnlyThePrivateWorkspaceAndDoesNotFollowLinks() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        Path source = Files.writeString(other.resolve("source"), "original");
        Path scratch;
        Path otherScratch;
        try (var workspace = PrivateMediaWorkspace.create(temp, "media-");
                var concurrent = PrivateMediaWorkspace.create(temp, "media-")) {
            scratch = workspace.path();
            otherScratch = concurrent.path();
            assertThat(scratch).isNotEqualTo(otherScratch).hasParent(temp);
            assertThat(Files.getPosixFilePermissions(scratch))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            Path nested = Files.createDirectories(scratch.resolve("a/b"));
            Files.writeString(nested.resolve("output"), "synthetic");
            Files.createSymbolicLink(nested.resolve("outside"), other);
        }
        assertThat(scratch).doesNotExist();
        assertThat(source).hasContent("original");
        assertThat(otherScratch).doesNotExist();
    }

    @Test
    void closeRemovesPartialOutputWhenProcessingFails() throws Exception {
        var failure = new IllegalStateException("processing failed");
        var workspace = PrivateMediaWorkspace.create(temp, "media-");
        Path scratch = workspace.path();
        assertThatThrownBy(() -> {
            try (workspace) {
                Files.writeString(scratch.resolve("partial"), "synthetic");
                throw failure;
            }
        }).isSameAs(failure);
        assertThat(scratch).doesNotExist();
    }

    @Test
    void cleanupFailureDoesNotReplaceProcessingFailure() throws Exception {
        var workspace = PrivateMediaWorkspace.create(temp, "media-");
        Path scratch = workspace.path();
        Path blocked = Files.createDirectory(scratch.resolve("blocked"));
        Files.writeString(blocked.resolve("partial"), "synthetic");
        var failure = new IllegalStateException("processing failed");
        try {
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("r-x------"));
            assumeFalse(Files.isWritable(blocked), "Requires a user subject to POSIX write permissions");
            assertThatThrownBy(() -> {
                try (workspace) {
                    throw failure;
                }
            }).isSameAs(failure);
            assertThat(blocked.resolve("partial")).exists();
        } finally {
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwx------"));
            workspace.close();
        }
        assertThat(scratch).doesNotExist();
    }
}

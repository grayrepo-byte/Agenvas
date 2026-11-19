package dev.agenvas.provider.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A private directory for one local media operation. Consume any output before closing it. */
final class PrivateMediaWorkspace implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PrivateMediaWorkspace.class);
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY = PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    private final Path path;

    private PrivateMediaWorkspace(Path path) {
        this.path = path;
    }

    static PrivateMediaWorkspace create(String prefix) throws IOException {
        return new PrivateMediaWorkspace(Files.createTempDirectory(prefix, PRIVATE_DIRECTORY));
    }

    static PrivateMediaWorkspace create(Path parent, String prefix) throws IOException {
        return new PrivateMediaWorkspace(Files.createTempDirectory(parent, prefix, PRIVATE_DIRECTORY));
    }

    Path path() {
        return path;
    }

    @Override
    public void close() {
        try {
            // The default walk does not follow symbolic links, including a link at the root.
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null)
                        throw failure;
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException failure) {
            // Cleanup must not replace the operation's result or log private paths.
            log.warn("Local media workspace cleanup failed");
        }
    }
}

package dev.agenvas.subtitle.runtime;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;
import org.springframework.stereotype.Component;

/** Private volume staging, bounded streaming, checksum validation and same-filesystem atomic publication. */
@Component
public class ManagedAsrFiles {
    private static final long RESERVE_BYTES = 128L * 1024 * 1024;
    private final Path root;
    public ManagedAsrFiles(ManagedAsrProperties properties) {
        root = properties.root();
    }
    public Path modelPath() {
        return root.resolve(ManagedAsrCatalog.MODEL.fileName());
    }
    @FunctionalInterface
    public interface Publisher {
        void publish(Path stage, Path target) throws IOException;
    }
    public void download(ManagedAsrCatalog.Model model, AsrModelSource source, Runnable check, LongConsumer progress,
            Publisher publisher) {
        Path stage = null;
        try {
            prepare();
            Path lockPath = root.resolve(".download.lock");
            rejectLink(lockPath);
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                if (lock == null)
                    throw new AsrDownloadException(AsrDownloadException.Code.VOLUME_BUSY);
                cleanParts();
                if (Files.getFileStore(root).getUsableSpace() < model.bytes() + RESERVE_BYTES)
                    diskLimit();
                stage = Files.createTempFile(root, ".download-", ".part",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                long bytes = 0, lastProgress = 0;
                var digest = digest();
                check.run();
                progress.accept(0);
                try (var input = source.open(model, check);
                        var output = Files.newOutputStream(stage, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        check.run();
                        bytes += count;
                        if (bytes > model.bytes())
                            throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
                        if (Files.getFileStore(root).getUsableSpace() < RESERVE_BYTES)
                            diskLimit();
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        if (System.nanoTime() - lastProgress > 500_000_000L) {
                            progress.accept(bytes);
                            lastProgress = System.nanoTime();
                        }
                    }
                }
                check.run();
                progress.accept(bytes);
                if (bytes != model.bytes())
                    throw new AsrDownloadException(AsrDownloadException.Code.INVALID_DOWNLOAD);
                if (!HexFormat.of().formatHex(digest.digest()).equals(model.sha256()))
                    throw new AsrDownloadException(AsrDownloadException.Code.CHECKSUM_MISMATCH);
                try (var output = FileChannel.open(stage, StandardOpenOption.WRITE)) {
                    output.force(true);
                }
                check.run();
                Path target = root.resolve(model.fileName());
                rejectLink(target);
                publisher.publish(stage, target);
            }
        } catch (OverlappingFileLockException busy) {
            throw new AsrDownloadException(AsrDownloadException.Code.VOLUME_BUSY);
        } catch (IOException failure) {
            throw new AsrDownloadException(AsrDownloadException.Code.DOWNLOAD_FAILED);
        } finally {
            if (stage != null)
                try {
                    Files.deleteIfExists(stage);
                } catch (IOException cleanup) {
                    throw new AsrDownloadException(AsrDownloadException.Code.VOLUME_UNAVAILABLE);
                }
        }
    }
    public void publish(Path stage, Path target) throws IOException {
        Path license = root.resolve("LICENSE-whisper-model.txt");
        rejectLink(license);
        try (var text = getClass().getResourceAsStream("/subtitles/whisper-model-LICENSE.txt")) {
            if (text == null)
                throw new IOException("Missing bundled model license");
            Files.write(license, text.readAllBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(license, PosixFilePermissions.fromString("rw-------"));
        }
        Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (var directory = FileChannel.open(root, StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
    public void cleanInterrupted() {
        try {
            prepare();
            Path path = root.resolve(".download.lock");
            rejectLink(path);
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var lock = channel.tryLock()) {
                if (lock != null)
                    cleanParts();
            }
        } catch (OverlappingFileLockException busy) {
            /* The prior owner still cleans its own attempt. */ } catch (IOException failed) {
            throw new AsrDownloadException(AsrDownloadException.Code.VOLUME_UNAVAILABLE);
        }
    }
    public boolean verified(ManagedAsrCatalog.Model model) {
        Path path = root.resolve(model.fileName());
        try {
            if (Files.isSymbolicLink(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) != model.bytes())
                return false;
            var digest = digest();
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;)
                    digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest()).equals(model.sha256());
        } catch (IOException failure) {
            return false;
        }
    }
    /** Called only while the volume lock is held; restarting never resumes or trusts a partial file. */
    private void cleanParts() throws IOException {
        try (var paths = Files.newDirectoryStream(root, ".download-*.part")) {
            for (Path path : paths) {
                rejectLink(path);
                Files.delete(path);
            }
        }
    }
    private void prepare() throws IOException {
        rejectLink(root);
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
    }
    private static void rejectLink(Path path) {
        if (Files.isSymbolicLink(path))
            throw new AsrDownloadException(AsrDownloadException.Code.VOLUME_UNAVAILABLE);
    }
    private static void diskLimit() {
        throw new AsrDownloadException(AsrDownloadException.Code.DISK_LIMIT);
    }
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

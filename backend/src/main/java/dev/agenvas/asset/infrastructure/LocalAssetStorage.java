package dev.agenvas.asset.infrastructure;

import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.shared.error.ApiProblemException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Streams image bytes to a private temporary file, validates them, then atomically installs. */
@Component
public class LocalAssetStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAssetStorage.class);
    private static final String EXPORT_LOCK_NAME = ".active.lock";
    private static final String EXPORT_OUTPUT_NAME = "silent-export.mp4";
    private static final Object[] TASK_LOCK_STRIPES = new Object[64];
    static {
        for (int index = 0; index < TASK_LOCK_STRIPES.length; index++) {
            TASK_LOCK_STRIPES[index] = new Object();
        }
    }
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final int THUMBNAIL_EDGE = 480;
    private final Path root;
    private final MediaToolRunner mediaTools;
    private final ObjectMapper mapper;

    public LocalAssetStorage(AssetProperties properties, MediaToolRunner mediaTools,
            ObjectMapper mapper) {
        root = properties.root().toAbsolutePath().normalize();
        this.mediaTools = mediaTools;
        this.mapper = mapper;
    }

    /** Archive result supplied to the database writer only after the stable file exists. */
    public record StoredImage(String objectKey, String contentType, long byteSize,
            String sha256, int width, int height, String thumbnailKey,
            long thumbnailByteSize, String thumbnailSha256) {}

    /** A probed MP4 and its extracted PNG poster installed under immutable object keys. */
    public record StoredVideo(String objectKey, long byteSize, String sha256,
            int width, int height, int durationMs, String thumbnailKey, long thumbnailByteSize,
            String thumbnailSha256) {}

    /** Serializes one task archive across processes sharing the required local volume. */
    public <T> T withTaskImageLock(UUID projectId, UUID assetId, Supplier<T> action) {
        return withTaskArchiveLock(projectId, assetId, "image", action);
    }

    /** Video uses the same cross-process archive fence but a distinct lock namespace. */
    public <T> T withTaskVideoLock(UUID projectId, UUID assetId, Supplier<T> action) {
        return withTaskArchiveLock(projectId, assetId, "video", action);
    }

    private <T> T withTaskArchiveLock(UUID projectId, UUID assetId, String kind,
            Supplier<T> action) {
        Object stripe = TASK_LOCK_STRIPES[Math.floorMod(assetId.hashCode(),
                TASK_LOCK_STRIPES.length)];
        synchronized (stripe) {
            try {
                Path directory = prepareProjectDirectory(projectId);
                Path lockPath = directory.resolve(".task-" + kind + "-" + assetId + ".lock");
                try (FileChannel channel = FileChannel.open(lockPath,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                        FileLock ignored = channel.lock()) {
                    return action.get();
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot lock task media archive", failure);
            }
        }
    }

    /** Recovers task-keyed MP4 bytes and poster after a file/DB crash window. */
    public Optional<StoredVideo> recoverVideo(UUID projectId, UUID assetId) {
        String prefix = projectId + "/" + assetId;
        Path original = checkedPath(prefix + ".mp4");
        Path poster = checkedPath(prefix + ".thumb.png");
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(poster, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Task video poster has no original file");
            }
            return Optional.empty();
        }
        if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Task video path is not a regular file");
        }
        Path temporary = null;
        try {
            long size = Files.size(original);
            if (size < 1 || size > MAX_VIDEO_BYTES) {
                throw new IllegalStateException("Recovered task video exceeds archive bounds");
            }
            VideoDetails details = inspectVideo(original);
            if (!Files.exists(poster, LinkOption.NOFOLLOW_LINKS)) {
                temporary = Files.createTempFile(original.getParent(), ".recover-video-poster-", ".png");
                mediaTools.ffmpeg(java.util.List.of("-hide_banner", "-loglevel", "error",
                        "-nostdin", "-i", original.toString(), "-frames:v", "1",
                        "-vf", "scale=480:-2", "-y", temporary.toString()));
                Files.move(temporary, poster, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
            }
            if (!Files.isRegularFile(poster, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(poster) < 1 || Files.size(poster) > MAX_IMAGE_BYTES
                    || ImageIO.read(poster.toFile()) == null) {
                throw new IllegalStateException("Recovered task video poster is invalid");
            }
            return Optional.of(new StoredVideo(prefix + ".mp4", size, sha256(original),
                    details.width(), details.height(), details.durationMs(), prefix + ".thumb.png",
                    Files.size(poster), sha256(poster)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot recover task video archive", failure);
        } finally {
            cleanup(temporary);
        }
    }

    private VideoDetails inspectVideo(Path path) {
        JsonNode probe;
        try {
            probe = mapper.readTree(mediaTools.ffprobe(java.util.List.of(
                    "-v", "error", "-select_streams", "v:0", "-show_entries",
                    "stream=codec_type,width,height:format=format_name,duration",
                    "-of", "json", path.toString())));
        } catch (MediaToolRunner.MediaToolException exception) {
            if (exception.invalidInput()) {
                throw invalid("无法解析视频媒体。", "ASSET_INVALID_VIDEO");
            }
            throw exception;
        }
        JsonNode stream = probe.path("streams").path(0);
        int width = stream.path("width").asInt();
        int height = stream.path("height").asInt();
        double duration = probe.path("format").path("duration").asDouble();
        String format = probe.path("format").path("format_name").asText();
        if (!"video".equals(stream.path("codec_type").asText())
                || !format.contains("mp4") || width < 1 || height < 1
                || (long) width * height > MAX_IMAGE_PIXELS
                || !Double.isFinite(duration) || duration <= 0 || duration > 60) {
            throw invalid("视频必须是可解码且不超过 60 秒的 MP4。", "ASSET_INVALID_VIDEO");
        }
        int durationMs = Math.toIntExact(Math.round(duration * 1_000));
        if (durationMs < 1 || durationMs > 60_000) {
            throw invalid("视频时长超出支持范围。", "ASSET_INVALID_VIDEO");
        }
        return new VideoDetails(width, height, durationMs);
    }

    private record VideoDetails(int width, int height, int durationMs) {}

    /** Gives a local export one locked private scratch directory on the asset volume. */
    public ExportWorkspace createExportWorkDirectory(UUID projectId) {
        Path directory = null;
        FileChannel channel = null;
        try {
            Path projectDirectory = prepareProjectDirectory(projectId);
            directory = Files.createTempDirectory(projectDirectory, ".export-");
            channel = FileChannel.open(directory.resolve(EXPORT_LOCK_NAME),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            return new ExportWorkspace(directory, channel, channel.lock());
        } catch (IOException | RuntimeException exception) {
            if (channel != null) {
                try { channel.close(); } catch (IOException ignored) { /* Preserve the first failure. */ }
            }
            if (directory != null) {
                cleanup(directory.resolve(EXPORT_LOCK_NAME));
                cleanup(directory);
            }
            throw new IllegalStateException("Cannot create private export work directory", exception);
        }
    }

    /** An OS lock outlives a stalled lease and prevents cleanup of an active encoder. */
    public static final class ExportWorkspace implements AutoCloseable {
        private final Path directory;
        private final FileChannel channel;
        private final FileLock lock;

        private ExportWorkspace(Path directory, FileChannel channel, FileLock lock) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
        }

        public Path directory() {
            return directory;
        }

        /** Best effort: a failed immediate cleanup remains eligible for the janitor. */
        @Override
        public void close() {
            try {
                lock.release();
            } catch (IOException failure) {
                LOGGER.warn("Export scratch cleanup deferred: {}", failure.getClass().getSimpleName());
            }
            try {
                channel.close();
                Files.deleteIfExists(directory.resolve(EXPORT_LOCK_NAME));
                Files.deleteIfExists(directory);
            } catch (IOException failure) {
                LOGGER.warn("Export scratch cleanup deferred: {}", failure.getClass().getSimpleName());
            }
        }
    }

    /** Removes only old, unlocked export scratch with exactly the files this app creates. */
    public int cleanupStaleExportWorkDirectories(Instant cutoff, int limit) {
        if (cutoff == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid export scratch cleanup bounds");
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        int removed = 0;
        try (var projects = Files.newDirectoryStream(root)) {
            for (Path project : projects) {
                if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
                        || !uuidDirectory(project.getFileName().toString())) continue;
                try (var candidates = Files.newDirectoryStream(project, ".export-*")) {
                    for (Path candidate : candidates) {
                        if (removed >= limit) return removed;
                        if (cleanupStaleExportDirectory(candidate, cutoff)) removed++;
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect export scratch directories", failure);
        }
        return removed;
    }

    private boolean cleanupStaleExportDirectory(Path directory, Instant cutoff) {
        Path lockPath = directory.resolve(EXPORT_LOCK_NAME);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            if (Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS)
                    .toInstant().isAfter(cutoff)) return false;
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException occupied) {
                    return false;
                }
                if (lock == null) return false;
                try (lock) {
                    try (var children = Files.newDirectoryStream(directory)) {
                        for (Path child : children) {
                            String name = child.getFileName().toString();
                            if (!name.equals(EXPORT_LOCK_NAME)
                                    && !name.equals(EXPORT_OUTPUT_NAME)) return false;
                        }
                    }
                    Files.deleteIfExists(directory.resolve(EXPORT_OUTPUT_NAME));
                }
            }
            Files.deleteIfExists(lockPath);
            Files.deleteIfExists(directory);
            return true;
        } catch (IOException failure) {
            LOGGER.warn("Export scratch cleanup deferred: {}", failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean uuidDirectory(String name) {
        try {
            return UUID.fromString(name).toString().equals(name);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Bounded video ingest uses an actual stream probe and first-frame decode. */
    public StoredVideo storeVideo(UUID projectId, UUID assetId, InputStream source) {
        Path directory = root.resolve(projectId.toString());
        Path temporary = null;
        Path posterTemporary = null;
        Path stable = null;
        Path posterStable = null;
        boolean originalMoved = false;
        boolean posterMoved = false;
        boolean installed = false;
        try {
            directory = prepareProjectDirectory(projectId);
            temporary = Files.createTempFile(directory, ".video-ingest-", ".mp4");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(source, temporary, digest, MAX_VIDEO_BYTES);
            VideoDetails details = inspectVideo(temporary);
            posterTemporary = Files.createTempFile(directory, ".video-poster-", ".png");
            try {
                mediaTools.ffmpeg(java.util.List.of("-hide_banner", "-loglevel", "error",
                        "-nostdin", "-i", temporary.toString(), "-frames:v", "1",
                        "-vf", "scale=480:-2", "-y", posterTemporary.toString()));
            } catch (MediaToolRunner.MediaToolException exception) {
                if (exception.invalidInput()) {
                    throw invalid("无法解码视频首帧。", "ASSET_INVALID_VIDEO");
                }
                throw exception;
            }
            if (Files.size(posterTemporary) < 1 || Files.size(posterTemporary) > MAX_IMAGE_BYTES
                    || ImageIO.read(posterTemporary.toFile()) == null) {
                throw invalid("无法解码视频首帧。", "ASSET_INVALID_VIDEO");
            }
            String key = projectId + "/" + assetId + ".mp4";
            String posterKey = projectId + "/" + assetId + ".thumb.png";
            stable = checkedPath(key);
            posterStable = checkedPath(posterKey);
            Files.move(temporary, stable, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            originalMoved = true;
            Files.move(posterTemporary, posterStable, StandardCopyOption.ATOMIC_MOVE);
            posterTemporary = null;
            posterMoved = true;
            StoredVideo result = new StoredVideo(key, size,
                    HexFormat.of().formatHex(digest.digest()), details.width(),
                    details.height(), details.durationMs(), posterKey,
                    Files.size(posterStable), sha256(posterStable));
            installed = true;
            return result;
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Private video archive failed", exception);
        } finally {
            if (!installed) {
                if (originalMoved) cleanup(stable);
                if (posterMoved) cleanup(posterStable);
            }
            cleanup(temporary);
            cleanup(posterTemporary);
        }
    }

    /** Creates one never-reused object key from server-generated identifiers. */
    public StoredImage storeImage(UUID projectId, UUID assetId, InputStream source) {
        Path directory = root.resolve(projectId.toString());
        Path temporary = null;
        Path thumbnailTemporary = null;
        Path stable = null;
        Path thumbnailStable = null;
        boolean originalMoved = false;
        boolean thumbnailMoved = false;
        boolean installed = false;
        try {
            directory = prepareProjectDirectory(projectId);
            temporary = Files.createTempFile(directory, ".ingest-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(source, temporary, digest, MAX_IMAGE_BYTES);
            ImageDetails details = inspectImage(temporary);
            thumbnailTemporary = Files.createTempFile(directory, ".thumb-", ".tmp");
            writeThumbnail(details.decoded(), thumbnailTemporary);
            String key = projectId + "/" + assetId + details.extension();
            String thumbnailKey = projectId + "/" + assetId + ".thumb.png";
            stable = checkedPath(key);
            thumbnailStable = checkedPath(thumbnailKey);
            try {
                Files.move(temporary, stable, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
                originalMoved = true;
                Files.move(thumbnailTemporary, thumbnailStable, StandardCopyOption.ATOMIC_MOVE);
                thumbnailTemporary = null;
                thumbnailMoved = true;
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("Asset volume must support atomic file moves", exception);
            }
            long thumbnailSize = Files.size(thumbnailStable);
            String thumbnailHash = sha256(thumbnailStable);
            StoredImage result = new StoredImage(key, details.contentType(), size,
                    HexFormat.of().formatHex(digest.digest()), details.width(), details.height(),
                    thumbnailKey, thumbnailSize, thumbnailHash);
            installed = true;
            return result;
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Private asset archive failed", exception);
        } finally {
            if (!installed) {
                if (originalMoved) {
                    cleanup(stable);
                }
                if (thumbnailMoved) {
                    cleanup(thumbnailStable);
                }
            }
            cleanup(temporary);
            cleanup(thumbnailTemporary);
        }
    }

    /** Reconstructs metadata for a task-keyed original installed before a process crash. */
    public Optional<StoredImage> recoverImage(UUID projectId, UUID assetId) {
        String prefix = projectId + "/" + assetId;
        Path png = checkedPath(prefix + ".png");
        Path jpeg = checkedPath(prefix + ".jpg");
        boolean hasPng = Files.isRegularFile(png, LinkOption.NOFOLLOW_LINKS);
        boolean hasJpeg = Files.isRegularFile(jpeg, LinkOption.NOFOLLOW_LINKS);
        Path thumbnail = checkedPath(prefix + ".thumb.png");
        if ((Files.exists(png, LinkOption.NOFOLLOW_LINKS) && !hasPng)
                || (Files.exists(jpeg, LinkOption.NOFOLLOW_LINKS) && !hasJpeg)) {
            throw new IllegalStateException("Task image path is not a regular file");
        }
        if (hasPng && hasJpeg) {
            throw new IllegalStateException("Conflicting task image files require investigation");
        }
        if (!hasPng && !hasJpeg) {
            if (Files.exists(thumbnail, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Task image thumbnail has no original file");
            }
            return Optional.empty();
        }
        Path original = hasPng ? png : jpeg;
        Path temporary = null;
        try {
            long size = Files.size(original);
            if (size < 1 || size > MAX_IMAGE_BYTES) {
                throw new IllegalStateException("Recovered task image exceeds archive bounds");
            }
            ImageDetails details = inspectImage(original);
            if (!original.getFileName().toString().endsWith(details.extension())) {
                throw new IllegalStateException("Recovered task image extension differs from bytes");
            }
            if (!Files.exists(thumbnail, LinkOption.NOFOLLOW_LINKS)) {
                temporary = Files.createTempFile(original.getParent(), ".recover-thumb-", ".tmp");
                writeThumbnail(details.decoded(), temporary);
                Files.move(temporary, thumbnail, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
            }
            if (!Files.isRegularFile(thumbnail, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(thumbnail) < 1 || Files.size(thumbnail) > MAX_IMAGE_BYTES
                    || ImageIO.read(thumbnail.toFile()) == null) {
                throw new IllegalStateException("Recovered task thumbnail is invalid");
            }
            return Optional.of(new StoredImage(prefix + details.extension(),
                    details.contentType(), size, sha256(original), details.width(),
                    details.height(), prefix + ".thumb.png", Files.size(thumbnail),
                    sha256(thumbnail)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot recover task image archive", failure);
        } finally {
            cleanup(temporary);
        }
    }

    /** Resolves only a validated key under the configured archive root. */
    public Path checkedPath(String objectKey) {
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root) || path.equals(root)
                || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new IllegalStateException("Invalid stored asset key");
        }
        // A lexical path inside the volume must not escape through an existing project symlink.
        Path parent = root;
        for (Path component : root.relativize(path.getParent())) {
            parent = parent.resolve(component);
            if (Files.isSymbolicLink(parent)) {
                throw new IllegalStateException("Stored asset directory is a symbolic link");
            }
        }
        return path;
    }

    /** Creates a project directory only when it is an actual directory under this volume. */
    private Path prepareProjectDirectory(UUID projectId) throws IOException {
        String probeKey = projectId + "/.directory-check";
        Path directory = root.resolve(projectId.toString());
        checkedPath(probeKey);
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Asset project path is not a directory");
        }
        checkedPath(probeKey);
        return directory;
    }

    /** Removes only the newly created object if its database insertion fails. */
    public void discard(String objectKey) {
        try {
            Files.deleteIfExists(checkedPath(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to discard unregistered asset", exception);
        }
    }

    private void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A bounded orphan is recoverable; it must never receive a READY database row.
        }
    }

    /** Builds a bounded preview once at ingest, never on every canvas GET. */
    private void writeThumbnail(BufferedImage source, Path target) throws IOException {
        double scale = Math.min(1.0,
                (double) THUMBNAIL_EDGE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        try (OutputStream output = openArchiveOutput(target)) {
            if (!ImageIO.write(thumbnail, "png", output)) {
                throw new IllegalStateException("PNG thumbnail encoder unavailable");
            }
        }
    }

    private String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the hash while bounded thumbnail bytes are read.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private long copyBounded(InputStream source, Path target, MessageDigest digest, long maximum)
            throws IOException {
        long count = 0;
        try (var output = openArchiveOutput(target);
                var input = new DigestInputStream(source, digest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > maximum) {
                    throw new ApiProblemException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "ASSET_TOO_LARGE", "素材过大", "素材超过归档大小限制。", false);
                }
                output.write(buffer, 0, read);
            }
        }
        if (count == 0) {
            throw maximum == MAX_VIDEO_BYTES
                    ? invalid("视频文件不能为空。", "ASSET_INVALID_VIDEO")
                    : invalid("图片文件不能为空。", "ASSET_INVALID_IMAGE");
        }
        return count;
    }

    /** Keeps the bounded archive writer replaceable for deterministic partial-write fault tests. */
    protected OutputStream openArchiveOutput(Path target) throws IOException {
        return Files.newOutputStream(target);
    }

    private ImageDetails inspectImage(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                throw invalid("无法解码图片。", "ASSET_INVALID_IMAGE");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid("仅支持可解码的 PNG、JPEG 或 WebP 图片。", "ASSET_INVALID_IMAGE");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                String contentType;
                String extension;
                if (format.equals("png")) {
                    contentType = "image/png";
                    extension = ".png";
                } else if (format.equals("jpeg") || format.equals("jpg")) {
                    contentType = "image/jpeg";
                    extension = ".jpg";
                } else if (format.equals("webp")) {
                    contentType = "image/webp";
                    extension = ".webp";
                } else {
                    throw invalid("暂不支持该图片编码。", "ASSET_UNSUPPORTED_IMAGE");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw invalid("图片像素超出 40 MP 限制。", "ASSET_TOO_MANY_PIXELS");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width
                        || decoded.getHeight() != height) {
                    throw invalid("图片解码失败。", "ASSET_INVALID_IMAGE");
                }
                return new ImageDetails(contentType, extension, width, height, decoded);
            } catch (IOException | IndexOutOfBoundsException exception) {
                throw invalid("图片解码失败。", "ASSET_INVALID_IMAGE");
            } finally {
                reader.dispose();
            }
        }
    }

    private ApiProblemException invalid(String detail, String code) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                "素材无效", detail, false);
    }

    private record ImageDetails(String contentType, String extension, int width, int height,
            BufferedImage decoded) {}
}

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

/** 将媒体流写入私有临时文件，完成字节与解码校验后以原子移动安装不可变对象。 */
@Component
public class LocalAssetStorage {

    /** 文件清理只能写入日志，不能掩盖最初的归档错误。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalAssetStorage.class);
    /** 导出临时目录中用于跨进程保护活动编码器的锁文件名。 */
    private static final String EXPORT_LOCK_NAME = ".active.lock";
    /** 清理器唯一允许删除的导出结果文件名。 */
    private static final String EXPORT_OUTPUT_NAME = "silent-export.mp4";
    /** 先在进程内串行化，再用文件锁保护共享卷上的任务归档。 */
    private static final Object[] TASK_LOCK_STRIPES = new Object[64];
    static {
        for (int index = 0; index < TASK_LOCK_STRIPES.length; index++) {
            TASK_LOCK_STRIPES[index] = new Object();
        }
    }
    /** 原始图片和缩略图的最大归档字节数。 */
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    /** 原始视频的最大归档字节数。 */
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;
    /** 图片与视频允许解码的最大像素面积。 */
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    /** 缩略图最长边，短边按比例缩放。 */
    private static final int THUMBNAIL_EDGE = 480;
    /** 规范化后的私有归档根目录。 */
    private final Path root;
    /** 使用固定参数调用媒体探测和转码工具。 */
    private final MediaToolRunner mediaTools;
    /** 解析 ffprobe 的受限 JSON 输出。 */
    private final ObjectMapper mapper;

    /** 固定私有媒体根目录及受控探测工具；对象键只能解析到此根目录内。
     * @param properties 本地素材目录配置
     * @param mediaTools 使用白名单参数运行 ffmpeg 和 ffprobe 的执行器
     * @param mapper 解析 ffprobe JSON 的 Jackson 映射器
     */
    public LocalAssetStorage(AssetProperties properties, MediaToolRunner mediaTools,
            ObjectMapper mapper) {
        root = properties.root().toAbsolutePath().normalize();
        this.mediaTools = mediaTools;
        this.mapper = mapper;
    }

    /**
     * 原图与缩略图都安装到稳定路径后才能写入数据库的结果。
     *
     * @param objectKey 原图在项目目录下的相对键
     * @param contentType 按实际图像编码判定的 MIME 类型
     * @param byteSize 原图字节数
     * @param sha256 原图摘要
     * @param width 解码宽度
     * @param height 解码高度
     * @param thumbnailKey PNG 缩略图相对键
     * @param thumbnailByteSize 缩略图字节数
     * @param thumbnailSha256 缩略图摘要
     */
    public record StoredImage(String objectKey, String contentType, long byteSize,
            String sha256, int width, int height, String thumbnailKey,
            long thumbnailByteSize, String thumbnailSha256) {}

    /**
     * 已探测的 MP4 和已提取 PNG 海报图，均以不可变键安装。
     *
     * @param objectKey MP4 相对键
     * @param byteSize MP4 字节数
     * @param sha256 MP4 摘要
     * @param width 视频像素宽度
     * @param height 视频像素高度
     * @param durationMs 探测得到的时长毫秒数
     * @param thumbnailKey 海报 PNG 相对键
     * @param thumbnailByteSize 海报字节数
     * @param thumbnailSha256 海报摘要
     */
    public record StoredVideo(String objectKey, long byteSize, String sha256,
            int width, int height, int durationMs, String thumbnailKey, long thumbnailByteSize,
            String thumbnailSha256) {}

    /** 使用共享卷文件锁保护一个任务的图片恢复或归档临界区。 */
    public <T> T withTaskImageLock(UUID projectId, UUID assetId, Supplier<T> action) {
        return withTaskArchiveLock(projectId, assetId, "image", action);
    }

    /** 视频使用同一跨进程归档机制，但独立的锁命名空间。 */
    public <T> T withTaskVideoLock(UUID projectId, UUID assetId, Supplier<T> action) {
        return withTaskArchiveLock(projectId, assetId, "video", action);
    }

    /** 以条带监视器避免同 JVM 重叠锁异常，再持有项目卷上的 OS 文件锁执行操作。 */
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

    /** 从任务派生路径恢复已安装 MP4；原片存在但海报缺失时重建海报并重新验证。 */
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

    /** 通过 ffprobe 核验视频流、MP4 容器、分辨率和 60 秒时长上限。 */
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

    /** ffprobe 验证后的视频元数据。
     * @param width 视频流像素宽度
     * @param height 视频流像素高度
     * @param durationMs 视频时长，已验证为 1 到 60,000 毫秒
     */
    private record VideoDetails(int width, int height, int durationMs) {}

    /** 在素材卷项目目录下创建仅本次导出使用的私有临时目录，并持有 OS 锁。 */
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

    /** OS 文件锁在 Worker 租约丢失后仍可保护活动编码进程，防止清理器删文件。 */
    public static final class ExportWorkspace implements AutoCloseable {
        /** 本次导出专属目录，目录名由系统随机生成。 */
        private final Path directory;
        /** 保持锁文件描述符存活，以维持跨进程文件锁。 */
        private final FileChannel channel;
        /** 防止另一个进程误清理仍在编码的工作目录。 */
        private final FileLock lock;

        /** 仅由本地存储创建，调用方不得替换锁、通道或工作目录。
         * @param directory 本次导出的临时工作目录
         * @param channel 持有锁文件的打开通道
         * @param lock 已获得的跨进程目录锁
         */
        private ExportWorkspace(Path directory, FileChannel channel, FileLock lock) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
        }

        /** 返回本次编码专用的临时目录，调用方只能写入约定输出文件。 */
        public Path directory() {
            return directory;
        }

        /** 释放文件锁并尝试清理临时目录；清理失败由定时清理器后续处理。 */
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

    /** 最多清理 limit 个过期、未锁定且只含应用约定文件的导出目录。 */
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

    /** 尝试获取目录锁后检查内容白名单，再删除输出、锁和目录。 */
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

    /** 仅接受规范格式的 UUID 项目目录名。 */
    private boolean uuidDirectory(String name) {
        try {
            return UUID.fromString(name).toString().equals(name);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** 限流保存视频字节，实测容器与视频流后解码首帧并原子安装 MP4 和海报。 */
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

    /** 仅用服务端生成的项目和素材 ID 构造从不复用的图片文件键。 */
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

    /** 从任务固定路径重建崩溃前已安装的图片元数据；冲突文件或孤立缩略图会报错。 */
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

    /** 将相对对象键解析到归档根目录内，并拒绝路径穿越和已有目录符号链接。 */
    public Path checkedPath(String objectKey) {
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root) || path.equals(root)
                || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new IllegalStateException("Invalid stored asset key");
        }
        // 即使词法路径位于卷内，也不能沿已有项目目录符号链接逃出归档根目录。
        Path parent = root;
        for (Path component : root.relativize(path.getParent())) {
            parent = parent.resolve(component);
            if (Files.isSymbolicLink(parent)) {
                throw new IllegalStateException("Stored asset directory is a symbolic link");
            }
        }
        return path;
    }

    /** 创建或复核项目目录确实位于卷内且不是符号链接。 */
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

    /** 数据库登记失败时删除本次新建的未登记对象。 */
    public void discard(String objectKey) {
        try {
            Files.deleteIfExists(checkedPath(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to discard unregistered asset", exception);
        }
    }

    /** 尽力删除临时文件；残留孤儿不可拥有 READY 数据库记录。 */
    private void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 有界孤儿文件可被后续恢复或清理；不能为其写 READY 数据库行。
        }
    }

    /** 入库时生成最长边不超过 480 像素的 PNG 预览，画布读取时不重复解码原图。 */
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

    /** 顺序读取文件计算 SHA-256，不将整份媒体载入内存。 */
    private String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
                // DigestInputStream 在分块读取时同步更新摘要。
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 分块复制并同步更新摘要，超过字节上限或空输入立即失败。 */
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

    /** 隔离文件输出创建点，便于注入写入失败并验证临时文件清理。 */
    protected OutputStream openArchiveOutput(Path target) throws IOException {
        return Files.newOutputStream(target);
    }

    /** 依据实际解码器识别 PNG/JPEG/WebP，限制像素数并完整解码首帧。 */
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

    /** 将不可解码、超限或不支持的媒体映射为稳定 422 错误。 */
    private ApiProblemException invalid(String detail, String code) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                "素材无效", detail, false);
    }

    /** 图像解码得到的媒体类型、尺寸及用于生成缩略图的像素缓冲。
     * @param contentType 根据实际解码格式确定的 MIME 类型
     * @param extension 与已验证格式对应的安全文件扩展名
     * @param width 解码后的图像宽度
     * @param height 解码后的图像高度
     * @param decoded 已完整解码的首帧像素数据
     */
    private record ImageDetails(String contentType, String extension, int width, int height,
            BufferedImage decoded) {}
}

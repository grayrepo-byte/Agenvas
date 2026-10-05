package dev.agenvas.asset.infrastructure;

import dev.agenvas.shared.i18n.ApiMessage;
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
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 将媒体流写入私有临时文件，完成字节与解码校验后以原子移动安装不可变对象。 */
@Component
public class LocalAssetStorage implements dev.agenvas.asset.storage.AssetStorage {

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
                throw invalid(ApiMessage.of("api.local-asset-storage.unable-to-parse-video-media"), "ASSET_INVALID_VIDEO");
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
            throw invalid(ApiMessage.of("api.local-asset-storage.video-must-be-decodable-mp4-and-no-longer-than-60"), "ASSET_INVALID_VIDEO");
        }
        int durationMs = Math.toIntExact(Math.round(duration * 1_000));
        if (durationMs < 1 || durationMs > 60_000) {
            throw invalid(ApiMessage.of("api.local-asset-storage.the-video-length-exceeds-the-supported-range"), "ASSET_INVALID_VIDEO");
        }
        return new VideoDetails(width, height, durationMs);
    }

    /** ffprobe 验证后的视频元数据。
     * @param width 视频流像素宽度
     * @param height 视频流像素高度
     * @param durationMs 视频时长，已验证为 1 到 60,000 毫秒
     */
    private record VideoDetails(int width, int height, int durationMs) {}

    /** 限流保存视频字节，实测容器与视频流后解码首帧并原子安装 MP4 和海报。 */
    public static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024;
    public static final int MAX_AUDIO_DURATION_MS = 600_000;
    public record StoredAudio(String objectKey, String contentType, long byteSize,
            String sha256, int durationMs) {}

    public <T> T withTaskAudioLock(UUID projectId, UUID assetId, Supplier<T> action) {
        return withTaskArchiveLock(projectId, assetId, "audio", action);
    }

    /** Actual container, stream and full decoding checks precede immutable installation. */
    public StoredAudio storeAudio(UUID projectId, UUID assetId, InputStream source) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(prepareProjectDirectory(projectId), ".audio-ingest-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(source, temporary, digest, MAX_AUDIO_BYTES);
            StoredAudio details = inspectAudio(temporary, "", size,
                    HexFormat.of().formatHex(digest.digest()));
            String extension = switch (details.contentType()) {
                case "audio/mpeg" -> ".mp3";
                case "audio/wav" -> ".wav";
                default -> ".ogg";
            };
            String key = projectId + "/" + assetId + extension;
            Path stable = checkedPath(key);
            if (Files.exists(stable, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Immutable audio already exists");
            Files.move(temporary, stable, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return new StoredAudio(key, details.contentType(), size, details.sha256(), details.durationMs());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Private audio archive failed", failure);
        } finally { cleanup(temporary); }
    }

    /** Recover installed bytes after a database failure without requesting synthesis again. */
    public java.util.Optional<StoredAudio> recoverAudio(UUID projectId, UUID assetId) {
        try {
            for (String extension : java.util.List.of(".mp3", ".wav", ".ogg")) {
                String key = projectId + "/" + assetId + extension;
                Path file = checkedPath(key);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) < 1 || Files.size(file) > MAX_AUDIO_BYTES)
                    throw new IllegalStateException("Recovered audio exceeds archive bounds");
                return java.util.Optional.of(inspectAudio(file, key, Files.size(file), sha256(file)));
            }
            return java.util.Optional.empty();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot recover task audio archive", failure);
        }
    }

    private StoredAudio inspectAudio(Path file, String key, long size, String hash) {
        try {
            JsonNode probe = mapper.readTree(mediaTools.ffprobe(java.util.List.of(
                    "-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries",
                    "stream=codec_type,codec_name:format=format_name,duration", "-of", "json", file.toString())));
            JsonNode streams = probe.path("streams");
            double seconds = probe.path("format").path("duration").asDouble();
            String format = probe.path("format").path("format_name").asText();
            String mime = switch (format) {
                case "mp3" -> "audio/mpeg";
                case "wav" -> "audio/wav";
                case "ogg" -> "audio/ogg";
                default -> null;
            };
            if (mime == null || "audio/ogg".equals(mime) && !"opus".equals(streams.path(0).path("codec_name").asText()) || !streams.isArray() || streams.size() != 1
                    || !"audio".equals(streams.path(0).path("codec_type").asText())
                    || !Double.isFinite(seconds) || seconds < 0.1
                    || seconds * 1000 > MAX_AUDIO_DURATION_MS)
                throw invalid(ApiMessage.of("api.local-asset-storage.audio-must-be-decodable-mp3-wav-or-ogg-and-no"), "ASSET_INVALID_AUDIO");
            mediaTools.ffmpeg(java.util.List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-protocol_whitelist", "file,pipe", "-i", file.toString(), "-map", "0:a:0",
                    "-f", "null", "-"));
            return new StoredAudio(key, mime, size, hash, (int) Math.round(seconds * 1000));
        } catch (MediaToolRunner.MediaToolException failure) {
            if (failure.invalidInput()) throw invalid(ApiMessage.of("api.local-asset-storage.audio-cannot-be-decoded"), "ASSET_INVALID_AUDIO");
            throw failure;
        }
    }

    public StoredVideo storeVideo(UUID projectId, UUID assetId, InputStream source) {
        Path temporary = null;
        Path posterTemporary = null;
        try {
            Path directory = prepareProjectDirectory(projectId);
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
                    throw invalid(ApiMessage.of("api.local-asset-storage.unable-to-decode-the-first-frame-of-the-video"), "ASSET_INVALID_VIDEO");
                }
                throw exception;
            }
            if (Files.size(posterTemporary) < 1 || Files.size(posterTemporary) > MAX_IMAGE_BYTES
                    || ImageIO.read(posterTemporary.toFile()) == null) {
                throw invalid(ApiMessage.of("api.local-asset-storage.unable-to-decode-the-first-frame-of-the-video"), "ASSET_INVALID_VIDEO");
            }
            String key = projectId + "/" + assetId + ".mp4";
            String posterKey = projectId + "/" + assetId + ".thumb.png";
            Path stable = checkedPath(key);
            Path posterStable = checkedPath(posterKey);
            return installVisualArchive(temporary, posterTemporary, stable, posterStable,
                    () -> new StoredVideo(key, size,
                            HexFormat.of().formatHex(digest.digest()), details.width(),
                            details.height(), details.durationMs(), posterKey,
                            Files.size(posterStable), sha256(posterStable)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Private video archive failed", exception);
        } finally {
            cleanup(temporary);
            cleanup(posterTemporary);
        }
    }

    /** 仅用服务端生成的项目和素材 ID 构造从不复用的图片文件键。 */
    public StoredImage storeImage(UUID projectId, UUID assetId, InputStream source) {
        Path temporary = null;
        Path thumbnailTemporary = null;
        try {
            Path directory = prepareProjectDirectory(projectId);
            temporary = Files.createTempFile(directory, ".ingest-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyBounded(source, temporary, digest, MAX_IMAGE_BYTES);
            ImageDetails details = inspectImage(temporary);
            thumbnailTemporary = Files.createTempFile(directory, ".thumb-", ".tmp");
            writeThumbnail(details.decoded(), thumbnailTemporary);
            String key = projectId + "/" + assetId + details.extension();
            String thumbnailKey = projectId + "/" + assetId + ".thumb.png";
            Path stable = checkedPath(key);
            Path thumbnailStable = checkedPath(thumbnailKey);
            try {
                return installVisualArchive(temporary, thumbnailTemporary, stable, thumbnailStable,
                        () -> new StoredImage(key, details.contentType(), size,
                                HexFormat.of().formatHex(digest.digest()), details.width(), details.height(),
                                thumbnailKey, Files.size(thumbnailStable), sha256(thumbnailStable)));
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("Asset volume must support atomic file moves", exception);
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Private asset archive failed", exception);
        } finally {
            cleanup(temporary);
            cleanup(thumbnailTemporary);
        }
    }

    /** 两个稳定文件及结果元数据全部就绪前失败，只回滚本次已成功移动的文件。 */
    private <T> T installVisualArchive(Path originalTemporary, Path previewTemporary,
            Path originalStable, Path previewStable, VisualArchiveResult<T> resultFactory)
            throws IOException, NoSuchAlgorithmException {
        boolean originalMoved = false;
        boolean previewMoved = false;
        boolean installed = false;
        try {
            Files.move(originalTemporary, originalStable, StandardCopyOption.ATOMIC_MOVE);
            originalMoved = true;
            Files.move(previewTemporary, previewStable, StandardCopyOption.ATOMIC_MOVE);
            previewMoved = true;
            T result = resultFactory.get();
            installed = true;
            return result;
        } finally {
            if (!installed) {
                if (originalMoved) cleanup(originalStable);
                if (previewMoved) cleanup(previewStable);
            }
        }
    }

    /** 归档元数据读取可能失败，必须仍处于稳定文件回滚范围内。 */
    @FunctionalInterface
    private interface VisualArchiveResult<T> {
        T get() throws IOException, NoSuchAlgorithmException;
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

    /**
     * 入库时生成最长边不超过 480 像素的 PNG 预览。画布图片卡片现在直接加载原图，
     * 预览仍然归档，供后续需要低带宽/低解码成本的列表功能使用。
     */
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
                            "ASSET_TOO_LARGE", ApiMessage.of("api.local-asset-storage.material-is-too-large"), ApiMessage.of("api.local-asset-storage.the-material-exceeds-the-archive-size-limit"), false);
                }
                output.write(buffer, 0, read);
            }
        }
        if (count == 0) {
            throw maximum == MAX_VIDEO_BYTES
                    ? invalid(ApiMessage.of("api.local-asset-storage.video-file-cannot-be-empty"), "ASSET_INVALID_VIDEO")
                    : maximum == MAX_AUDIO_BYTES ? invalid(ApiMessage.of("api.local-asset-storage.audio-file-cannot-be-empty"), "ASSET_INVALID_AUDIO")
                    : invalid(ApiMessage.of("api.local-asset-storage.image-file-cannot-be-empty"), "ASSET_INVALID_IMAGE");
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
                throw invalid(ApiMessage.of("api.local-asset-storage.unable-to-decode-picture"), "ASSET_INVALID_IMAGE");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid(ApiMessage.of("api.local-asset-storage.only-decodable-png-jpeg-or-webp-images-are-supported"), "ASSET_INVALID_IMAGE");
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
                    throw invalid(ApiMessage.of("api.local-asset-storage.this-image-encoding-is-not-supported-yet"), "ASSET_UNSUPPORTED_IMAGE");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw invalid(ApiMessage.of("api.local-asset-storage.image-pixels-exceed-40-mp-limit"), "ASSET_TOO_MANY_PIXELS");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width
                        || decoded.getHeight() != height) {
                    throw invalid(ApiMessage.of("api.local-asset-storage.image-decoding-failed"), "ASSET_INVALID_IMAGE");
                }
                return new ImageDetails(contentType, extension, width, height, decoded);
            } catch (IOException | IndexOutOfBoundsException exception) {
                throw invalid(ApiMessage.of("api.local-asset-storage.image-decoding-failed"), "ASSET_INVALID_IMAGE");
            } finally {
                reader.dispose();
            }
        }
    }

    /** 将不可解码、超限或不支持的媒体映射为稳定 422 错误。 */
    private ApiProblemException invalid(ApiMessage detail, String code) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                ApiMessage.of("api.local-asset-storage.invalid-material"), detail, false);
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

package dev.agenvas.asset.application;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 校验项目权限并管理私有媒体归档；文件先安装，数据库只记录已校验的 READY 元数据。 */
@Service
public class AssetService {

    /** 对上传和读取执行所有者、项目及归档状态检查。 */
    private final ProjectService projects;
    /** 保存 READY 素材元数据并按项目查询。 */
    private final AssetRepository assets;
    /** 验证字节、解码媒体并安装不可变文件；视频额外提取封面帧。 */
    private final LocalAssetStorage storage;
    /** 将素材就绪状态与项目事件一起提交。 */
    private final ProjectEventService events;
    /** 构造不暴露存储路径的素材事件负载。 */
    private final ObjectMapper mapper;
    /** 为素材元数据提供统一创建时间。 */
    private final Clock clock;

    /** 组装项目授权、文件存储和数据库事件发布边界。
     * @param projects 校验项目所有权与活动状态
     * @param assets 持久化已就绪素材元数据
     * @param storage 验证并安装媒体文件
     * @param events 与素材状态同事务写入项目事件
     * @param mapper 生成不暴露文件路径的事件负载
     * @param clock 提供可控的素材创建时间
     */
    public AssetService(ProjectService projects, AssetRepository assets,
            LocalAssetStorage storage, ProjectEventService events, ObjectMapper mapper,
            Clock clock) {
        this.projects = projects;
        this.assets = assets;
        this.storage = storage;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 流式接收用户图片，在字节、像素和解码校验通过后才写 READY；不生成预览副本。 */
    public Asset archiveImage(UUID ownerId, UUID projectId, InputStream input) {
        projects.requireActiveProject(ownerId, projectId);
        UUID assetId = UUID.randomUUID();
        LocalAssetStorage.StoredImage stored = storage.storeImage(projectId, assetId, input);
        return publishImage(ownerId, projectId, assetId, stored, true, false);
    }

    /** 以任务 ID 派生稳定素材 ID，并在共享卷锁内恢复或完成唯一图片归档。 */
    public Asset archiveTaskImage(UUID ownerId, UUID projectId, UUID taskId,
            Supplier<InputStream> download) {
        projects.get(ownerId, projectId);
        UUID assetId = taskImageAssetId(taskId);
        return storage.withTaskImageLock(projectId, assetId,
                () -> archiveTaskImageLocked(ownerId, projectId, assetId, download));
    }

    /** 锁内先核对已有元数据和文件，再下载、安装或恢复文件并发布 READY。 */
    private Asset archiveTaskImageLocked(UUID ownerId, UUID projectId, UUID assetId,
            Supplier<InputStream> download) {
        var recovered = storage.recoverImage(projectId, assetId);
        var existing = assets.find(projectId, assetId);
        if (existing.isPresent()) {
            LocalAssetStorage.StoredImage file = recovered.orElseThrow(() ->
                    new IllegalStateException("Task image metadata exists without original file"));
            Asset asset = existing.get();
            if (!asset.objectKey().equals(file.objectKey())
                    || !asset.sha256().equals(file.sha256())
                    || asset.byteSize() != file.byteSize()) {
                throw new IllegalStateException("Task image bytes differ from READY metadata");
            }
            return asset;
        }
        if (recovered.isPresent()) {
            return publishImage(ownerId, projectId, assetId, recovered.get(), false, true);
        }
        try (InputStream input = download.get()) {
            if (input == null) throw new IllegalStateException("Image download returned no stream");
            LocalAssetStorage.StoredImage stored = storage.storeImage(projectId, assetId, input);
            // 数据库写入失败时保留任务键文件，后续轮询可核对文件并补交元数据。
            return publishImage(ownerId, projectId, assetId, stored, false, true);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close generated image stream", failure);
        }
    }

    /** 使用带版本的命名空间派生任务图片 ID，避免与随机用户上传 ID 冲突。 */
    public static UUID taskImageAssetId(UUID taskId) {
        if (taskId == null) throw new IllegalArgumentException("taskId is required");
        return UUID.nameUUIDFromBytes(("agenvas:task-image:v1:" + taskId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成素材时间戳并对齐 PostgreSQL timestamptz 的微秒精度。直接用纳秒会让内存值与读回值
     * 相差一个微秒，使"创建后返回的对象"与"随后从库读出的同一素材"不再相等。
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** 图片原图落盘后才在项目事件事务中创建 READY 元数据；图片不生成缩略图。 */
    private Asset publishImage(UUID ownerId, UUID projectId, UUID assetId,
            LocalAssetStorage.StoredImage stored, boolean discardOnFailure,
            boolean taskOutput) {
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.IMAGE,
                stored.objectKey(), stored.contentType(), stored.byteSize(),
                stored.sha256(), stored.width(), stored.height(), null,
                null, null, null, now());
        try {
            events.recordChange(ownerId, projectId, () -> {
                if (taskOutput) {
                    projects.get(ownerId, projectId);
                } else {
                    projects.requireActiveProject(ownerId, projectId);
                }
                assets.insert(asset);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("assetId", asset.id().toString());
                payload.put("contentType", asset.contentType());
                payload.put("byteSize", asset.byteSize());
                return ProjectEventService.Change.changed(asset,
                        new ProjectEventService.EventDraft("asset.ready", 1,
                                asset.id(), 0, payload));
            });
            return asset;
        } catch (RuntimeException exception) {
            if (discardOnFailure) {
                storage.discard(stored.objectKey());
            }
            throw exception;
        }
    }

    /** 校验实际视频容器、解码结果、时长和分辨率，提取海报图后才发布 READY。 */
    public Asset archiveVideo(UUID ownerId, UUID projectId, InputStream input) {
        projects.requireActiveProject(ownerId, projectId);
        UUID assetId = UUID.randomUUID();
        LocalAssetStorage.StoredVideo stored = storage.storeVideo(projectId, assetId, input);
        return publishVideo(ownerId, projectId, assetId, stored, true, false);
    }

    /** 以任务 ID 派生稳定素材 ID，在共享卷锁内恢复唯一 MP4 和海报图。 */
    public Asset archiveTaskVideo(UUID ownerId, UUID projectId, UUID taskId,
            Supplier<InputStream> download) {
        projects.get(ownerId, projectId);
        UUID assetId = taskVideoAssetId(taskId);
        return storage.withTaskVideoLock(projectId, assetId,
                () -> archiveTaskVideoLocked(ownerId, projectId, assetId, download));
    }

    /** 与图片归档相同，先核对文件和元数据，再下载或恢复后发布视频素材。 */
    private Asset archiveTaskVideoLocked(UUID ownerId, UUID projectId, UUID assetId,
            Supplier<InputStream> download) {
        var recovered = storage.recoverVideo(projectId, assetId);
        var existing = assets.find(projectId, assetId);
        if (existing.isPresent()) {
            LocalAssetStorage.StoredVideo file = recovered.orElseThrow(() ->
                    new IllegalStateException("Task video metadata exists without original file"));
            Asset asset = existing.get();
            if (asset.mediaKind() != Asset.MediaKind.VIDEO
                    || !asset.objectKey().equals(file.objectKey())
                    || !asset.sha256().equals(file.sha256())
                    || asset.byteSize() != file.byteSize()
                    || !asset.thumbnailSha256().equals(file.thumbnailSha256())) {
                throw new IllegalStateException("Task video bytes differ from READY metadata");
            }
            return asset;
        }
        if (recovered.isPresent()) {
            return publishVideo(ownerId, projectId, assetId, recovered.get(), false, true);
        }
        try (InputStream input = download.get()) {
            if (input == null) throw new IllegalStateException("Video download returned no stream");
            LocalAssetStorage.StoredVideo stored = storage.storeVideo(projectId, assetId, input);
            return publishVideo(ownerId, projectId, assetId, stored, false, true);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close generated video stream", failure);
        }
    }

    /** 派生与图片及随机上传隔离的任务视频素材 ID。 */
    public static UUID taskVideoAssetId(UUID taskId) {
        if (taskId == null) throw new IllegalArgumentException("taskId is required");
        return UUID.nameUUIDFromBytes(("agenvas:task-video:v1:" + taskId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 视频原件及 PNG 海报均安装成功后，才提交 READY 元数据和事件。 */
    private Asset publishVideo(UUID ownerId, UUID projectId, UUID assetId,
            LocalAssetStorage.StoredVideo stored, boolean discardOnFailure,
            boolean taskOutput) {
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.VIDEO,
                stored.objectKey(), "video/mp4", stored.byteSize(), stored.sha256(),
                stored.width(), stored.height(), stored.durationMs(), stored.thumbnailKey(),
                stored.thumbnailByteSize(), stored.thumbnailSha256(), now());
        try {
            events.recordChange(ownerId, projectId, () -> {
                if (taskOutput) {
                    projects.get(ownerId, projectId);
                } else {
                    projects.requireActiveProject(ownerId, projectId);
                }
                assets.insert(asset);
                ObjectNode payload = mapper.createObjectNode();
                payload.put("assetId", asset.id().toString());
                payload.put("contentType", asset.contentType());
                payload.put("byteSize", asset.byteSize());
                return ProjectEventService.Change.changed(asset,
                        new ProjectEventService.EventDraft("asset.ready", 1,
                                asset.id(), 0, payload));
            });
            return asset;
        } catch (RuntimeException exception) {
            if (discardOnFailure) {
                try {
                    storage.discard(stored.objectKey());
                } finally {
                    storage.discard(stored.thumbnailKey());
                }
            }
            throw exception;
        }
    }

    /** 先核验项目所有权，再检查 READY 文件路径和字节大小后返回私有文件。 */
    public AssetFile get(UUID ownerId, UUID projectId, UUID assetId) {
        projects.get(ownerId, projectId);
        Asset asset = assets.find(projectId, assetId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND",
                        "素材不存在", "找不到该项目中的素材。", false));
        Path path = checkedReadyFile(asset.objectKey(), asset.byteSize());
        return new AssetFile(asset, path);
    }

    /** 向项目清单组装器返回已鉴权的素材记录，不返回磁盘路径。 */
    public List<Asset> listProjectAssets(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return assets.listProjectAssets(projectId);
    }

    /** 产物引用媒体前确认素材已 READY、属于该项目且类型匹配。 */
    public Asset requireReadyMedia(UUID ownerId, UUID projectId, UUID assetId,
            Asset.MediaKind expectedKind) {
        Asset asset = get(ownerId, projectId, assetId).asset();
        if (asset.mediaKind() != expectedKind) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "ARTIFACT_ASSET_KIND_INVALID",
                    "素材类型不匹配", "产物引用的素材类型不匹配。", false);
        }
        return asset;
    }

    /**
     * 使用与原素材相同的项目权限返回预先生成的视频封面帧。图片不生成缩略图，
     * 因此图片素材访问此接口会返回 404。
     */
    public ThumbnailFile getThumbnail(UUID ownerId, UUID projectId, UUID assetId) {
        Asset asset = get(ownerId, projectId, assetId).asset();
        if (asset.thumbnailKey() == null || asset.thumbnailByteSize() == null) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_THUMBNAIL_NOT_FOUND",
                    "预览不存在", "该素材没有可用的缩略图。", false);
        }
        return new ThumbnailFile(asset,
                checkedReadyFile(asset.thumbnailKey(), asset.thumbnailByteSize()));
    }

    /** 通过存储层路径白名单解析文件，并拒绝符号链接、非普通文件或大小不符。 */
    private Path checkedReadyFile(String objectKey, long expectedSize) {
        Path path = storage.checkedPath(objectKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("READY asset file is missing");
        }
        try {
            if (Files.size(path) != expectedSize) {
                throw new IllegalStateException("READY asset file size differs from metadata");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot inspect READY asset file", exception);
        }
        return path;
    }

    /** 已鉴权的原始素材元数据和服务端内部文件路径。
     * @param asset 项目内可访问的素材记录
     * @param path 已规范化并核验大小的服务端文件路径，不得直接序列化给客户端
     */
    public record AssetFile(Asset asset, Path path) {}

    /** 已鉴权的视频封面帧元数据和受大小限制的 PNG 文件路径。
     * @param asset 所属素材记录
     * @param path 经项目授权及路径边界校验的封面帧文件
     */
    public record ThumbnailFile(Asset asset, Path path) {}
}

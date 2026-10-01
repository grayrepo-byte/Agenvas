package dev.agenvas.asset.application;

import dev.agenvas.shared.i18n.ApiMessage;
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
    /** 验证字节、解码媒体、生成缩略图并安装不可变文件。 */
    private final dev.agenvas.asset.storage.AssetStorage storage;
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
            @org.springframework.beans.factory.annotation.Qualifier("configuredAssetStorage")
            dev.agenvas.asset.storage.AssetStorage storage, ProjectEventService events, ObjectMapper mapper,
            Clock clock) {
        this.projects = projects;
        this.assets = assets;
        this.storage = storage;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Prepares fixed library bytes outside the caller's final business transaction. */
    public Asset prepareLibraryImport(UUID owner, UUID project, UUID id, Asset.MediaKind kind, Path source) {
        projects.requireActiveProject(owner, project);
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            return switch (kind) {
                case IMAGE -> storage.withTaskImageLock(project, id, () -> {
                    var file = storage.recoverImage(project, id).orElseGet(() -> storage.storeImage(project, id, input));
                    return new Asset(id, project, kind, file.objectKey(), file.contentType(), file.byteSize(),
                            file.sha256(), file.width(), file.height(), null, file.thumbnailKey(),
                            file.thumbnailByteSize(), file.thumbnailSha256(), now());
                });
                case VIDEO -> storage.withTaskVideoLock(project, id, () -> {
                    var file = storage.recoverVideo(project, id).orElseGet(() -> storage.storeVideo(project, id, input));
                    return new Asset(id, project, kind, file.objectKey(), "video/mp4", file.byteSize(),
                            file.sha256(), file.width(), file.height(), file.durationMs(), file.thumbnailKey(),
                            file.thumbnailByteSize(), file.thumbnailSha256(), now());
                });
                case AUDIO -> storage.withTaskAudioLock(project, id, () -> {
                    var file = storage.recoverAudio(project, id).orElseGet(() -> storage.storeAudio(project, id, input));
                    return new Asset(id, project, kind, file.objectKey(), file.contentType(), file.byteSize(),
                            file.sha256(), null, null, file.durationMs(), null, null, null, now());
                });
            };
        } catch (IOException failure) { throw new IllegalStateException("Cannot read library import", failure); }
    }

    /** Only rejected, unregistered imports may be removed; READY project content is never garbage. */
    public void discardUnregisteredLibraryImport(UUID owner, Asset prepared) {
        projects.get(owner, prepared.projectId());
        if (assets.find(prepared.projectId(), prepared.id()).isPresent())
            throw new IllegalStateException("Registered project media cannot be discarded");
        if (!storage.belongsToProject(prepared.objectKey(), prepared.projectId()))
            throw new IllegalStateException("Import partition mismatch");
        if (prepared.thumbnailKey() != null && !storage.belongsToProject(prepared.thumbnailKey(), prepared.projectId()))
            throw new IllegalStateException("Thumbnail partition mismatch");
        storage.discard(prepared.objectKey());
        if (prepared.thumbnailKey() != null) storage.discard(prepared.thumbnailKey());
    }

    /** Registers prepared immutable bytes in the same transaction as the imported content and events. */
    public void registerLibraryImport(UUID owner, Asset asset) {
        events.recordChange(owner, asset.projectId(), () -> {
            projects.requireActiveProject(owner, asset.projectId());
            var existing = assets.find(asset.projectId(), asset.id());
            if (existing.isPresent()) {
                if (!existing.get().sha256().equals(asset.sha256()) || existing.get().mediaKind() != asset.mediaKind())
                    throw new IllegalStateException("Imported media differs from READY metadata");
                return ProjectEventService.Change.unchanged(asset);
            }
            assets.insert(asset);
            return ProjectEventService.Change.changed(asset, new ProjectEventService.EventDraft("asset.ready", 1,
                    asset.id(), 0, mapper.createObjectNode().put("assetId", asset.id().toString())
                    .put("contentType", asset.contentType()).put("byteSize", asset.byteSize())));
        });
    }

    /** Authenticated audio upload; MIME and duration come from actual decoding. */
    public Asset archiveAudio(UUID ownerId, UUID projectId, InputStream input) {
        projects.requireActiveProject(ownerId, projectId);
        UUID id = UUID.randomUUID();
        var stored = storage.storeAudio(projectId, id, input);
        try { return publishAudio(ownerId, projectId, id, stored, false); }
        catch (RuntimeException failure) { storage.discard(stored.objectKey()); throw failure; }
    }

    /** Recovery reads installed bytes only; no provider request is made. */
    public java.util.Optional<Asset> recoverTaskAudio(UUID ownerId, UUID projectId, UUID taskId) {
        projects.get(ownerId, projectId);
        UUID id = UUID.nameUUIDFromBytes(("agenvas:task-audio:v1:" + taskId).getBytes(StandardCharsets.UTF_8));
        return storage.withTaskAudioLock(projectId, id, () -> {
            var stored = storage.recoverAudio(projectId, id);
            if (stored.isEmpty()) return java.util.Optional.empty();
            var existing = assets.find(projectId, id);
            if (existing.isPresent()) {
                Asset asset = existing.get();
                if (asset.mediaKind() != Asset.MediaKind.AUDIO || !asset.objectKey().equals(stored.get().objectKey())
                        || !asset.sha256().equals(stored.get().sha256()) || asset.byteSize() != stored.get().byteSize())
                    throw new IllegalStateException("Audio bytes differ from READY metadata");
                return existing;
            }
            return java.util.Optional.of(publishAudio(ownerId, projectId, id, stored.get(), true));
        });
    }

    public Asset archiveTaskAudio(UUID ownerId, UUID projectId, UUID taskId,
            Supplier<InputStream> download) {
        projects.get(ownerId, projectId);
        UUID id = UUID.nameUUIDFromBytes(("agenvas:task-audio:v1:" + taskId).getBytes(StandardCharsets.UTF_8));
        return storage.withTaskAudioLock(projectId, id, () -> {
            var recovered = storage.recoverAudio(projectId, id);
            var existing = assets.find(projectId, id);
            if (existing.isPresent()) {
                var file = recovered.orElseThrow(() -> new IllegalStateException("Audio file missing"));
                Asset asset = existing.get();
                if (asset.mediaKind() != Asset.MediaKind.AUDIO || !asset.objectKey().equals(file.objectKey())
                        || !asset.sha256().equals(file.sha256()) || asset.byteSize() != file.byteSize())
                    throw new IllegalStateException("Audio bytes differ from READY metadata");
                return asset;
            }
            if (recovered.isPresent()) return publishAudio(ownerId, projectId, id, recovered.get(), true);
            try (InputStream input = download.get()) {
                if (input == null) throw new IllegalStateException("Audio stream missing");
                return publishAudio(ownerId, projectId, id, storage.storeAudio(projectId, id, input), true);
            } catch (IOException failure) { throw new IllegalStateException("Cannot close audio stream", failure); }
        });
    }

    private Asset publishAudio(UUID ownerId, UUID projectId, UUID id,
            LocalAssetStorage.StoredAudio stored, boolean taskOutput) {
        Asset asset = new Asset(id, projectId, Asset.MediaKind.AUDIO, stored.objectKey(),
                stored.contentType(), stored.byteSize(), stored.sha256(), null, null,
                stored.durationMs(), null, null, null, now());
        return events.recordChange(ownerId, projectId, () -> {
            if (taskOutput) projects.get(ownerId, projectId); else projects.requireActiveProject(ownerId, projectId);
            assets.insert(asset);
            ObjectNode payload = mapper.createObjectNode().put("assetId", id.toString())
                    .put("contentType", asset.contentType()).put("byteSize", asset.byteSize());
            return ProjectEventService.Change.changed(asset,
                    new ProjectEventService.EventDraft("asset.ready", 1, id, 0, payload));
        }).value();
    }

    /** 流式接收用户图片，在字节、像素和解码校验通过并生成缩略图后才写 READY。 */
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
                    || asset.byteSize() != file.byteSize()
                    || !asset.thumbnailSha256().equals(file.thumbnailSha256())) {
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

    /** 图片原件与缩略图都已落盘后才在项目事件事务中创建 READY 元数据。 */
    private Asset publishImage(UUID ownerId, UUID projectId, UUID assetId,
            LocalAssetStorage.StoredImage stored, boolean discardOnFailure,
            boolean taskOutput) {
        Asset asset = new Asset(assetId, projectId, Asset.MediaKind.IMAGE,
                stored.objectKey(), stored.contentType(), stored.byteSize(),
                stored.sha256(), stored.width(), stored.height(), null,
                stored.thumbnailKey(), stored.thumbnailByteSize(),
                stored.thumbnailSha256(), now());
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
                        ApiMessage.of("api.asset-service.material-does-not-exist"), ApiMessage.of("api.asset-service.the-assets-in-this-project-were-not-found"), false));
        Path path = checkedReadyFile(asset.objectKey(), asset.byteSize());
        return new AssetFile(asset, path);
    }

    /** READY metadata is the database truth; creating an input reference never performs network I/O. */
    public Asset metadata(UUID ownerId, UUID projectId, UUID assetId) {
        projects.get(ownerId, projectId);
        return assets.find(projectId, assetId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                "ASSET_NOT_FOUND", ApiMessage.of("api.asset-service.material-does-not-exist"), ApiMessage.of("api.asset-service.the-assets-in-this-project-were-not-found"), false));
    }

    /** Authorize before accessing storage; metadata/HEAD never materialize a remote video. */
    public AssetContent content(UUID ownerId, UUID projectId, UUID assetId, boolean thumbnail) {
        projects.get(ownerId, projectId);
        Asset asset = assets.find(projectId, assetId).orElseThrow(() ->
                new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", ApiMessage.of("api.asset-service.material-does-not-exist"), ApiMessage.of("api.asset-service.the-assets-in-this-project-were-not-found"), false));
        if (thumbnail && (asset.thumbnailKey() == null || asset.thumbnailByteSize() == null))
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_THUMBNAIL_NOT_FOUND", ApiMessage.of("api.asset-service.preview-does-not-exist"), ApiMessage.of("api.asset-service.there-is-no-thumbnail-available-for-this-footage"), false);
        String key = thumbnail ? asset.thumbnailKey() : asset.objectKey();
        long size = thumbnail ? asset.thumbnailByteSize() : asset.byteSize();
        String hash = thumbnail ? asset.thumbnailSha256() : asset.sha256();
        storage.verify(key, size, hash);
        return new AssetContent(asset, key, size, thumbnail ? "image/png" : asset.contentType());
    }

    /** Open only a previously authorized descriptor; the stream owns its local/remote resources. */
    public InputStream open(AssetContent content, long start, long length) throws IOException {
        return storage.open(content.objectKey(), start, length, content.size());
    }

    public record AssetContent(Asset asset, String objectKey, long size, String contentType) {}

    /** 向项目清单组装器返回已鉴权的素材记录，不返回磁盘路径。 */
    public List<Asset> listProjectAssets(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return assets.listProjectAssets(projectId);
    }

    /** 产物引用媒体前确认素材已 READY、属于该项目且类型匹配。 */
    public Asset requireReadyMedia(UUID ownerId, UUID projectId, UUID assetId,
            Asset.MediaKind expectedKind) {
        Asset asset = metadata(ownerId, projectId, assetId);
        if (asset.mediaKind() != expectedKind) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "ARTIFACT_ASSET_KIND_INVALID",
                    ApiMessage.of("api.asset-service.material-type-mismatch"), ApiMessage.of("api.asset-service.the-material-type-referenced-by-the-product-does-not-match"), false);
        }
        return asset;
    }

    /** 使用与原素材相同的项目权限返回预先生成的小型 PNG 缩略图。 */
    public ThumbnailFile getThumbnail(UUID ownerId, UUID projectId, UUID assetId) {
        Asset asset = metadata(ownerId, projectId, assetId);
        if (asset.thumbnailKey() == null || asset.thumbnailByteSize() == null) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "ASSET_THUMBNAIL_NOT_FOUND",
                    ApiMessage.of("api.asset-service.preview-does-not-exist"), ApiMessage.of("api.asset-service.there-is-no-thumbnail-available-for-this-footage"), false);
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

    /** 已鉴权的缩略图元数据和受大小限制的 PNG 文件路径。
     * @param asset 所属素材记录
     * @param path 经项目授权及路径边界校验的缩略图文件
     */
    public record ThumbnailFile(Asset asset, Path path) {}
}

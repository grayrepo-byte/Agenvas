package dev.agenvas.asset.infrastructure;

import static dev.agenvas.db.Tables.ASSET;

import dev.agenvas.asset.application.AssetRepository;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.db.tables.records.AssetRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** PostgreSQL 素材元数据账本实现；媒体字节由本地存储管理。 */
@Repository
public class JooqAssetRepository implements AssetRepository {

    /** 执行 jOOQ 查询并将结果映射为领域素材记录。 */
    private final DSLContext dsl;

    /** 注入 jOOQ 查询上下文。
     * @param dsl jOOQ 查询上下文
     */
    public JooqAssetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 插入已完成文件归档与媒体校验的素材记录；记录存在即表示归档完成。
     * @param asset 素材元数据及不可变对象键
     */
    @Override
    public void insert(Asset asset) {
        dsl.insertInto(ASSET)
                .set(ASSET.ID, asset.id())
                .set(ASSET.PROJECT_ID, asset.projectId())
                .set(ASSET.MEDIA_KIND, asset.mediaKind().name())
                .set(ASSET.OBJECT_KEY, asset.objectKey())
                .set(ASSET.CONTENT_TYPE, asset.contentType())
                .set(ASSET.BYTE_SIZE, asset.byteSize())
                .set(ASSET.SHA256, asset.sha256())
                .set(ASSET.WIDTH, asset.width())
                .set(ASSET.HEIGHT, asset.height())
                .set(ASSET.DURATION_MS, asset.durationMs())
                .set(ASSET.THUMBNAIL_KEY, asset.thumbnailKey())
                .set(ASSET.THUMBNAIL_BYTE_SIZE, asset.thumbnailByteSize())
                .set(ASSET.THUMBNAIL_SHA256, asset.thumbnailSha256())
                .set(ASSET.CREATED_AT, OffsetDateTime.ofInstant(asset.createdAt(), ZoneOffset.UTC))
                .execute();
    }

    /** 按项目和素材 ID 查询，避免跨项目 ID 被当作有效引用。
     * @param projectId 资源作用域项目
     * @param assetId 素材 UUID
     * @return 匹配的素材记录
     */
    @Override
    public Optional<Asset> find(UUID projectId, UUID assetId) {
        return dsl.selectFrom(ASSET)
                .where(ASSET.PROJECT_ID.eq(projectId))
                .and(ASSET.ID.eq(assetId))
                .fetchOptional(this::map);
    }

    @Override
    public Optional<Asset> findAvailable(UUID projectId, UUID assetId) {
        return dsl.selectFrom(ASSET)
                .where(ASSET.PROJECT_ID.eq(projectId), ASSET.ID.eq(assetId),
                        ASSET.STUDIO_PURGE_AFTER.isNull())
                .fetchOptional(this::map);
    }

    @Override
    public boolean queueStudioImagePurge(UUID projectId, UUID assetId, Instant purgeAfter) {
        return dsl.update(ASSET)
                .set(ASSET.STUDIO_PURGE_AFTER, utc(purgeAfter))
                .where(ASSET.PROJECT_ID.eq(projectId), ASSET.ID.eq(assetId),
                        ASSET.MEDIA_KIND.eq(Asset.MediaKind.IMAGE.name()), ASSET.STUDIO_PURGE_AFTER.isNull())
                .execute() == 1;
    }

    @Override
    public List<Asset> findDueStudioImagePurges(Instant now, int limit) {
        return dsl.selectFrom(ASSET)
                .where(due(now))
                .orderBy(ASSET.STUDIO_PURGE_AFTER, ASSET.ID)
                .limit(limit)
                .fetch(this::map);
    }

    @Override
    public boolean isStudioImagePurgeDue(UUID projectId, UUID assetId, Instant now) {
        return dsl.fetchExists(dsl.selectOne().from(ASSET)
                .where(ASSET.PROJECT_ID.eq(projectId), ASSET.ID.eq(assetId), due(now)));
    }

    @Override
    public void markStudioImagePurged(UUID projectId, UUID assetId, Instant now) {
        dsl.update(ASSET)
                .set(ASSET.STUDIO_PURGED_AT, utc(now))
                .where(ASSET.PROJECT_ID.eq(projectId), ASSET.ID.eq(assetId),
                        ASSET.STUDIO_PURGE_AFTER.isNotNull(), ASSET.STUDIO_PURGED_AT.isNull())
                .execute();
    }

    @Override
    public void delayStudioImagePurge(UUID projectId, UUID assetId, Instant now, Instant retryAt) {
        dsl.update(ASSET)
                .set(ASSET.STUDIO_PURGE_AFTER, utc(retryAt))
                .where(ASSET.PROJECT_ID.eq(projectId), ASSET.ID.eq(assetId), due(now))
                .execute();
    }

    private static Condition due(Instant now) {
        return ASSET.STUDIO_PURGED_AT.isNull().and(ASSET.STUDIO_PURGE_AFTER.le(utc(now)));
    }

    private static OffsetDateTime utc(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    /** 稳定排序读取项目素材，供画布快照生成使用。
     * @param projectId 要读取的项目
     * @return 按创建时间和 UUID 排序的素材记录
     */
    @Override
    public List<Asset> listProjectAssets(UUID projectId) {
        return dsl.selectFrom(ASSET)
                .where(ASSET.PROJECT_ID.eq(projectId))
                .and(ASSET.STUDIO_PURGE_AFTER.isNull())
                .orderBy(ASSET.CREATED_AT, ASSET.ID)
                .fetch(this::map);
    }

    /** 还原素材元数据；图片的时长、缩略图及像素尺寸列可空。 */
    private Asset map(AssetRecord row) {
        return new Asset(
                row.getId(),
                row.getProjectId(),
                Asset.MediaKind.valueOf(row.getMediaKind()),
                row.getObjectKey(),
                row.getContentType(),
                row.getByteSize(),
                row.getSha256(),
                row.getWidth(),
                row.getHeight(),
                row.getDurationMs(),
                row.getThumbnailKey(),
                row.getThumbnailByteSize(),
                row.getThumbnailSha256(),
                row.getCreatedAt().toInstant());
    }
}

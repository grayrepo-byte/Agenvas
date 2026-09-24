package dev.agenvas.asset.infrastructure;

import dev.agenvas.asset.application.AssetRepository;
import dev.agenvas.asset.domain.Asset;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL 素材元数据账本实现；媒体字节由本地存储管理。 */
@Repository
public class JdbcAssetRepository implements AssetRepository {

    /** 执行参数化 SQL 并将查询结果映射为领域素材记录。 */
    private final JdbcClient jdbc;

    /** 注入带参数绑定的 JDBC 客户端。
     * @param jdbc Spring JDBC 执行器
     */
    public JdbcAssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入已完成文件归档与媒体校验的 READY 记录。
     * @param asset 素材元数据及不可变对象键
     */
    @Override
    public void insert(Asset asset) {
        jdbc.sql("""
                insert into asset (id, project_id, media_kind, status, object_key,
                    content_type, byte_size, sha256, width, height, duration_ms,
                    thumbnail_key, thumbnail_byte_size, thumbnail_sha256, created_at)
                values (:id, :projectId, :kind, 'READY', :objectKey, :contentType,
                    :byteSize, :sha256, :width, :height, :durationMs,
                    :thumbnailKey, :thumbnailByteSize, :thumbnailSha256, :createdAt)
                """)
                .param("id", asset.id())
                .param("projectId", asset.projectId())
                .param("kind", asset.mediaKind().name())
                .param("objectKey", asset.objectKey())
                .param("contentType", asset.contentType())
                .param("byteSize", asset.byteSize())
                .param("sha256", asset.sha256())
                .param("width", asset.width())
                .param("height", asset.height())
                .param("durationMs", asset.durationMs(), java.sql.Types.INTEGER)
                .param("thumbnailKey", asset.thumbnailKey(), java.sql.Types.VARCHAR)
                .param("thumbnailByteSize", asset.thumbnailByteSize(), java.sql.Types.BIGINT)
                .param("thumbnailSha256", asset.thumbnailSha256(), java.sql.Types.VARCHAR)
                .param("createdAt", OffsetDateTime.ofInstant(asset.createdAt(), ZoneOffset.UTC))
                .update();
    }

    /** 按项目和素材 ID 查询，避免跨项目 ID 被当作有效引用。
     * @param projectId 资源作用域项目
     * @param assetId 素材 UUID
     * @return 匹配的素材记录
     */
    @Override
    public Optional<Asset> find(UUID projectId, UUID assetId) {
        return jdbc.sql("""
                select id, project_id, media_kind, object_key, content_type,
                    byte_size, sha256, width, height, duration_ms,
                    thumbnail_key, thumbnail_byte_size, thumbnail_sha256, created_at
                from asset where project_id = :projectId and id = :assetId
                """)
                .param("projectId", projectId)
                .param("assetId", assetId)
                .query((rs, row) -> new Asset(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        Asset.MediaKind.valueOf(rs.getString("media_kind")),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getLong("byte_size"),
                        rs.getString("sha256"),
                        rs.getObject("width", Integer.class),
                        rs.getObject("height", Integer.class),
                        rs.getObject("duration_ms", Integer.class),
                        rs.getString("thumbnail_key"),
                        rs.getObject("thumbnail_byte_size", Long.class),
                        rs.getString("thumbnail_sha256"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** 稳定排序读取项目素材，供画布快照生成使用。
     * @param projectId 要读取的项目
     * @return 按创建时间和 UUID 排序的素材记录
     */
    @Override
    public List<Asset> listProjectAssets(UUID projectId) {
        return jdbc.sql("""
                        select id, project_id, media_kind, object_key, content_type,
                            byte_size, sha256, width, height, duration_ms,
                            thumbnail_key, thumbnail_byte_size, thumbnail_sha256, created_at
                        from asset where project_id = :projectId
                        order by created_at, id
                        """)
                .param("projectId", projectId)
                .query((rs, row) -> new Asset(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        Asset.MediaKind.valueOf(rs.getString("media_kind")),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getLong("byte_size"),
                        rs.getString("sha256"),
                        rs.getObject("width", Integer.class),
                        rs.getObject("height", Integer.class),
                        rs.getObject("duration_ms", Integer.class),
                        rs.getString("thumbnail_key"),
                        rs.getObject("thumbnail_byte_size", Long.class),
                        rs.getString("thumbnail_sha256"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}

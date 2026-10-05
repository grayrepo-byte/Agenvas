package dev.agenvas.asset.application;

import dev.agenvas.asset.domain.Asset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** 仅持久化已完成归档的文件元数据，读取始终受项目范围限制。 */
public interface AssetRepository {

    /** 媒体文件原子安装成功后插入 READY 素材记录。 */
    void insert(Asset asset);

    /** 仅在已授权项目中查找素材。 */
    Optional<Asset> find(UUID projectId, UUID assetId);

    /** 列出项目清单所需的私有媒体元数据，不返回对象路径或文件字节。 */
    List<Asset> listProjectAssets(UUID projectId);
}

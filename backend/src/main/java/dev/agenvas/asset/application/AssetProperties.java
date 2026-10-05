package dev.agenvas.asset.application;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 本地媒体对象的私有归档目录；部署时应将此路径挂载到持久私有卷。
 * @param root 素材和缩略图文件的归档根路径
 */
@ConfigurationProperties(prefix = "agenvas.storage")
public record AssetProperties(Path root) {

    /** 未配置持久卷时使用可写的本地开发目录。 */
    public AssetProperties {
        root = root == null ? Path.of("data", "assets") : root;
    }
}

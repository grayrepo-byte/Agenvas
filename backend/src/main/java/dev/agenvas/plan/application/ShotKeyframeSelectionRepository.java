package dev.agenvas.plan.application;

import java.util.Optional;
import java.util.UUID;

/** 关键帧显式选择的项目范围持久化边界。 */
public interface ShotKeyframeSelectionRepository {

    /** 读取镜头已选定的精确图片版本。 */
    Optional<ShotKeyframeSelection> find(UUID projectId, UUID shotArtifactId);

    /** 创建首次选择；已有选择时返回 false。 */
    boolean insert(ShotKeyframeSelection selection);

    /** 仅在观测到的版本匹配时替换选择。 */
    boolean update(ShotKeyframeSelection selection, long expectedVersion);
}

package dev.agenvas.plan.application;

import java.time.Instant;
import java.util.UUID;

/** 用户为镜头选择的关键帧引用；镜头和图片都绑定不可变版本，避免后续编辑改变已选输入。
 * @param projectId 所属项目
 * @param shotArtifactId 被配置关键帧的镜头产物
 * @param shotVersionId 用户选择时的镜头内容版本
 * @param imageArtifactId 被选作关键帧的图片产物
 * @param imageVersionId 固定的图片内容版本
 * @param sourceTaskId 生成该图片的任务；手工导入图片时可为空
 * @param selectedByUserId 作出选择的用户
 * @param version 选择关系的并发控制版本
 * @param createdAt 首次选择时间
 * @param updatedAt 最近一次更改选择的时间
 */
public record ShotKeyframeSelection(UUID projectId, UUID shotArtifactId,
        UUID shotVersionId, UUID imageArtifactId, UUID imageVersionId,
        UUID sourceTaskId, UUID selectedByUserId, long version,
        Instant createdAt, Instant updatedAt) {}

package dev.agenvas.project.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目当前持久化状态；eventSeq 用于项目内有序补发，version 用于设置并发控制。
 *
 * @param id 项目 ID
 * @param ownerId 唯一所有者用户 ID
 * @param name 项目名称
 * @param aspectRatio 画布及导出的默认画幅
 * @param status 活动或归档状态
 * @param eventSeq 项目事件序号水位
 * @param version 项目设置乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近一次设置变化时间
 * @param archivedAt 归档时间；活动项目为空
 */
public record Project(
        UUID id,
        UUID ownerId,
        String name,
        AspectRatio aspectRatio,
        Status status,
        long eventSeq,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    /** 首版画布和无声导出支持的画幅。 */
    public enum AspectRatio {
        /** 横向宽屏，16:9。 */
        LANDSCAPE_16_9,
        /** 纵向画幅，9:16。 */
        PORTRAIT_9_16,
        /** 正方形画幅，1:1。 */
        SQUARE_1_1
    }

    /** 项目生命周期状态。 */
    public enum Status {
        /** 可编辑并可启动 Run。 */
        ACTIVE,
        /** 保留历史读取，禁止新编辑和运行。 */
        ARCHIVED
    }
}

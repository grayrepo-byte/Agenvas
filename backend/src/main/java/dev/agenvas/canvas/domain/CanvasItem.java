package dev.agenvas.canvas.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Artifact 或 AgentInstance 在画布上的持久化空间展示；拖动不修改被展示对象内容。
 *
 * @param id 画布项 ID
 * @param projectId 所属项目
 * @param subjectType 被展示业务对象的类别
 * @param subjectId 被展示的产物或 Agent ID
 * @param x 左上角横坐标
 * @param y 左上角纵坐标
 * @param width 卡片宽度
 * @param height 卡片高度
 * @param zIndex 显示层级
 * @param groupId 可选画布分组
 * @param locked 是否禁止位置和尺寸更新
 * @param version 布局乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近一次布局更新时间
 */
public record CanvasItem(
        UUID id,
        UUID projectId,
        SubjectType subjectType,
        UUID subjectId,
        BigDecimal x,
        BigDecimal y,
        BigDecimal width,
        BigDecimal height,
        int zIndex,
        UUID groupId,
        boolean locked,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** 可投影到画布上的业务对象类别。 */
    public enum SubjectType {
        /** 画布项引用 Artifact，渲染时读取其当前版本。 */
        ARTIFACT,
        /** 画布项引用 AgentInstance，渲染时读取当前配置。 */
        AGENT
    }
}

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
 * @param selectedVersionId 该媒体卡片独立展示的不可变版本；空产物与 Agent 卡片可为空
 * @param title 当前卡片独立的展示标题
 * @param x 左上角横坐标
 * @param y 左上角纵坐标
 * @param width 卡片宽度
 * @param height 卡片高度
 * @param zIndex 显示层级
 * @param groupId 可选画布分组
 * @param locked 是否禁止位置和尺寸更新
 * @param version 卡片展示状态的乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近一次展示状态更新时间
 */
public record CanvasItem(
        UUID id,
        UUID projectId,
        SubjectType subjectType,
        UUID subjectId,
        UUID selectedVersionId,
        String title,
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
        /** 画布项引用 Artifact；媒体卡片渲染自身选用的版本。 */
        ARTIFACT,
        /** 画布项引用 AgentInstance，渲染时读取当前配置。 */
        AGENT
    }
}

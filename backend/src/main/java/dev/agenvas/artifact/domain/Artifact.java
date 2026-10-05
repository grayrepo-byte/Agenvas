package dev.agenvas.artifact.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目内稳定的创作产物身份；正文只存在不可变 ArtifactVersion 中。
 *
 * @param id 产物身份 ID
 * @param projectId 所属项目 ID
 * @param kind 正文结构对应的产物类型
 * @param title 当前展示标题
 * @param resourceDefaultVersionId 资源库明确选用的默认不可变版本 ID
 * @param archivedAt 归档时间；非空时不可再编辑或选用新版本
 * @param version 当前选择与标题的乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近一次选择或标题变化时间
 */
public record Artifact(
        UUID id,
        UUID projectId,
        Kind kind,
        String title,
        UUID resourceDefaultVersionId,
        Instant archivedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** MVP 支持的创作产物正文类别。 */
    public enum Kind {
        /** 用户或 Agent 创建的结构化文本。 */
        TEXT,
        /** 图片媒体引用及生成信息。 */
        IMAGE,
        /** 视频媒体引用及生成信息。 */
        VIDEO,
        /** Uploaded or generated speech/audio. */
        AUDIO
    }
}

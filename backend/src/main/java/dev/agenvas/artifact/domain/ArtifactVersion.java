package dev.agenvas.artifact.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 不可变产物正文版本及其读取的精确语义输入版本。
 *
 * @param id 版本 ID
 * @param projectId 所属项目 ID
 * @param artifactId 所属稳定产物身份
 * @param versionNo 该产物内从 1 递增的版本序号
 * @param schemaVersion 正文 Schema 版本
 * @param content 创建时深拷贝的正文 JSON
 * @param inputReferences 按语义角色和顺序固定的输入版本引用
 * @param createdByKind 产生该版本的用户、Agent 或任务类别
 * @param runId 产生该版本的 Run；用户版本可为空
 * @param createdAt 创建时间
 */
public record ArtifactVersion(
        UUID id,
        UUID projectId,
        UUID artifactId,
        int versionNo,
        int schemaVersion,
        JsonNode content,
        List<InputReference> inputReferences,
        CreatedByKind createdByKind,
        UUID runId,
        Instant createdAt) {

    /** 不可变版本的创建来源。 */
    public enum CreatedByKind {
        /** 由用户直接创建或编辑。 */
        USER,
        /** 由受控 Agent 工具创建或编辑。 */
        AGENT,
        /** 由媒体任务或系统任务归档生成。 */
        TASK
    }

    /**
     * 指向精确历史输入版本的类型化语义引用。
     *
     * @param versionId 被读取的版本 ID
     * @param role 正文 Schema 定义的输入角色
     * @param order 同一角色下的稳定顺序
     * @param expectedKind 被引用产物必须具有的类型
     */
    public record InputReference(
            UUID versionId,
            String role,
            int order,
            Artifact.Kind expectedKind) {}
}

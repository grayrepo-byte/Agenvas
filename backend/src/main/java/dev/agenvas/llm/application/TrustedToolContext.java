package dev.agenvas.llm.application;

import java.util.UUID;

/**
 * 服务端建立的工具调用身份和范围，三项均不得作为模型工具参数由模型选择。
 *
 * @param ownerId 从认证上下文或已认领任务反查的所有者 ID
 * @param projectId 已鉴权的项目 ID，目标资源仍需在服务层再次校验
 * @param runId 当前执行的 Run ID
 */
public record TrustedToolContext(UUID ownerId, UUID projectId, UUID runId) {

    /** 在访问工具账本前拒绝缺少所有者、项目或 Run 范围的上下文。 */
    public TrustedToolContext {
        if (ownerId == null || projectId == null || runId == null) {
            throw new IllegalArgumentException("Trusted tool context requires owner, project and Run");
        }
    }
}

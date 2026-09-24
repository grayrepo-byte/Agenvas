package dev.agenvas.agent.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 持久化 Agent 卡片配置；一次运行的指令、策略和对话状态保存在 AgentRun 中。
 *
 * @param id Agent 卡片 ID
 * @param projectId 所属项目 ID
 * @param profileKey 使用的内置 Agent 档案标识
 * @param profileVersion 档案实现版本
 * @param name 用户设置的卡片名称
 * @param instruction 用户设置的卡片指令
 * @param outputGroupId 该 Agent 在画布上的输出分组 ID
 * @param version 配置乐观锁版本
 * @param createdAt 创建时间
 * @param updatedAt 最近一次配置更新时间
 * @param bindings 用户显式选择且固定到版本的输入列表
 */
public record AgentInstance(
        UUID id,
        UUID projectId,
        String profileKey,
        int profileVersion,
        String name,
        String instruction,
        UUID outputGroupId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<Binding> bindings) {

    /**
     * 用户显式选择的输入引用，固定到不可变 ArtifactVersion。
     *
     * @param id 绑定关系 ID
     * @param artifactId 被引用的产物 ID
     * @param selectedVersionId 被选中的不可变版本 ID
     * @param bindingType 当前档案支持的绑定角色
     * @param createdAt 绑定创建时间
     */
    public record Binding(
            UUID id,
            UUID artifactId,
            UUID selectedVersionId,
            BindingType bindingType,
            Instant createdAt) {}

    /** MVP Creator 档案允许的绑定角色。 */
    public enum BindingType {
        /** 作为模型上下文输入的产物版本。 */
        INPUT
    }
}

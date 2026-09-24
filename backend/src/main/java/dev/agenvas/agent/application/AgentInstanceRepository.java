package dev.agenvas.agent.application;

import dev.agenvas.agent.domain.AgentInstance;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Agent 卡片配置及显式输入绑定的所有者范围持久化边界。 */
public interface AgentInstanceRepository {

    /** 插入单个卡片配置，不授予其隐式读取项目全部素材的权限。 */
    void create(AgentInstance instance);

    /** 按确定的创建顺序列出卡片及其绑定。 */
    List<AgentInstance> list(UUID ownerId, UUID projectId);

    /** 在所有者范围内读取单个卡片。 */
    Optional<AgentInstance> find(UUID ownerId, UUID projectId, UUID agentId);

    /** 锁定卡片，供完整配置的乐观并发更新使用。 */
    Optional<AgentInstance> findForUpdate(UUID ownerId, UUID projectId, UUID agentId);

    /** 预期配置版本匹配时更新可变字段。 */
    boolean update(
            UUID ownerId,
            AgentInstance instance,
            long expectedVersion,
            Instant updatedAt);

    /** 在调用方事务内整体替换显式输入绑定集合。 */
    void replaceBindings(UUID projectId, UUID agentId, List<AgentInstance.Binding> bindings);
}

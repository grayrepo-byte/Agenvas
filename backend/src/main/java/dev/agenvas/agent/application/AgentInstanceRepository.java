package dev.agenvas.agent.application;

import dev.agenvas.agent.domain.AgentInstance;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 项目助手配置的所有者范围持久化边界。 */
public interface AgentInstanceRepository {
    /** 插入单个助手配置；权限由受理 Run 时的固定画布范围决定。 */
    void create(AgentInstance instance);

    /** 一个项目仅有一个导演；按项目和所有者读取其稳定身份。 */
    Optional<AgentInstance> findProjectDirector(UUID ownerId, UUID projectId);

    /** 在所有者范围内读取单个助手。 */
    Optional<AgentInstance> find(UUID ownerId, UUID projectId, UUID agentId);

    /** 锁定助手，供完整配置的乐观并发更新使用。 */
    Optional<AgentInstance> findForUpdate(UUID ownerId, UUID projectId, UUID agentId);

    /** 预期配置版本匹配时更新可变字段。 */
    boolean update(
            UUID ownerId,
            AgentInstance instance,
            long expectedVersion,
            Instant updatedAt);

}

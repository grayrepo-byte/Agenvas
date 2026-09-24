package dev.agenvas.usage.application;

import dev.agenvas.usage.domain.UsageEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 只追加用量账本的持久化幂等边界。 */
public interface UsageRepository {

    /** 每个逻辑操作键只插入首条记录。 */
    boolean insertOnce(UsageEntry entry);

    /** 重放时核对载荷是否与原操作一致，不掩盖同键冲突。 */
    Optional<UsageEntry> findByOperationKey(String operationKey);

    /** 按创建时间列出已授权项目的用量记录。 */
    List<UsageEntry> listProject(UUID projectId);
}

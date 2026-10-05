package dev.agenvas.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 唯一管理员账户的持久化边界。 */
public interface AdminAccountRepository {

    /** 锁定安装初始化行直到外层事务结束，串行化首位管理员创建。 */
    void lockSetup();

    /** 查询持久完成标记或既有账户；停用账户不会重新开放初始化。 */
    boolean isSetupCompleted();

    /** 在创建管理员的同一事务中首次记录完成时间；已有标记时返回 false。 */
    boolean completeSetup(Instant completedAt);

    /** 按规范化登录名查找活动管理员。 */
    Optional<AdminAccount> findActiveByLoginName(String loginName);

    /** 插入首位活动管理员；并发初始化由数据库约束仲裁。 */
    void createAdmin(UUID id, String loginName, String passwordHash, Instant createdAt);

    /** 仅当账户预期版本匹配时修改密码。 */
    boolean updatePassword(
            UUID id, long expectedVersion, String passwordHash, Instant passwordChangedAt);
}

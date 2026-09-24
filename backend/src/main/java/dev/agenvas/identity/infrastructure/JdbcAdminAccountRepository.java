package dev.agenvas.identity.infrastructure;

import dev.agenvas.identity.application.AdminAccountRepository;
import dev.agenvas.identity.application.AdminAccount;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL 管理员仓储；首次初始化通过单行锁串行化，密码更新使用版本 CAS。 */
@Repository
public class JdbcAdminAccountRepository implements AdminAccountRepository {

    /** 执行初始化锁、管理员读取和密码更新 SQL。 */
    private final JdbcClient jdbcClient;

    /** 注入管理员账户仓储使用的 JDBC 客户端。 */
    public JdbcAdminAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 锁定安装初始化互斥行，保证账户存在性检查与创建串行执行。 */
    @Override
    public void lockSetup() {
        jdbcClient.sql("select id from installation_lock where id = 1 for update")
                .query(Integer.class)
                .single();
    }

    /** 检查是否已有活动管理员，初始化流程在互斥锁内调用。 */
    @Override
    public boolean hasAdminAccount() {
        return Boolean.TRUE.equals(
                jdbcClient.sql("select exists(select 1 from app_user where status = 'ACTIVE')")
                        .query(Boolean.class)
                        .single());
    }

    /** 按规范化登录名读取活动账户及密码哈希，不返回已禁用账户。 */
    @Override
    public Optional<AdminAccount> findActiveByLoginName(String loginName) {
        return jdbcClient
                .sql("""
                        select id, login_name, password_hash, status, created_at,
                               password_changed_at, version
                        from app_user
                        where login_name = :loginName and status = 'ACTIVE'
                        """)
                .param("loginName", loginName)
                .query((resultSet, rowNumber) -> new AdminAccount(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("login_name"),
                        resultSet.getString("password_hash"),
                        AdminAccount.Status.valueOf(resultSet.getString("status")),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("password_changed_at", OffsetDateTime.class).toInstant(),
                        resultSet.getLong("version")))
                .optional();
    }

    /** 插入首个活动管理员账户，密码哈希由应用服务预先生成。 */
    @Override
    public void createAdmin(UUID id, String loginName, String passwordHash, Instant createdAt) {
        jdbcClient
                .sql("""
                        insert into app_user (
                            id, login_name, password_hash, status, created_at,
                            password_changed_at, version
                        ) values (
                            :id, :loginName, :passwordHash, 'ACTIVE', :createdAt,
                            :createdAt, 0
                        )
                        """)
                .param("id", id)
                .param("loginName", loginName)
                .param("passwordHash", passwordHash)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** 仅在账户仍活动且版本未变化时更新密码并递增版本。 */
    @Override
    public boolean updatePassword(
            UUID id, long expectedVersion, String passwordHash, Instant passwordChangedAt) {
        int updated = jdbcClient
                .sql("""
                        update app_user
                        set password_hash = :passwordHash,
                            password_changed_at = :passwordChangedAt,
                            version = version + 1
                        where id = :id and version = :expectedVersion and status = 'ACTIVE'
                        """)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .param("passwordHash", passwordHash)
                .param("passwordChangedAt", passwordChangedAt.atOffset(ZoneOffset.UTC))
                .update();
        return updated == 1;
    }
}

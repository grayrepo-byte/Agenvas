package dev.agenvas.identity.infrastructure;

import static dev.agenvas.db.Tables.APP_USER;
import static dev.agenvas.db.Tables.INSTALLATION_LOCK;

import dev.agenvas.db.tables.records.AppUserRecord;
import dev.agenvas.identity.application.AdminAccountRepository;
import dev.agenvas.identity.application.AdminAccount;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** PostgreSQL 管理员仓储；首次初始化通过单行锁串行化，密码更新使用版本 CAS。 */
@Repository
public class JooqAdminAccountRepository implements AdminAccountRepository {

    /** 初始化互斥行的固定主键；表中只有这一行。 */
    private static final short SETUP_LOCK_ID = 1;

    /** 执行初始化锁、管理员读取和密码更新 SQL。 */
    private final DSLContext dsl;

    /** 注入管理员账户仓储使用的 jOOQ 上下文。 */
    public JooqAdminAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 锁定安装初始化互斥行，保证账户存在性检查与创建串行执行。 */
    @Override
    public void lockSetup() {
        dsl.select(INSTALLATION_LOCK.ID)
                .from(INSTALLATION_LOCK)
                .where(INSTALLATION_LOCK.ID.eq(SETUP_LOCK_ID))
                .forUpdate()
                .fetchSingle();
    }

    /** 检查是否已有活动管理员，初始化流程在互斥锁内调用。 */
    @Override
    public boolean hasAdminAccount() {
        return dsl.fetchExists(dsl.selectOne()
                .from(APP_USER)
                .where(APP_USER.STATUS.eq(AdminAccount.Status.ACTIVE.name())));
    }

    /** 按规范化登录名读取活动账户及密码哈希，不返回已禁用账户。 */
    @Override
    public Optional<AdminAccount> findActiveByLoginName(String loginName) {
        return dsl.selectFrom(APP_USER)
                .where(APP_USER.LOGIN_NAME.eq(loginName))
                .and(APP_USER.STATUS.eq(AdminAccount.Status.ACTIVE.name()))
                .fetchOptional(this::map);
    }

    /** 插入首个活动管理员账户，密码哈希由应用服务预先生成。 */
    @Override
    public void createAdmin(UUID id, String loginName, String passwordHash, Instant createdAt) {
        dsl.insertInto(APP_USER)
                .set(APP_USER.ID, id)
                .set(APP_USER.LOGIN_NAME, loginName)
                .set(APP_USER.PASSWORD_HASH, passwordHash)
                .set(APP_USER.STATUS, AdminAccount.Status.ACTIVE.name())
                .set(APP_USER.CREATED_AT, atUtc(createdAt))
                .set(APP_USER.PASSWORD_CHANGED_AT, atUtc(createdAt))
                .set(APP_USER.VERSION, 0L)
                .execute();
    }

    /** 仅在账户仍活动且版本未变化时更新密码并递增版本。 */
    @Override
    public boolean updatePassword(
            UUID id, long expectedVersion, String passwordHash, Instant passwordChangedAt) {
        return dsl.update(APP_USER)
                .set(APP_USER.PASSWORD_HASH, passwordHash)
                .set(APP_USER.PASSWORD_CHANGED_AT, atUtc(passwordChangedAt))
                .set(APP_USER.VERSION, APP_USER.VERSION.plus(1))
                .where(APP_USER.ID.eq(id))
                .and(APP_USER.VERSION.eq(expectedVersion))
                .and(APP_USER.STATUS.eq(AdminAccount.Status.ACTIVE.name()))
                .execute() == 1;
    }

    /** 将账户行还原为管理员视图；时间统一按 UTC 读取。 */
    private AdminAccount map(AppUserRecord row) {
        return new AdminAccount(
                row.getId(),
                row.getLoginName(),
                row.getPasswordHash(),
                AdminAccount.Status.valueOf(row.getStatus()),
                row.getCreatedAt().toInstant(),
                row.getPasswordChangedAt().toInstant(),
                row.getVersion());
    }

    /** 将 Instant 转成 PostgreSQL timestamptz 参数所需的 UTC 时间。 */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

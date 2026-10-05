package dev.agenvas.settings.infrastructure;

import static dev.agenvas.db.Tables.LLM_PROVIDER_CONFIG;
import static dev.agenvas.db.Tables.LLM_PROVIDER_CONFIG_COUNTER;

import dev.agenvas.db.tables.records.LlmProviderConfigRecord;
import dev.agenvas.settings.application.LlmProviderConfig;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** PostgreSQL LLM 配置仓储；保留每个密文版本，并在同一事务中切换活动配置指针。 */
@Repository
public class JooqLlmProviderConfigRepository implements LlmProviderConfigRepository {

    /** LLM 配置版本计数器的固定主键；表中只有这一行。 */
    private static final short COUNTER_ID = 1;

    /** 执行配置版本锁定、密文读取和活动配置发布 SQL。 */
    private final DSLContext dsl;

    /** 注入 LLM 配置仓储使用的 jOOQ 上下文。 */
    public JooqLlmProviderConfigRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 锁定单行版本计数器，串行化新配置版本分配。 */
    @Override
    public int lockVersion() {
        return dsl.select(LLM_PROVIDER_CONFIG_COUNTER.CURRENT_VERSION)
                .from(LLM_PROVIDER_CONFIG_COUNTER)
                .where(LLM_PROVIDER_CONFIG_COUNTER.ID.eq(COUNTER_ID))
                .forUpdate()
                .fetchSingle(LLM_PROVIDER_CONFIG_COUNTER.CURRENT_VERSION);
    }

    /** 读取当前活动配置及其服务端密文，不向 API 层直接返回该对象。 */
    @Override
    public Optional<LlmProviderConfig> active() {
        return dsl.selectFrom(LLM_PROVIDER_CONFIG)
                .where(LLM_PROVIDER_CONFIG.ACTIVE.eq(true))
                .fetchOptional(this::map);
    }

    /** 按不可变版本读取配置，用于重放固定旧版本的模型请求。 */
    @Override
    public Optional<LlmProviderConfig> findVersion(int version) {
        return dsl.selectFrom(LLM_PROVIDER_CONFIG)
                .where(LLM_PROVIDER_CONFIG.VERSION.eq(version))
                .fetchOptional(this::map);
    }

    /** 先取消旧活动标记，再插入新密文版本并 CAS 推进计数器。 */
    @Override
    public void publish(int expectedVersion, LlmProviderConfig config) {
        dsl.update(LLM_PROVIDER_CONFIG)
                .set(LLM_PROVIDER_CONFIG.ACTIVE, false)
                .where(LLM_PROVIDER_CONFIG.ACTIVE.eq(true))
                .execute();
        int inserted = dsl.insertInto(LLM_PROVIDER_CONFIG)
                .set(LLM_PROVIDER_CONFIG.ID, config.id())
                .set(LLM_PROVIDER_CONFIG.VERSION, config.version())
                .set(LLM_PROVIDER_CONFIG.ENDPOINT, config.endpoint())
                .set(LLM_PROVIDER_CONFIG.MODEL_ID, config.modelId())
                .set(LLM_PROVIDER_CONFIG.CREDENTIAL_CIPHERTEXT, config.credentialCiphertext())
                .set(LLM_PROVIDER_CONFIG.CREDENTIAL_NONCE, config.credentialNonce())
                .set(LLM_PROVIDER_CONFIG.KEY_VERSION, config.keyVersion())
                .set(LLM_PROVIDER_CONFIG.KEY_MASK, config.keyMask())
                .set(LLM_PROVIDER_CONFIG.TOOL_CALLING_VERIFIED, config.toolCallingVerified())
                .set(LLM_PROVIDER_CONFIG.ACTIVE, true)
                .set(LLM_PROVIDER_CONFIG.CREATED_AT,
                        OffsetDateTime.ofInstant(config.createdAt(), ZoneOffset.UTC))
                .execute();
        int advanced = dsl.update(LLM_PROVIDER_CONFIG_COUNTER)
                .set(LLM_PROVIDER_CONFIG_COUNTER.CURRENT_VERSION, config.version())
                .where(LLM_PROVIDER_CONFIG_COUNTER.ID.eq(COUNTER_ID))
                .and(LLM_PROVIDER_CONFIG_COUNTER.CURRENT_VERSION.eq(expectedVersion))
                .execute();
        if (inserted != 1 || advanced != 1) {
            throw new IllegalStateException("LLM configuration counter changed under its lock");
        }
    }

    /** 仅在指定版本仍活动且尚未验证时标记工具调用能力通过。 */
    @Override
    public boolean markToolCallingVerified(int version) {
        return dsl.update(LLM_PROVIDER_CONFIG)
                .set(LLM_PROVIDER_CONFIG.TOOL_CALLING_VERIFIED, true)
                .where(LLM_PROVIDER_CONFIG.ACTIVE.eq(true))
                .and(LLM_PROVIDER_CONFIG.VERSION.eq(version))
                .and(LLM_PROVIDER_CONFIG.TOOL_CALLING_VERIFIED.eq(false))
                .execute() == 1;
    }

    /** 映射配置数据库行，包含密文、nonce 与密钥版本供加密服务处理。 */
    private LlmProviderConfig map(LlmProviderConfigRecord row) {
        return new LlmProviderConfig(row.getId(),
                row.getVersion(), row.getEndpoint(), row.getModelId(),
                row.getCredentialCiphertext(), row.getCredentialNonce(),
                row.getKeyVersion(), row.getKeyMask(),
                row.getToolCallingVerified(), row.getActive(),
                row.getCreatedAt().toInstant());
    }
}

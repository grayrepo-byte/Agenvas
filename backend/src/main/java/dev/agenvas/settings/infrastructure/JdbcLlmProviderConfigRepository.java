package dev.agenvas.settings.infrastructure;

import dev.agenvas.settings.application.LlmProviderConfig;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL LLM 配置仓储；保留每个密文版本，并在同一事务中切换活动配置指针。 */
@Repository
public class JdbcLlmProviderConfigRepository implements LlmProviderConfigRepository {

    /** 执行配置版本锁定、密文读取和活动配置发布 SQL。 */
    private final JdbcClient jdbc;

    /** 注入 LLM 配置仓储使用的 JDBC 客户端。 */
    public JdbcLlmProviderConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 锁定单行版本计数器，串行化新配置版本分配。 */
    @Override
    public int lockVersion() {
        return jdbc.sql("select current_version from llm_provider_config_counter "
                        + "where id = 1 for update")
                .query(Integer.class).single();
    }

    /** 读取当前活动配置及其服务端密文，不向 API 层直接返回该对象。 */
    @Override
    public Optional<LlmProviderConfig> active() {
        return jdbc.sql("select * from llm_provider_config where active = true")
                .query(this::map).optional();
    }

    /** 按不可变版本读取配置，用于重放固定旧版本的模型请求。 */
    @Override
    public Optional<LlmProviderConfig> findVersion(int version) {
        return jdbc.sql("select * from llm_provider_config where version = :version")
                .param("version", version).query(this::map).optional();
    }

    /** 先取消旧活动标记，再插入新密文版本并 CAS 推进计数器。 */
    @Override
    public void publish(int expectedVersion, LlmProviderConfig config) {
        jdbc.sql("update llm_provider_config set active = false where active = true")
                .update();
        int inserted = jdbc.sql("""
                        insert into llm_provider_config (id, version, endpoint, model_id,
                            credential_ciphertext, credential_nonce, key_version, key_mask,
                            tool_calling_verified, active, created_at)
                        values (:id, :version, :endpoint, :modelId, :ciphertext, :nonce,
                            :keyVersion, :keyMask, :toolCallingVerified, true, :createdAt)
                        """)
                .param("id", config.id()).param("version", config.version())
                .param("endpoint", config.endpoint()).param("modelId", config.modelId())
                .param("ciphertext", config.credentialCiphertext())
                .param("nonce", config.credentialNonce())
                .param("keyVersion", config.keyVersion()).param("keyMask", config.keyMask())
                .param("toolCallingVerified", config.toolCallingVerified())
                .param("createdAt", OffsetDateTime.ofInstant(config.createdAt(), ZoneOffset.UTC))
                .update();
        int advanced = jdbc.sql("""
                        update llm_provider_config_counter set current_version = :next
                        where id = 1 and current_version = :expected
                        """)
                .param("next", config.version()).param("expected", expectedVersion).update();
        if (inserted != 1 || advanced != 1) {
            throw new IllegalStateException("LLM configuration counter changed under its lock");
        }
    }

    /** 仅在指定版本仍活动且尚未验证时标记工具调用能力通过。 */
    @Override
    public boolean markToolCallingVerified(int version) {
        return jdbc.sql("update llm_provider_config set tool_calling_verified = true "
                        + "where active = true and version = :version "
                        + "and tool_calling_verified = false")
                .param("version", version).update() == 1;
    }

    /** 映射配置数据库行，包含密文、nonce 与密钥版本供加密服务处理。 */
    private LlmProviderConfig map(ResultSet rs, int row) throws SQLException {
        return new LlmProviderConfig(rs.getObject("id", java.util.UUID.class),
                rs.getInt("version"), rs.getString("endpoint"), rs.getString("model_id"),
                rs.getBytes("credential_ciphertext"), rs.getBytes("credential_nonce"),
                rs.getInt("key_version"), rs.getString("key_mask"),
                rs.getBoolean("tool_calling_verified"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}

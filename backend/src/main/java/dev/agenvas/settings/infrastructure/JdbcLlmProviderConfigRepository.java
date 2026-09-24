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

/** PostgreSQL implementation keeps ciphertext versions while atomically moving the active flag. */
@Repository
public class JdbcLlmProviderConfigRepository implements LlmProviderConfigRepository {

    private final JdbcClient jdbc;

    public JdbcLlmProviderConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int lockVersion() {
        return jdbc.sql("select current_version from llm_provider_config_counter "
                        + "where id = 1 for update")
                .query(Integer.class).single();
    }

    @Override
    public Optional<LlmProviderConfig> active() {
        return jdbc.sql("select * from llm_provider_config where active = true")
                .query(this::map).optional();
    }

    @Override
    public Optional<LlmProviderConfig> findVersion(int version) {
        return jdbc.sql("select * from llm_provider_config where version = :version")
                .param("version", version).query(this::map).optional();
    }

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

    @Override
    public boolean markToolCallingVerified(int version) {
        return jdbc.sql("update llm_provider_config set tool_calling_verified = true "
                        + "where active = true and version = :version "
                        + "and tool_calling_verified = false")
                .param("version", version).update() == 1;
    }

    private LlmProviderConfig map(ResultSet rs, int row) throws SQLException {
        return new LlmProviderConfig(rs.getObject("id", java.util.UUID.class),
                rs.getInt("version"), rs.getString("endpoint"), rs.getString("model_id"),
                rs.getBytes("credential_ciphertext"), rs.getBytes("credential_nonce"),
                rs.getInt("key_version"), rs.getString("key_mask"),
                rs.getBoolean("tool_calling_verified"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}

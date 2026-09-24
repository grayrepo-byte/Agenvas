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

/** PostgreSQL implementation of administrator persistence. */
@Repository
public class JdbcAdminAccountRepository implements AdminAccountRepository {

    private final JdbcClient jdbcClient;

    public JdbcAdminAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void lockSetup() {
        jdbcClient.sql("select id from installation_lock where id = 1 for update")
                .query(Integer.class)
                .single();
    }

    @Override
    public boolean hasAdminAccount() {
        return Boolean.TRUE.equals(
                jdbcClient.sql("select exists(select 1 from app_user where status = 'ACTIVE')")
                        .query(Boolean.class)
                        .single());
    }

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

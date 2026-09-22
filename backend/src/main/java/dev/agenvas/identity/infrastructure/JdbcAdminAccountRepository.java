package dev.agenvas.identity.infrastructure;

import dev.agenvas.identity.application.AdminAccountRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdminAccountRepository implements AdminAccountRepository {

    private final JdbcClient jdbcClient;

    public JdbcAdminAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean hasAdminAccount() {
        return Boolean.TRUE.equals(
                jdbcClient.sql("select exists(select 1 from app_user where status = 'ACTIVE')")
                        .query(Boolean.class)
                        .single());
    }
}

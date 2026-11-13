package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LlmCapacityMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyUntouchedDefaultChangesAndCredentialsRemainUnmodified(boolean explicitlySaved) throws Exception {
        var base = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).target("46").load();
        base.clean();
        base.migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            if (explicitlySaved)
                sql.executeUpdate(
                        "update director_settings set context_window_tokens=65536,response_reserve_tokens=4096,summary_tokens=1024,version=2");
            sql.executeUpdate("""
                    insert into llm_provider_config(id,version,endpoint,model_id,credential_ciphertext,credential_nonce,key_version,key_mask,active,created_at)
                    values ('00000000-0000-4000-8000-000000000001',1,'https://10.0.0.5/v1','synthetic-model',decode('0102','hex'),decode(repeat('00',12),'hex'),1,'synthetic-mask',true,now())
                    """);
        }
        var upgraded = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        upgraded.migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
                var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select context_window_tokens,version from director_settings")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(explicitlySaved ? 65536 : 32768);
                assertThat(rows.getLong(2)).isEqualTo(2);
            }
            try (var rows = sql.executeQuery(
                    "select model_policy,encode(credential_ciphertext,'hex'),octet_length(credential_nonce),endpoint from llm_provider_config")) {
                rows.next();
                assertThat(rows.getString(1)).isNull();
                assertThat(rows.getString(2)).isEqualTo("0102");
                assertThat(rows.getInt(3)).isEqualTo(12);
                assertThat(rows.getString(4)).isEqualTo("https://10.0.0.5/v1");
            }
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }
}

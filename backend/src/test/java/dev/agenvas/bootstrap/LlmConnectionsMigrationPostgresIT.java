package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LlmConnectionsMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @Test
    void upgradeKeepsActiveAndHistoricalEncryptedSnapshotsAndCreatesOnlyOneConnection() throws Exception {
        var base = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("62").load();
        base.migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    insert into llm_provider_config(id,version,endpoint,model_id,credential_ciphertext,credential_nonce,key_version,key_mask,active,created_at)
                    values ('00000000-0000-4000-8000-000000000001',1,'https://10.0.0.5/v1','old-model',decode('0102','hex'),decode(repeat('00',12),'hex'),1,'masked',false,now()),
                           ('00000000-0000-4000-8000-000000000002',2,'https://10.0.0.6/v1','current-model',decode('0304','hex'),decode(repeat('01',12),'hex'),1,'masked',true,now())
                    """);
            sql.executeUpdate("update llm_provider_config_counter set current_version=2");
        }
        var upgraded = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        upgraded.migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select count(*),min(current_config_version) from llm_connection")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(2);
            }
            try (var rows = sql.executeQuery(
                    "select version,encode(credential_ciphertext,'hex'),active from llm_provider_config order by version")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getString(2)).isEqualTo("0102");
                assertThat(rows.getBoolean(3)).isFalse();
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getString(2)).isEqualTo("0304");
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }
}

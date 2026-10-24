package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Additive upgrade and empty installation against an isolated PostgreSQL database. */
@Testcontainers
class DirectorSettingsMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void createsDefaultSettingsWithoutChangingExistingCreativeConfiguration(boolean upgrade) throws Exception {
        var migration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).load();
        migration.clean();
        if (upgrade) {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration").target("40").load().migrate();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = connection.createStatement()) {
                statement.executeUpdate("update prompt_definition set content='Synthetic retained creative direction', version=42 where key='agent.director'");
            }
        }
        var result = migration.migrate();
        if (upgrade) assertThat(result.migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select * from director_settings")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("context_window_tokens")).isEqualTo(1_000_000);
                assertThat(rows.getInt("compaction_threshold_percent")).isEqualTo(50);
                assertThat(rows.getInt("recovery_window_minutes")).isEqualTo(1440);
                assertThat(rows.getLong("version")).isEqualTo(1);
                assertThat(rows.next()).isFalse();
            }
            if (upgrade) try (var rows = statement.executeQuery("select content,version from prompt_definition where key='agent.director'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Synthetic retained creative direction");
                assertThat(rows.getLong(2)).isEqualTo(42);
            }
            try (var rows = statement.executeQuery("""
                    select count(*) from pg_attribute where attrelid='director_settings'::regclass
                    and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null
                    """)) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("select count(*) from pg_constraint where conrelid='director_settings'::regclass and obj_description(oid) is null")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}

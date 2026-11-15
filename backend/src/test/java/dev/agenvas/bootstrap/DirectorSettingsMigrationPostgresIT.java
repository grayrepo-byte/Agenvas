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
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsDefaultSettingsWithoutChangingExistingCreativeConfiguration(boolean upgrade) throws Exception {
        var migration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).load();
        migration.clean();
        if (upgrade) {
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration").target("40").load().migrate();
            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword());
                    var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "update prompt_definition set content='Synthetic retained creative direction', version=42 where key='agent.director'");
            }
        }
        int pending = migration.info().pending().length;
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(pending);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select * from director_settings")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("context_window_tokens")).isEqualTo(32768);
                assertThat(rows.getInt("compaction_threshold_percent")).isEqualTo(50);
                assertThat(rows.getInt("recovery_window_minutes")).isEqualTo(1440);
                assertThat(rows.getLong("version")).isEqualTo(3);
                assertThat(rows.next()).isFalse();
            }
            if (upgrade)
                try (var rows = statement
                        .executeQuery("select content,version from prompt_definition where key='agent.director'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("Synthetic retained creative direction");
                    assertThat(rows.getLong(2)).isEqualTo(42);
                }
            try (var rows = statement.executeQuery("""
                    select count(*) from pg_attribute where attrelid='director_settings'::regclass
                    and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null
                    """)) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery(
                    "select count(*) from pg_constraint where conrelid='director_settings'::regclass and obj_description(oid) is null")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("""
                    select count(*) from pg_class c where
                    (c.oid='director_settings'::regclass or c.oid in (
                        select indexrelid from pg_index where indrelid='director_settings'::regclass))
                    and nullif(btrim(obj_description(c.oid,'pg_class')),'') is null
                    """)) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }

    @org.junit.jupiter.api.Test
    void removesObsoleteCompactionSettingsAndPreservesTheOtherSavedValues() throws Exception {
        var previous = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("63").cleanDisabled(false).load();
        previous.clean();
        previous.migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    update director_settings set context_window_tokens=65536,compaction_threshold_percent=75,
                    response_reserve_tokens=8192,summary_tokens=4096,recent_tool_rounds=5,
                    recovery_window_minutes=120,max_model_retries=44,retry_max_delay_seconds=27,
                    max_repair_attempts=8,version=7
                    """);
        }
        var upgraded = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select * from director_settings")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("context_window_tokens")).isEqualTo(65536);
                assertThat(rows.getInt("compaction_threshold_percent")).isEqualTo(75);
                assertThat(rows.getInt("response_reserve_tokens")).isEqualTo(8192);
                assertThat(rows.getInt("recovery_window_minutes")).isEqualTo(120);
                assertThat(rows.getInt("max_model_retries")).isEqualTo(44);
                assertThat(rows.getInt("retry_max_delay_seconds")).isEqualTo(27);
                assertThat(rows.getInt("max_repair_attempts")).isEqualTo(8);
                assertThat(rows.getLong("version")).isEqualTo(8);
            }
            try (var rows = statement.executeQuery("""
                    select count(*) from information_schema.columns where table_schema='public'
                    and table_name='director_settings' and column_name in ('summary_tokens','recent_tool_rounds')
                    """)) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }
}

package dev.agenvas.subtitle.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Additive V61-to-V62 upgrade, checked independently of generated persistence mappings. */
@Testcontainers
class ManagedAsrMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @Test
    void addsDefaultOffConsentWithoutChangingExistingCreativeWork() throws Exception {
        migrate("61");
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            try (var insert = connection.prepareStatement(
                    "insert into app_user(id,login_name,password_hash,status,created_at) values (?, 'subtitle-upgrade','synthetic','ACTIVE',now())")) {
                insert.setObject(1, owner);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement(
                    "insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Retained creative work','LANDSCAPE_16_9','ACTIVE',now(),now())")) {
                insert.setObject(1, project);
                insert.setObject(2, owner);
                insert.executeUpdate();
            }
        }
        assertThat(migrate("62")).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var query = connection.createStatement()) {
            try (var result = query.executeQuery("select name,version,event_seq from project")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("Retained creative work");
                assertThat(result.getLong(2)).isZero();
                assertThat(result.getLong(3)).isZero();
            }

            try (var result = query.executeQuery(
                    "select enabled,version,state,operation_id,downloaded_bytes from local_asr_installation")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isFalse();
                assertThat(result.getLong(2)).isEqualTo(1);
                assertThat(result.getString(3)).isEqualTo("MISSING");
                assertThat(result.getObject(4)).isNull();
                assertThat(result.getLong(5)).isZero();
            }
            try (var result = query.executeQuery(
                    "select count(*) from pg_attribute where attrelid='local_asr_installation'::regclass and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null")) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }

        }
    }
    private int migrate(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate().migrationsExecuted;
    }
}

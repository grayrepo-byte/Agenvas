package dev.agenvas.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Additive V59-to-V60 upgrade, checked independently of generated persistence mappings. */
@Testcontainers
class SubtitleMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @Test
    void addsSubtitleStorageAndTaskKindWithoutChangingAnExistingProject() throws Exception {
        migrate("59");
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
        assertThat(migrate("60")).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var query = connection.createStatement()) {
            try (var result = query.executeQuery("select name,version,event_seq from project")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("Retained creative work");
                assertThat(result.getLong(2)).isZero();
                assertThat(result.getLong(3)).isZero();
            }
            try (var result = query.executeQuery("select count(*) from subtitle_draft")) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
            try (var result = query
                    .executeQuery("select pg_get_constraintdef(oid) from pg_constraint where conname='ck_task_kind'")) {
                result.next();
                assertThat(result.getString(1)).contains("SUBTITLE_TRANSCRIPTION");
            }
            try (var result = query.executeQuery(
                    "select count(*) from pg_attribute where attrelid='subtitle_draft'::regclass and attnum>0 and not attisdropped and col_description(attrelid,attnum) is null")) {
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

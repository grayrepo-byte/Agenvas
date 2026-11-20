package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Upgrade evidence uses an isolated real PostgreSQL and independent JDBC assertions. */
@Testcontainers
class ImageStudioMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesExistingCanvasWithoutCreatingStudioDataAndDocumentsNewObjects() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("64").load().migrate();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    insert into app_user(id,login_name,password_hash,status,created_at)
                    values ('00000000-0000-4000-8000-000000001001','synthetic-studio-owner','synthetic-hash','ACTIVE',now());
                    insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at)
                    values ('00000000-0000-4000-8000-000000001002','00000000-0000-4000-8000-000000001001',
                            'Synthetic existing canvas','PORTRAIT_9_16','ACTIVE',now(),now());
                    """);
        }
        var upgraded = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isGreaterThanOrEqualTo(2);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select purpose,aspect_ratio from project")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CANVAS");
                assertThat(rows.getString(2)).isEqualTo("PORTRAIT_9_16");
                assertThat(rows.next()).isFalse();
            }
            for (String table : new String[]{"image_studio_batch", "image_studio_batch_task", "canvas_item"}) {
                try (var rows = sql.executeQuery("select count(*) from " + table)) {
                    rows.next();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
            try (var rows = sql.executeQuery("""
                    select a.attname from pg_attribute a
                    where a.attrelid in ('image_studio_batch'::regclass,'image_studio_batch_task'::regclass)
                      and a.attnum > 0 and not a.attisdropped
                      and col_description(a.attrelid,a.attnum) is null
                    union all
                    select conname from pg_constraint
                    where conrelid in ('image_studio_batch'::regclass,'image_studio_batch_task'::regclass)
                      and obj_description(oid,'pg_constraint') is null
                    union all
                    select indexrelid::regclass::text from pg_index
                    where indrelid in ('image_studio_batch'::regclass,'image_studio_batch_task'::regclass)
                      and obj_description(indexrelid,'pg_class') is null
                    """)) {
                assertThat(rows.next()).as("All Studio columns, constraints and indexes have comments").isFalse();
            }
            sql.executeUpdate("""
                    insert into project(id,owner_id,name,aspect_ratio,status,purpose,created_at,updated_at)
                    values ('00000000-0000-4000-8000-000000001003','00000000-0000-4000-8000-000000001001',
                            'Synthetic internal scope','SQUARE_1_1','ACTIVE','IMAGE_STUDIO',now(),now())
                    """);
            assertThatThrownBy(() -> sql.executeUpdate("""
                    insert into project(id,owner_id,name,aspect_ratio,status,purpose,created_at,updated_at)
                    values ('00000000-0000-4000-8000-000000001004','00000000-0000-4000-8000-000000001001',
                            'Synthetic duplicate scope','SQUARE_1_1','ACTIVE','IMAGE_STUDIO',now(),now())
                    """))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23505"));
        }
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }
}

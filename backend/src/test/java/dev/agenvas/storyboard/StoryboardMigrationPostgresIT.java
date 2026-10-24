package dev.agenvas.storyboard;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the additive V35-to-V36 upgrade retains project data and creates documented board tables. */
@Testcontainers
class StoryboardMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test void upgradesExistingV35ProjectWithoutCreatingOrChangingCreativeContent() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("35").load().migrate();
        UUID owner = UUID.randomUUID(); UUID project = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var insert = connection.prepareStatement("insert into app_user(id,login_name,password_hash,status,created_at) values (?, 'upgrade-admin','synthetic','ACTIVE',now())")) {
                insert.setObject(1, owner); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Existing project','LANDSCAPE_16_9','ACTIVE',now(),now())")) {
                insert.setObject(1, project); insert.setObject(2, owner); insert.executeUpdate();
            }
        }
        var migration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("36").load().migrate();
        assertThat(migration.migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var query = connection.createStatement()) {
            try (var result = query.executeQuery("select name,event_seq,version from project")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("Existing project");
                assertThat(result.getLong(2)).isZero(); assertThat(result.getLong(3)).isZero();
            }
            try (var result = query.executeQuery("select count(*) from storyboard")) { result.next(); assertThat(result.getLong(1)).isZero(); }
            try (var result = query.executeQuery("select count(*) from pg_class where relname in ('storyboard','storyboard_revision','storyboard_pkey','storyboard_revision_pkey') and obj_description(oid,'pg_class') is not null")) {
                result.next(); assertThat(result.getInt(1)).isEqualTo(4);
            }
        }
    }
}

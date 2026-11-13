package dev.agenvas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Upgrade from the preceding schema preserves existing projects and adds only lifecycle constraints. */
@Testcontainers
class ProjectDeletionMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesExistingActiveAndArchivedProjectsWithoutChangingTheirData() throws Exception {
        flyway("42").migrate();
        UUID owner = UUID.randomUUID(), active = UUID.randomUUID(), archived = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            execute(connection,
                    "insert into app_user(id,login_name,password_hash,status,created_at) values (?,'migration-owner','synthetic-hash','ACTIVE',now())",
                    owner);
            execute(connection,
                    "insert into project(id,owner_id,name,aspect_ratio,status,version,created_at,updated_at) values (?,?,'Active','SQUARE_1_1','ACTIVE',2,now(),now())",
                    active, owner);
            execute(connection,
                    "insert into project(id,owner_id,name,aspect_ratio,status,version,archived_at,created_at,updated_at) values (?,?,'Archived','SQUARE_1_1','ARCHIVED',3,now(),now(),now())",
                    archived, owner);
            flyway("43").migrate();
            try (var rows = connection.createStatement()
                    .executeQuery("select name,status,version,archived_at from project order by name")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("name")).isEqualTo("Active");
                assertThat(rows.getString("status")).isEqualTo("ACTIVE");
                assertThat(rows.getLong("version")).isEqualTo(2);
                assertThat(rows.getObject("archived_at")).isNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("name")).isEqualTo("Archived");
                assertThat(rows.getString("status")).isEqualTo("ARCHIVED");
                assertThat(rows.getLong("version")).isEqualTo(3);
                assertThat(rows.getObject("archived_at")).isNotNull();
                assertThat(rows.next()).isFalse();
            }
            execute(connection, "update project set status='DELETED' where id=?", archived);
            assertThatThrownBy(() -> execute(connection, "update project set status='DELETED' where id=?", active))
                    .isInstanceOfSatisfying(SQLException.class,
                            error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private static void execute(Connection connection, String sql, UUID... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++)
                statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}

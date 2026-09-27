package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies V49 backfills legacy card titles from their referenced business objects. */
@Testcontainers
class CanvasItemTitleUpgradePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void backfillsArtifactAndAgentCardsBeforeMakingTitleRequired() throws Exception {
        flyway("48").migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID artifactItem = UUID.randomUUID();
        UUID agentItem = UUID.randomUUID();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("insert into app_user(id,login_name,password_hash,status,created_at) values ('"
                    + owner + "','canvas-title-upgrade','hash','ACTIVE',now())");
            statement.execute("insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values ('"
                    + project + "','" + owner
                    + "','Upgrade','LANDSCAPE_16_9','ACTIVE',now(),now())");
            statement.execute("insert into artifact(id,project_id,kind,title,created_at,updated_at) values ('"
                    + artifact + "','" + project + "','IMAGE','Legacy image',now(),now())");
            statement.execute("insert into agent_instance(id,project_id,profile_key,profile_version,"
                    + "name,instruction,output_group_id,created_at,updated_at) values ('" + agent
                    + "','" + project + "','creator',1,'Legacy agent','Create','"
                    + UUID.randomUUID() + "',now(),now())");
            insertCanvasItem(statement, artifactItem, project, "ARTIFACT", artifact, artifact, null);
            insertCanvasItem(statement, agentItem, project, "AGENT", agent, null, agent);
        }

        Flyway upgrade = flyway(null);
        upgrade.migrate();
        upgrade.migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(singleText(statement, "select title from canvas_item where id='"
                    + artifactItem + "'")).isEqualTo("Legacy image");
            assertThat(singleText(statement, "select title from canvas_item where id='"
                    + agentItem + "'")).isEqualTo("Legacy agent");
            assertThatThrownBy(() -> statement.execute("update canvas_item set title='   ' where id='"
                    + artifactItem + "'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void insertCanvasItem(Statement statement, UUID item, UUID project,
            String subjectType, UUID subjectId, UUID artifactId, UUID agentId) throws SQLException {
        String artifactValue = artifactId == null ? "null" : "'" + artifactId + "'";
        String agentValue = agentId == null ? "null" : "'" + agentId + "'";
        statement.execute("insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,"
                + "agent_instance_id,x,y,width,height,z_index,locked,created_at,updated_at) values ('"
                + item + "','" + project + "','" + subjectType + "','" + subjectId + "',"
                + artifactValue + "," + agentValue + ",20,40,280,180,1,false,now(),now())");
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private String singleText(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}

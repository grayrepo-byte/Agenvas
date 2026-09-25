package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Exercises the production Flyway upgrade against legacy rows in PostgreSQL. */
@Testcontainers
class ShotDurationUpgradePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesWholeSecondShotWithoutMutatingHistoryOrFractionalShot() throws Exception {
        Flyway oldFlyway = flyway("35");
        oldFlyway.migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID scene = UUID.randomUUID();
        UUID sceneVersion = UUID.randomUUID();
        UUID wholeShot = UUID.randomUUID();
        UUID wholeVersion = UUID.randomUUID();
        UUID fractionalShot = UUID.randomUUID();
        UUID fractionalVersion = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID image = UUID.randomUUID();
        UUID imageVersion = UUID.randomUUID();
        UUID imageTask = UUID.randomUUID();
        UUID acceptedTask = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        UUID usage = UUID.randomUUID();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("insert into app_user(id,login_name,password_hash,status,created_at) values ('"
                    + owner + "','upgrade-owner','hash','ACTIVE',now())");
            statement.execute("insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values ('"
                    + project + "','" + owner + "','Upgrade','LANDSCAPE_16_9','ACTIVE',now(),now())");
            insertArtifact(statement, project, scene, sceneVersion, "SCENE",
                    "{\"name\":\"Scene\"}", "[]");
            insertArtifact(statement, project, wholeShot, wholeVersion, "SHOT",
                    "{\"order\":1,\"durationMs\":5000,\"sceneVersionId\":\"" + sceneVersion
                            + "\"}", "[]");
            insertArtifact(statement, project, fractionalShot, fractionalVersion, "SHOT",
                    "{\"order\":2,\"durationMs\":1250,\"sceneVersionId\":\"" + sceneVersion
                            + "\"}", "[]");
            statement.execute("insert into artifact_version_reference(source_version_id,project_id,"
                    + "target_version_id,reference_role,reference_order) values ('" + wholeVersion
                    + "','" + project + "','" + sceneVersion + "','scene',0)");
            insertArtifact(statement, project, image, imageVersion, "IMAGE", "{}", "[]");
            statement.execute("insert into agent_instance(id,project_id,profile_key,profile_version,"
                    + "name,instruction,output_group_id,created_at,updated_at) values ('" + agent
                    + "','" + project + "','creator',1,'Creator','Create','" + UUID.randomUUID()
                    + "',now(),now())");
            statement.execute("insert into agent_run(id,project_id,agent_instance_id,user_id,status,"
                    + "instruction,context_snapshot_json,policy_snapshot_json,profile_version,"
                    + "created_at,updated_at) values ('" + run + "','" + project + "','" + agent
                    + "','" + owner + "','WAITING_APPROVAL','Create','{}','{}',1,now(),now())");
            insertTask(statement, project, run, imageTask, "image-step", "IMAGE_GENERATION",
                    "SUCCEEDED", true);
            insertTask(statement, project, run, acceptedTask, "video-step", "VIDEO_GENERATION",
                    "WAITING_PROVIDER", false);
            statement.execute("insert into shot_keyframe_selection(project_id,shot_artifact_id,"
                    + "shot_version_id,image_artifact_id,image_version_id,source_task_id,"
                    + "selected_by_user_id,created_at,updated_at) values ('" + project + "','"
                    + wholeShot + "','" + wholeVersion + "','" + image + "','" + imageVersion
                    + "','" + imageTask + "','" + owner + "',now(),now())");
            statement.execute("insert into execution_plan(id,project_id,run_id,revision,stage,"
                    + "status,objective,plan_json,input_snapshot_json,input_snapshot_hash,"
                    + "plan_hash,provider_config_version,workflow_version,estimate_json,"
                    + "created_at,updated_at) values ('" + plan + "','" + project + "','" + run
                    + "',1,'VIDEO','PENDING','Legacy','{}','{}','" + "b".repeat(64)
                    + "','" + "c".repeat(64) + "',1,'mock-video-v1','{}',now(),now())");
            statement.execute("insert into export_proposal(id,project_id,run_id,status,input_json,"
                    + "input_pins_json,proposal_hash,project_version,created_at) values ('"
                    + proposal + "','" + project + "','" + run + "','PENDING','{}','[]','"
                    + "d".repeat(64) + "',0,now())");
            statement.execute("insert into usage_ledger(id,project_id,run_id,task_id,operation_key,"
                    + "entry_type,quantity_json,cost_status,cost_source,created_at) values ('"
                    + usage + "','" + project + "','" + run + "','" + acceptedTask
                    + "','legacy-seconds','RESERVATION','{\"videoSeconds\":\"1.250\"}',"
                    + "'UNKNOWN','legacy',now())");
        }

        Flyway upgrade = flyway(null);
        upgrade.migrate();
        upgrade.migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("select av.id,av.version_no,av.schema_version,"
                    + "av.content_json::text from artifact a join artifact_version av "
                    + "on av.id=a.current_version_id where a.id='" + wholeShot + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1, UUID.class)).isNotEqualTo(wholeVersion);
                assertThat(result.getInt(2)).isEqualTo(2);
                assertThat(result.getInt(3)).isEqualTo(2);
                assertThat(result.getString(4)).contains("\"durationSeconds\": 5")
                        .doesNotContain("durationMs");
            }
            assertThat(singleLong(statement, "select count(*) from artifact_version where artifact_id='"
                    + wholeShot + "' and id='" + wholeVersion + "' and content_json->>'durationMs'='5000'"))
                    .isEqualTo(1);
            assertThat(singleLong(statement, "select count(*) from artifact a join artifact_version av "
                    + "on av.id=a.current_version_id where a.id='" + fractionalShot
                    + "' and av.id='" + fractionalVersion + "' and av.content_json->>'durationMs'='1250'"))
                    .isEqualTo(1);
            assertThat(singleLong(statement, "select count(*) from artifact_version_reference r "
                    + "join artifact a on a.current_version_id=r.source_version_id where a.id='"
                    + wholeShot + "' and r.target_version_id='" + sceneVersion + "'"))
                    .isEqualTo(1);
            assertThat(singleText(statement, "select shot_version_id::text from "
                    + "shot_keyframe_selection where shot_artifact_id='" + wholeShot + "'"))
                    .isEqualTo(wholeVersion.toString());
            assertThat(singleText(statement, "select status from execution_plan where id='"
                    + plan + "'")).isEqualTo("STALE");
            assertThat(singleText(statement, "select status from export_proposal where id='"
                    + proposal + "'")).isEqualTo("STALE");
            assertThat(singleText(statement, "select status from task where id='"
                    + acceptedTask + "'")).isEqualTo("WAITING_PROVIDER");
            assertThat(singleText(statement, "select quantity_json->>'videoSeconds' from "
                    + "usage_ledger where id='" + usage + "'")).isEqualTo("1.250");
        }
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

    private void insertArtifact(Statement statement, UUID project, UUID artifact,
            UUID version, String kind, String content, String refs) throws SQLException {
        statement.execute("insert into artifact(id,project_id,kind,title,created_at,updated_at) "
                + "values ('" + artifact + "','" + project + "','" + kind
                + "','Legacy',now(),now())");
        statement.execute("insert into artifact_version(id,project_id,artifact_id,version_no,"
                + "schema_version,content_json,input_refs_json,created_by_kind,created_at) values ('"
                + version + "','" + project + "','" + artifact + "',1,1,'" + content
                + "'::jsonb,'" + refs + "'::jsonb,'USER',now())");
        statement.execute("update artifact set current_version_id='" + version + "' where id='"
                + artifact + "'");
    }

    private long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String singleText(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void insertTask(Statement statement, UUID project, UUID run, UUID task,
            String step, String kind, String status, boolean completed) throws SQLException {
        statement.execute("insert into task(id,project_id,run_id,step_key,kind,status,input_json,"
                + "input_hash,attempt_no,next_action_at,provider_request_id,created_at,updated_at,"
                + "completed_at) values ('" + task + "','" + project + "','" + run + "','" + step
                + "','" + kind + "','" + status + "','{}','" + "a".repeat(64)
                + "',1,now(),'original-request',now(),now(),"
                + (completed ? "now()" : "null") + ")");
    }
}

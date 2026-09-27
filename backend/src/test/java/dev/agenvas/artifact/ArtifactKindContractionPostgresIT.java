package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies V51 contracts the canvas to direct generation: the character, scene and shot kinds and
 * the whole three-shot pipeline are purged, while text/image/video artifacts, their versions and
 * the direct generation tasks survive untouched.
 */
@Testcontainers
class ArtifactKindContractionPostgresIT {

    private static final String HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void purgesRemovedKindsAndPipelineButKeepsDirectGeneration() throws Exception {
        flyway("50").migrate();

        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID waitingRun = UUID.randomUUID();
        UUID text = UUID.randomUUID();
        UUID textVersion = UUID.randomUUID();
        UUID image = UUID.randomUUID();
        UUID imageVersion = UUID.randomUUID();
        UUID shot = UUID.randomUUID();
        UUID shotVersion = UUID.randomUUID();
        UUID character = UUID.randomUUID();
        UUID characterVersion = UUID.randomUUID();
        UUID imageCard = UUID.randomUUID();
        UUID shotCard = UUID.randomUUID();
        UUID directTask = UUID.randomUUID();
        UUID ingestTask = UUID.randomUUID();
        UUID planStepTask = UUID.randomUUID();
        UUID exportTask = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID waitingTool = UUID.randomUUID();
        UUID doneTool = UUID.randomUUID();

        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            seed(statement, new Seed(owner, project, agent, conversation, waitingRun,
                    new Artifact(text, textVersion, "TEXT"), new Artifact(image, imageVersion, "IMAGE"),
                    new Artifact(shot, shotVersion, "SHOT"),
                    new Artifact(character, characterVersion, "CHARACTER"),
                    imageCard, shotCard, directTask, ingestTask, planStepTask, exportTask,
                    plan, waitingTool, doneTool));
        }

        Flyway upgrade = flyway(null);
        upgrade.migrate();
        upgrade.migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(text(statement, "select string_agg(kind, ',' order by kind) from artifact"
                    + " where project_id='" + project + "'")).isEqualTo("IMAGE,TEXT,VIDEO");
            assertThat(count(statement, "select count(*) from artifact_version where artifact_id='"
                    + shot + "'")).isZero();
            assertThat(count(statement, "select count(*) from artifact_version where artifact_id='"
                    + image + "'")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from artifact where id='"
                    + character + "'")).isZero();

            assertThat(count(statement, "select count(*) from canvas_item where artifact_id='"
                    + shotCard + "' and artifact_id='" + shot + "'")).isZero();
            assertThat(count(statement, "select count(*) from canvas_item where id='"
                    + imageCard + "'")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from agent_binding")).isZero();
            assertThat(count(statement, "select count(*) from media_draft where artifact_id='"
                    + image + "' and input_image_version_id is null")).isEqualTo(1);

            for (String removed : List.of("execution_plan", "plan_step", "plan_approval",
                    "shot_keyframe_selection", "export_proposal", "shot_duration_upgrade",
                    "artifact_relation")) {
                assertThat(count(statement, "select count(*) from information_schema.tables"
                        + " where table_schema='public' and table_name='" + removed + "'")).isZero();
            }
            assertThat(count(statement, "select count(*) from information_schema.columns"
                    + " where table_name='task' and column_name='plan_id'")).isZero();

            assertThat(count(statement, "select count(*) from task where id='"
                    + directTask + "'")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from task where id='"
                    + ingestTask + "' and status='CANCELED'")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from task where id in ('"
                    + planStepTask + "','" + exportTask + "')")).isZero();
            assertThat(text(statement, "select artifact_id::text from task_artifact_target"
                    + " where task_id='" + directTask + "'")).isEqualTo(image.toString());

            assertThat(text(statement, "select status from agent_run where id='"
                    + waitingRun + "'")).isEqualTo("CANCELED");
            assertThat(text(statement, "select (completed_at is not null)::text from agent_run"
                    + " where id='" + waitingRun + "'")).isEqualTo("true");
            assertThat(text(statement, "select (active_run_id is null)::text from project"
                    + " where id='" + project + "'")).isEqualTo("true");

            assertThat(text(statement, "select result_json->>'status' from tool_execution"
                    + " where id='" + waitingTool + "'")).isEqualTo("SUCCEEDED");
            assertThat(text(statement, "select result_json->>'status' from tool_execution"
                    + " where id='" + doneTool + "'")).isEqualTo("SUCCEEDED");

            assertThat(count(statement, "select count(*) from artifact_version where artifact_id='"
                    + text + "'")).isEqualTo(1);
        }

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("update artifact set kind='SHOT' where id='"
                    + text + "'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("update task set kind='MEDIA_EXPORT' where id='"
                    + directTask + "'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("update task set origin='PROJECT_EXPORT'"
                    + " where id='" + directTask + "'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("update agent_run set status='WAITING_APPROVAL'"
                    + " where id='" + waitingRun + "'")).isInstanceOf(SQLException.class);
        }
    }

    private record Artifact(UUID id, UUID version, String kind) { }

    private record Seed(UUID owner, UUID project, UUID agent, UUID conversation, UUID waitingRun,
            Artifact text, Artifact image, Artifact shot, Artifact character, UUID imageCard,
            UUID shotCard, UUID directTask, UUID ingestTask, UUID planStepTask, UUID exportTask,
            UUID plan, UUID waitingTool, UUID doneTool) { }

    private void seed(Statement statement, Seed seed) throws SQLException {
        statement.execute("insert into app_user(id,login_name,password_hash,status,created_at)"
                + " values ('" + seed.owner() + "','kind-contraction','hash','ACTIVE',now())");
        statement.execute("insert into project(id,owner_id,name,aspect_ratio,status,created_at,"
                + "updated_at) values ('" + seed.project() + "','" + seed.owner()
                + "','Contraction','LANDSCAPE_16_9','ACTIVE',now(),now())");
        statement.execute("insert into agent_instance(id,project_id,profile_key,profile_version,"
                + "name,instruction,output_group_id,created_at,updated_at) values ('"
                + seed.agent() + "','" + seed.project() + "','creator',1,'Agent','Create','"
                + UUID.randomUUID() + "',now(),now())");
        statement.execute("insert into agent_conversation(id,project_id,agent_instance_id,title,"
                + "turn_count,version,created_at,updated_at) values ('" + seed.conversation() + "','"
                + seed.project() + "','" + seed.agent() + "','Session',0,0,now(),now())");
        statement.execute("insert into agent_run(id,project_id,agent_instance_id,user_id,status,"
                + "instruction,context_snapshot_json,policy_snapshot_json,profile_version,"
                + "next_step_index,version,created_at,updated_at,conversation_id,conversation_turn)"
                + " values ('" + seed.waitingRun() + "','" + seed.project() + "','" + seed.agent()
                + "','" + seed.owner() + "','WAITING_APPROVAL','Make media','{}','{}',1,0,0,"
                + "now(),now(),'" + seed.conversation() + "',1)");
        statement.execute("update project set active_run_id='" + seed.waitingRun() + "' where id='"
                + seed.project() + "'");

        for (Artifact artifact : List.of(seed.text(), seed.image(), seed.shot(), seed.character())) {
            statement.execute("insert into artifact(id,project_id,kind,title,created_at,updated_at)"
                    + " values ('" + artifact.id() + "','" + seed.project() + "','" + artifact.kind()
                    + "','" + artifact.kind() + "','now','now')");
            statement.execute("insert into artifact_version(id,project_id,artifact_id,version_no,"
                    + "schema_version,content_json,input_refs_json,created_by_kind,created_at)"
                    + " values ('" + artifact.version() + "','" + seed.project() + "','"
                    + artifact.id() + "',1,1,'{}','[]','USER',now())");
            statement.execute("update artifact set current_version_id='" + artifact.version()
                    + "' where id='" + artifact.id() + "'");
        }

        UUID video = UUID.randomUUID();
        UUID videoVersion = UUID.randomUUID();
        statement.execute("insert into artifact(id,project_id,kind,title,created_at,updated_at)"
                + " values ('" + video + "','" + seed.project() + "','VIDEO','VIDEO',now(),now())");
        statement.execute("insert into artifact_version(id,project_id,artifact_id,version_no,"
                + "schema_version,content_json,input_refs_json,created_by_kind,created_at)"
                + " values ('" + videoVersion + "','" + seed.project() + "','" + video
                + "',1,1,'{}','[]','USER',now())");
        statement.execute("update artifact set current_version_id='" + videoVersion + "' where id='"
                + video + "'");

        statement.execute("insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,"
                + "x,y,width,height,z_index,locked,created_at,updated_at,title) values ('"
                + seed.imageCard() + "','" + seed.project() + "','ARTIFACT','" + seed.image().id()
                + "','" + seed.image().id() + "',0,0,280,180,1,false,now(),now(),'Image')");
        statement.execute("insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,"
                + "x,y,width,height,z_index,locked,created_at,updated_at,title) values ('"
                + seed.shotCard() + "','" + seed.project() + "','ARTIFACT','" + seed.shot().id()
                + "','" + seed.shot().id() + "',0,0,280,180,2,false,now(),now(),'Shot')");
        statement.execute("insert into artifact_relation(id,project_id,source_artifact_id,"
                + "target_artifact_id,relation_type,created_at) values ('" + UUID.randomUUID() + "','"
                + seed.project() + "','" + seed.shot().id() + "','" + seed.character().id()
                + "','SHOT_CHARACTER',now())");
        statement.execute("insert into artifact_version_reference(source_version_id,project_id,"
                + "target_version_id,reference_role,reference_order) values ('"
                + seed.character().version() + "','" + seed.project() + "','" + seed.image().version()
                + "','referenceImage',0)");
        statement.execute("insert into agent_binding(id,project_id,agent_instance_id,artifact_id,"
                + "selected_version_id,binding_type,created_at) values ('" + UUID.randomUUID() + "','"
                + seed.project() + "','" + seed.agent() + "','" + seed.shot().id() + "','"
                + seed.shot().version() + "','INPUT',now())");
        statement.execute("insert into media_draft(project_id,artifact_id,prompt,"
                + "input_image_version_id,version,created_at,updated_at,display_mode) values ('"
                + seed.project() + "','" + seed.image().id() + "','prompt','"
                + seed.shot().version() + "',0,now(),now(),'DRAFT')");

        insertTask(statement, seed, seed.directTask(), null, "direct-1", "IMAGE_GENERATION",
                "READY", "USER_DIRECT", seed.image().id());
        insertTask(statement, seed, seed.ingestTask(), seed.waitingRun(), "ingest-1",
                "ASSET_INGEST", "READY", "AGENT", null);
        insertTask(statement, seed, seed.planStepTask(), seed.waitingRun(), "shot-1",
                "IMAGE_GENERATION", "PENDING", "AGENT", seed.shot().id());
        insertTask(statement, seed, seed.exportTask(), null, "export-1", "MEDIA_EXPORT",
                "READY", "PROJECT_EXPORT", null);

        statement.execute("insert into execution_plan(id,project_id,run_id,revision,stage,status,"
                + "objective,plan_json,input_snapshot_json,input_snapshot_hash,plan_hash,"
                + "provider_config_version,workflow_version,estimate_json,created_at,updated_at)"
                + " values ('"
                + seed.plan() + "','" + seed.project() + "','" + seed.waitingRun()
                + "',1,'IMAGE','APPROVED','Objective','{}','{}','" + HASH + "','" + HASH
                + "',1,'workflow','{}',now(),now())");
        statement.execute("insert into plan_step(plan_id,project_id,step_key,ordinal,kind,"
                + "shot_artifact_id,shot_version_id,output_slot_key,input_json,"
                + "dependency_keys_json) values ('" + seed.plan() + "','" + seed.project()
                + "','shot-1',0,'IMAGE_GENERATION','" + seed.shot().id() + "','"
                + seed.shot().version() + "','slot-0','{}','[]')");
        statement.execute("insert into plan_approval(id,plan_id,project_id,run_id,"
                + "approved_by_user_id,approved_plan_hash,approved_input_hash,reservation_json,"
                + "created_at) values ('" + UUID.randomUUID() + "','" + seed.plan() + "','"
                + seed.project() + "','" + seed.waitingRun() + "','" + seed.owner() + "','" + HASH
                + "','" + HASH + "','{}',now())");
        statement.execute("insert into shot_keyframe_selection(project_id,shot_artifact_id,"
                + "shot_version_id,image_artifact_id,image_version_id,source_task_id,"
                + "selected_by_user_id,version,created_at,updated_at) values ('" + seed.project()
                + "','" + seed.shot().id() + "','" + seed.shot().version() + "','"
                + seed.image().id() + "','" + seed.image().version() + "','" + seed.planStepTask()
                + "','" + seed.owner() + "',0,now(),now())");
        statement.execute("insert into export_proposal(id,project_id,run_id,status,input_json,"
                + "input_pins_json,proposal_hash,project_version,created_at) values ('"
                + UUID.randomUUID() + "','" + seed.project() + "','" + seed.waitingRun()
                + "','PENDING','{}','[]','" + HASH + "',0,now())");

        statement.execute("insert into llm_turn(project_id,run_id,step_index,status,"
                + "model_config_version,request_json,response_json,created_at,responded_at)"
                + " values ('" + seed.project() + "','" + seed.waitingRun()
                + "',0,'RESPONDED',1,'{}','{}',now(),now())");
        insertToolExecution(statement, seed, seed.waitingTool(), "WAITING_APPROVAL");
        insertToolExecution(statement, seed, seed.doneTool(), "SUCCEEDED");
    }

    private void insertTask(Statement statement, Seed seed, UUID task, UUID runId, String stepKey,
            String kind, String status, String origin, UUID target) throws SQLException {
        String run = runId == null ? "null" : "'" + runId + "'";
        statement.execute("insert into task(id,project_id,run_id,step_key,kind,status,input_json,"
                + "input_hash,attempt_no,next_action_at,lease_epoch,version,created_at,updated_at,"
                + "cancel_requested,origin) values ('" + task + "','" + seed.project() + "'," + run
                + ",'" + stepKey + "','" + kind + "','" + status + "','{}','" + HASH
                + "',1,now(),0,0,now(),now(),false,'" + origin + "')");
        if (target != null) {
            statement.execute("insert into task_artifact_target(task_id,project_id,artifact_id,"
                    + "expected_artifact_version,output_slot_key) values ('" + task + "','"
                    + seed.project() + "','" + target + "',0,null)");
        }
    }

    private void insertToolExecution(Statement statement, Seed seed, UUID id, String status)
            throws SQLException {
        statement.execute("insert into tool_execution(id,project_id,run_id,step_index,tool_call_id,"
                + "tool_name,argument_hash,status,result_json,created_at,completed_at) values ('"
                + id + "','" + seed.project() + "','" + seed.waitingRun() + "',0,'call-" + id
                + "','propose_generation_plan','" + HASH + "','COMPLETED','{\"status\":\"" + status
                + "\",\"userVisibleSummary\":\"proposal\"}',now(),now())");
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

    private String text(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private int count(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}

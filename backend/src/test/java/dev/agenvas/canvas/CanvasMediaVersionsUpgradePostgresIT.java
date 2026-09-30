package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V60 keeps existing nodes and recovers their own task outputs without merging branches. */
@Testcontainers
class CanvasMediaVersionsUpgradePostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void preservesNodesAndBackfillsSelectedAndUnselectedResults() throws Exception {
        flyway("59").migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID derived = UUID.randomUUID();
        UUID pendingNode = UUID.randomUUID();
        UUID selected = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        UUID edit = UUID.randomUUID();
        UUID lateTask = UUID.randomUUID();
        UUID editTask = UUID.randomUUID();
        UUID pendingTask = UUID.randomUUID();
        try (Connection c = connection(); Statement sql = c.createStatement()) {
            sql.execute("insert into app_user(id,login_name,password_hash,status,created_at) values ('"
                    + owner + "','version-upgrade-admin','hash','ACTIVE',now())");
            sql.execute("insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values ('"
                    + project + "','" + owner + "','Preserved project','SQUARE_1_1','ACTIVE',now(),now())");
            sql.execute("insert into artifact(id,project_id,kind,title,created_at,updated_at) values ('"
                    + artifact + "','" + project + "','IMAGE','Preserved image',now(),now())");
            version(sql, project, artifact, selected, 1, null);
            version(sql, project, artifact, late, 2, lateTask);
            version(sql, project, artifact, edit, 3, editTask);
            node(sql, project, artifact, source, selected);
            node(sql, project, artifact, derived, edit);
            node(sql, project, artifact, pendingNode, selected);
            task(sql, project, lateTask, source, "CANCELED");
            task(sql, project, editTask, derived, "SUCCEEDED");
            task(sql, project, pendingTask, pendingNode, "READY");
            sql.execute("update task set input_json = input_json || '{\"sourceCanvasItemId\":\""
                    + source + "\",\"parentVersionId\":\"" + selected
                    + "\"}'::jsonb where id='" + pendingTask + "'");
        }
        flyway(null).migrate();
        try (Connection c = connection(); Statement sql = c.createStatement()) {
            try (var rows = sql.executeQuery("select count(*) from canvas_item")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(3);
            }
            try (var rows = sql.executeQuery("select selected_version_id, media_selection_epoch "
                    + "from canvas_item where id='" + source + "'")) {
                rows.next(); assertThat(rows.getObject(1, UUID.class)).isEqualTo(selected);
                assertThat(rows.getLong(2)).isZero();
            }
            try (var rows = sql.executeQuery("select artifact_version_id from canvas_item_media_version "
                    + "where canvas_item_id='" + source + "'")) {
                var ids = new java.util.ArrayList<UUID>();
                while (rows.next()) ids.add(rows.getObject(1, UUID.class));
                assertThat(ids).containsExactlyInAnyOrder(selected, late);
            }
            try (var rows = sql.executeQuery("select artifact_version_id from canvas_item_media_version "
                    + "where canvas_item_id='" + derived + "'")) {
                rows.next(); assertThat(rows.getObject(1, UUID.class)).isEqualTo(edit);
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("select count(*) from canvas_item_media_version "
                    + "where canvas_item_id='" + pendingNode + "'")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            sql.execute("delete from canvas_item where id='" + source + "'");
            try (var rows = sql.executeQuery("select count(*) from artifact_version")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(3);
            }
        }
    }

    private void version(Statement sql, UUID project, UUID artifact, UUID id, int number,
            UUID task) throws Exception {
        String content = task == null ? "{}" : "{\"sourceTaskId\":\"" + task + "\"}";
        sql.execute("insert into artifact_version(id,project_id,artifact_id,version_no,schema_version,"
                + "content_json,input_refs_json,created_by_kind,created_at) values ('" + id + "','"
                + project + "','" + artifact + "'," + number + ",1,'" + content
                + "'::jsonb,'[]'::jsonb,'TASK',now())");
    }

    private void node(Statement sql, UUID project, UUID artifact, UUID id, UUID selected) throws Exception {
        sql.execute("insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,"
                + "selected_version_id,x,y,width,height,z_index,created_at,updated_at) values ('" + id
                + "','" + project + "','ARTIFACT','" + artifact + "','" + artifact
                + "','Preserved node','" + selected + "',0,0,280,240,0,now(),now())");
    }

    private void task(Statement sql, UUID project, UUID id, UUID card, String status) throws Exception {
        String completed = status.equals("READY") ? "NULL" : "now()";
        sql.execute("insert into task(id,project_id,step_key,kind,status,input_json,input_hash,"
                + "attempt_no,next_action_at,created_at,updated_at,completed_at,origin) values ('" + id
                + "','" + project + "','" + id + "','IMAGE_GENERATION','" + status
                + "','{\"canvasItemId\":\"" + card + "\"}'::jsonb,'" + "a".repeat(64)
                + "',1,now(),now(),now()," + completed + ",'USER_DIRECT')");
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

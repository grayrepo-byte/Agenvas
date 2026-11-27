package dev.agenvas.bootstrap;

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

/** Checks scope constraints and preserves original requests while extending the request format. */
@Testcontainers
class VideoStudioMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesV67ThroughV69WithOriginalHistoryAndNewReferenceRequests() throws SQLException {
        migrateTo("67");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var sql = connection.createStatement()) {
            UUID owner = user(connection, "video-migration-owner", "ACTIVE");
            UUID otherOwner = user(connection, "video-migration-other-owner", "DISABLED");
            UUID canvas = project(connection, owner, "CANVAS");
            UUID imageScope = project(connection, owner, "IMAGE_STUDIO");
            UUID imageTask = task(connection, imageScope, "IMAGE_GENERATION");
            UUID artifact = UUID.randomUUID();
            UUID canvasItem = UUID.randomUUID();
            update(connection, """
                    insert into artifact(id,project_id,kind,title,created_at,updated_at)
                    values (?,?,'IMAGE','Retained canvas image',now(),now())
                    """, artifact, canvas);
            update(connection, """
                    insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,x,y,width,height,
                                            z_index,created_at,updated_at)
                    values (?,?,'ARTIFACT',?,?,'Retained canvas item',20,40,320,240,0,now(),now())
                    """, canvasItem, canvas, artifact, artifact);
            UUID imageBatch = UUID.randomUUID();
            update(connection, """
                    insert into image_studio_batch(id,owner_id,project_id,command_key,request_hash,request_json,
                                                   created_at,deleted_at,delete_files)
                    values (?,?,?,'prior-image-command',repeat('a',64),'{"schemaVersion":1}',now(),now(),false)
                    """, imageBatch, owner, imageScope);
            update(connection, """
                    insert into image_studio_batch_task(batch_id,project_id,ordinal,task_id) values (?,?,0,?)
                    """, imageBatch, imageScope, imageTask);

            assertThat(migrateTo("68")).isEqualTo(1);
            try (var rows = sql.executeQuery("""
                    select p.purpose,p.aspect_ratio,c.id,c.title,c.x,c.y,a.id
                    from project p join canvas_item c on c.project_id=p.id
                    join artifact a on a.id=c.artifact_id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CANVAS");
                assertThat(rows.getString(2)).isEqualTo("PORTRAIT_9_16");
                assertThat(rows.getObject(3, UUID.class)).isEqualTo(canvasItem);
                assertThat(rows.getString(4)).isEqualTo("Retained canvas item");
                assertThat(rows.getBigDecimal(5)).isEqualByComparingTo("20");
                assertThat(rows.getBigDecimal(6)).isEqualByComparingTo("40");
                assertThat(rows.getObject(7, UUID.class)).isEqualTo(artifact);
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("""
                    select p.purpose,b.id,b.command_key,b.deleted_at,b.delete_files,t.task_id
                    from image_studio_batch b join project p on p.id=b.project_id
                    join image_studio_batch_task t on t.batch_id=b.id and t.project_id=p.id
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("IMAGE_STUDIO");
                assertThat(rows.getObject(2, UUID.class)).isEqualTo(imageBatch);
                assertThat(rows.getString(3)).isEqualTo("prior-image-command");
                assertThat(rows.getObject(4)).isNotNull();
                assertThat(rows.getObject(5, Boolean.class)).isFalse();
                assertThat(rows.getObject(6, UUID.class)).isEqualTo(imageTask);
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("select id from video_studio_generation")) {
                assertThat(rows.next()).isFalse();
            }
            assertConstraint(() -> project(connection, owner, "IMAGE_STUDIO"), "23505",
                    "uq_project_image_studio_owner");
            UUID video = project(connection, owner, "VIDEO_STUDIO");
            UUID otherVideo = project(connection, otherOwner, "VIDEO_STUDIO");
            assertConstraint(() -> project(connection, owner, "VIDEO_STUDIO"), "23505",
                    "uq_project_video_studio_owner");
            assertConstraint(() -> project(connection, owner, "UNKNOWN"), "23514", "ck_project_purpose");

            UUID videoTask = task(connection, video, "VIDEO_GENERATION");
            UUID secondVideoTask = task(connection, video, "VIDEO_GENERATION");
            UUID otherTask = task(connection, otherVideo, "VIDEO_GENERATION");
            UUID canvasTask = task(connection, canvas, "VIDEO_GENERATION");
            assertConstraint(
                    () -> generation(connection, owner, canvas, canvasTask, "command", "{\"schemaVersion\":1}"),
                    "23503", "fk_video_studio_generation_project");
            assertConstraint(
                    () -> generation(connection, owner, imageScope, imageTask, "command", "{\"schemaVersion\":1}"),
                    "23503", "fk_video_studio_generation_project");
            assertConstraint(
                    () -> generation(connection, otherOwner, video, videoTask, "command", "{\"schemaVersion\":1}"),
                    "23503", "fk_video_studio_generation_project");
            assertConstraint(() -> generation(connection, owner, video, otherTask, "command", "{\"schemaVersion\":1}"),
                    "23503", "fk_video_studio_generation_task");
            for (String invalid : new String[]{"{}", "[]", "{\"schemaVersion\":2}", "{\"schemaVersion\":\"1\"}"}) {
                assertConstraint(() -> generation(connection, owner, video, videoTask, "command", invalid),
                        "23514", "ck_video_studio_generation_request");
            }
            generation(connection, owner, video, videoTask, "command", "{\"schemaVersion\":1}");
            generation(connection, otherOwner, otherVideo, otherTask, "command", "{\"schemaVersion\":1}");
            assertConstraint(
                    () -> generation(connection, owner, video, secondVideoTask, "command", "{\"schemaVersion\":1}"),
                    "23505", "uq_video_studio_generation_owner_command");
            assertConstraint(
                    () -> generation(connection, owner, video, videoTask, "different", "{\"schemaVersion\":1}"),
                    "23505", "uq_video_studio_generation_task");
            assertConstraint(() -> sql.executeUpdate("update video_studio_generation set project_purpose='CANVAS'"),
                    "23514", "ck_video_studio_generation_purpose");

            assertThat(migrateTo("69")).isEqualTo(1);
            assertConstraint(() -> generation(connection, owner, video, secondVideoTask, "malformed",
                    "{\"schemaVersion\":2,\"references\":[]}"), "23514", "ck_video_studio_generation_request");
            generation(connection, owner, video, secondVideoTask, "references",
                    "{\"schemaVersion\":2,\"references\":[],\"dynamicValues\":{}}");
            try (var query = connection.prepareStatement("""
                    select command_key, request_json->>'schemaVersion', task_id from video_studio_generation
                    where project_id = ? order by command_key
                    """)) {
                query.setObject(1, video);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("command");
                    assertThat(rows.getString(2)).isEqualTo("1");
                    assertThat(rows.getObject(3, UUID.class)).isEqualTo(videoTask);
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("references");
                    assertThat(rows.getString(2)).isEqualTo("2");
                    assertThat(rows.getObject(3, UUID.class)).isEqualTo(secondVideoTask);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
        assertThat(migrateTo("69")).isZero();
    }

    private static int migrateTo(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate().migrationsExecuted;
    }

    private static UUID user(Connection connection, String login, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        update(connection, """
                insert into app_user(id,login_name,password_hash,status,created_at)
                values (?,?,'synthetic-hash',?,now())
                """, id, login, status);
        return id;
    }

    private static UUID project(Connection connection, UUID owner, String purpose) throws SQLException {
        UUID id = UUID.randomUUID();
        update(connection, """
                insert into project(id,owner_id,name,aspect_ratio,status,purpose,created_at,updated_at)
                values (?,?,?,'PORTRAIT_9_16','ACTIVE',?,now(),now())
                """, id, owner, purpose, purpose);
        return id;
    }

    private static UUID task(Connection connection, UUID project, String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        update(connection, """
                insert into task(id,project_id,step_key,kind,status,input_json,input_hash,attempt_no,
                                 next_action_at,created_at,updated_at)
                values (?,?,?,?,'READY','{}',repeat('b',64),1,now(),now(),now())
                """, id, project, id.toString(), kind);
        return id;
    }

    private static void generation(Connection connection, UUID owner, UUID project, UUID task, String key,
            String request) throws SQLException {
        update(connection, """
                insert into video_studio_generation(id,owner_id,project_id,task_id,command_key,request_hash,
                                                     request_json,created_at)
                values (?,?,?,?,?,repeat('c',64),cast(? as jsonb),now())
                """, UUID.randomUUID(), owner, project, task, key, request);
    }

    private static void update(Connection connection, String statement, Object... arguments) throws SQLException {
        try (var sql = connection.prepareStatement(statement)) {
            for (int index = 0; index < arguments.length; index++) {
                sql.setObject(index + 1, arguments[index]);
            }
            assertThat(sql.executeUpdate()).isEqualTo(1);
        }
    }

    private static void assertConstraint(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            String state, String constraint) {
        assertThatThrownBy(action).isInstanceOfSatisfying(SQLException.class, error -> {
            assertThat(error.getSQLState()).isEqualTo(state);
            assertThat(error.getMessage()).contains(constraint);
        });
    }
}

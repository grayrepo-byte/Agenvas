package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** V51 extends node identity without rewriting existing artifacts, drafts or fixed bindings. */
@Testcontainers
class CanvasCharacterNodeMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesV50PreservingMediaAndEnforcingCommentedNodeBoundaries() throws Exception {
        migrate("50");
        UUID owner = UUID.randomUUID(),
                project = UUID.randomUUID(),
                other = UUID.randomUUID(),
                artifact = UUID.randomUUID(),
                card = UUID.randomUUID();
        try (Connection db = connection()) {
            execute(
                    db,
                    "insert into app_user(id,login_name,password_hash,status,created_at) values"
                            + " (?,'node-upgrade','synthetic-hash','ACTIVE',now())",
                    owner);
            for (UUID id : new UUID[]{project, other})
                execute(
                        db,
                        "insert into"
                                + " project(id,owner_id,name,aspect_ratio,status,created_at,updated_at)"
                                + " values (?,?,'Existing','SQUARE_1_1','ACTIVE',now(),now())",
                        id,
                        owner);
            execute(
                    db,
                    "insert into artifact(id,project_id,kind,title,created_at,updated_at) values"
                            + " (?,?,'IMAGE','Existing',now(),now())",
                    artifact,
                    project);
            execute(
                    db,
                    "insert into"
                            + " canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,x,y,width,height,z_index,created_at,updated_at)"
                            + " values (?,?,'ARTIFACT',?,?,'Existing',0,0,300,300,0,now(),now())",
                    card,
                    project,
                    artifact,
                    artifact);
            execute(
                    db,
                    "insert into"
                            + " media_draft(project_id,canvas_item_id,prompt,created_at,updated_at)"
                            + " values (?,?,'Keep exact draft',now(),now())",
                    project,
                    card);
        }
        migrate("51");
        try (Connection db = connection()) {
            assertThat(
                    text(
                            db,
                            "select prompt from media_draft where canvas_item_id='"
                                    + card
                                    + "'"))
                    .isEqualTo("Keep exact draft");
            String tables = "('canvas_character_node','canvas_character_connection','canvas_character_command')";
            assertThat(
                    text(
                            db,
                            "select count(*)::text from pg_class c join pg_namespace n on"
                                    + " n.oid=c.relnamespace where n.nspname='public' and"
                                    + " c.relname in "
                                    + tables
                                    + " and obj_description(c.oid,'pg_class') is null"))
                    .isEqualTo("0");
            assertThat(
                    text(
                            db,
                            "select count(*)::text from pg_attribute a join pg_class c on"
                                    + " c.oid=a.attrelid where c.relname in "
                                    + tables
                                    + " and a.attnum>0 and not a.attisdropped and"
                                    + " col_description(c.oid,a.attnum) is null"))
                    .isEqualTo("0");
            assertThat(
                    text(
                            db,
                            "select count(*)::text from pg_constraint k join pg_class c on"
                                    + " c.oid=k.conrelid where c.relname in "
                                    + tables
                                    + " and obj_description(k.oid,'pg_constraint') is"
                                    + " null"))
                    .isEqualTo("0");
            assertThat(
                    text(
                            db,
                            "select count(*)::text from pg_index i join pg_class c on"
                                    + " c.oid=i.indrelid where c.relname in "
                                    + tables
                                    + " and obj_description(i.indexrelid,'pg_class') is"
                                    + " null"))
                    .isEqualTo("0");
            UUID character = UUID.randomUUID(), node = UUID.randomUUID();
            execute(
                    db,
                    "insert into"
                            + " project_character(id,project_id,content_json,version,created_at,updated_at)"
                            + " values"
                            + " (?,?,'{\"schemaVersion\":1,\"content\":{\"name\":\"Aster\"}}',1,now(),now())",
                    character,
                    project);
            execute(
                    db,
                    "insert into"
                            + " project_character_revision(project_id,character_id,version,content_json,archived,created_at)"
                            + " values"
                            + " (?,?,1,'{\"schemaVersion\":1,\"content\":{\"name\":\"Aster\"}}',false,now())",
                    project,
                    character);
            execute(
                    db,
                    "insert into"
                            + " canvas_item(id,project_id,subject_type,subject_id,character_id,title,x,y,width,height,z_index,created_at,updated_at)"
                            + " values (?,?,'CHARACTER',?,?,'Character',0,0,300,300,0,now(),now())",
                    node,
                    project,
                    character,
                    character);
            execute(
                    db,
                    "insert into"
                            + " canvas_character_node(project_id,canvas_item_id,character_id,character_version,selection_json)"
                            + " values (?,?,?,1,'{\"schemaVersion\":1,\"content\":{}}')",
                    project,
                    node,
                    character);
            assertThatThrownBy(
                    () -> execute(
                            db,
                            "insert into"
                                    + " canvas_character_connection(id,project_id,source_canvas_item_id,target_canvas_item_id)"
                                    + " values (?,?,?,?)",
                            UUID.randomUUID(),
                            other,
                            node,
                            card))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            e -> assertThat(e.getSQLState()).isEqualTo("23503"));
            execute(
                    db,
                    "insert into"
                            + " canvas_character_connection(id,project_id,source_canvas_item_id,target_canvas_item_id)"
                            + " values (?,?,?,?)",
                    UUID.randomUUID(),
                    project,
                    node,
                    card);
            execute(db, "delete from canvas_item where id=?", node);
            assertThat(text(db, "select count(*)::text from canvas_character_connection"))
                    .isEqualTo("0");
            assertThat(
                    text(
                            db,
                            "select prompt from media_draft where canvas_item_id='"
                                    + card
                                    + "'"))
                    .isEqualTo("Keep exact draft");
        }
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(Connection db, String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++)
                statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private String text(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement();
                var result = statement.executeQuery(sql)) {
            if (!result.next())
                throw new SQLException("Expected one verification row");
            return result.getString(1);
        }
    }
}

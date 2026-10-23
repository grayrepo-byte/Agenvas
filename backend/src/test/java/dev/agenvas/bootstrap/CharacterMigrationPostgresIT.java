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

/** V35 is additive: deployed creative content survives and new revisions cannot be overwritten. */
@Testcontainers
class CharacterMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void upgradesV34WithoutChangingContentAndEnforcesCommentedImmutableProjectBoundaries() throws Exception {
        migrate("34");
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), other = UUID.randomUUID();
        UUID artifact = UUID.randomUUID(), version = UUID.randomUUID(), card = UUID.randomUUID();
        try (Connection db = connection()) {
            execute(db, "insert into app_user(id,login_name,password_hash,status,created_at) values (?,'character-upgrade','synthetic-hash','ACTIVE',now())", owner);
            for (UUID id : new UUID[] {project, other}) execute(db,
                    "insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Existing project','LANDSCAPE_16_9','ACTIVE',now(),now())", id, owner);
            execute(db, "insert into artifact(id,project_id,kind,title,created_at,updated_at) values (?,?,'IMAGE','Existing image',now(),now())", artifact, project);
            execute(db, "insert into artifact_version(id,project_id,artifact_id,version_no,schema_version,content_json,input_refs_json,created_by_kind,created_at) values (?,?,?,1,1,'{\"prompt\":\"Keep exact old input\"}','[]','USER',now())", version, project, artifact);
            execute(db, "insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,selected_version_id,x,y,width,height,z_index,created_at,updated_at) values (?,?,'ARTIFACT',?,?,'Existing shot',?,0,0,320,240,0,now(),now())", card, project, artifact, artifact, version);
            execute(db, "insert into media_draft(project_id,canvas_item_id,prompt,created_at,updated_at) values (?,?,'Keep editable draft',now(),now())", project, card);
        }
        migrate("35");
        try (Connection db = connection()) {
            assertThat(text(db, "select content_json->>'prompt' from artifact_version where id='" + version + "'"))
                    .isEqualTo("Keep exact old input");
            assertThat(text(db, "select prompt from media_draft where canvas_item_id='" + card + "'"))
                    .isEqualTo("Keep editable draft");
            assertThat(text(db, "select selected_version_id::text from canvas_item where id='" + card + "'"))
                    .isEqualTo(version.toString());
            assertThat(text(db, "select count(*)::text from project_character")).isEqualTo("0");
            String tables = "('project_character','project_character_revision','canvas_character_binding','canvas_character_binding_member','project_character_command')";
            assertThat(text(db, "select count(*)::text from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in " + tables + " and obj_description(c.oid,'pg_class') is null")).isEqualTo("0");
            assertThat(text(db, "select count(*)::text from pg_attribute a join pg_class c on c.oid=a.attrelid where c.relname in " + tables + " and a.attnum>0 and not a.attisdropped and col_description(c.oid,a.attnum) is null")).isEqualTo("0");
            assertThat(text(db, "select count(*)::text from pg_constraint k join pg_class c on c.oid=k.conrelid where c.relname in " + tables + " and obj_description(k.oid,'pg_constraint') is null")).isEqualTo("0");
            UUID character = UUID.randomUUID();
            execute(db, "insert into project_character(id,project_id,content_json,version,created_at,updated_at) values (?,?,'{\"schemaVersion\":1,\"content\":{\"name\":\"Alice\"}}',1,now(),now())", character, project);
            execute(db, "insert into project_character_revision(project_id,character_id,version,content_json,archived,created_at) values (?,?,1,'{\"schemaVersion\":1,\"content\":{\"name\":\"Alice\"}}',false,now())", project, character);
            assertThatThrownBy(() -> execute(db, "update project_character_revision set content_json='{}' where character_id=?", character))
                    .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
            assertThatThrownBy(() -> execute(db, "delete from project_character_revision where character_id=?", character))
                    .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("P0001"));
            assertThatThrownBy(() -> execute(db, "insert into canvas_character_binding(project_id,canvas_item_id,version,draft_version,snapshots_json,draft_state_json,needs_review,updated_at) values (?,?,1,0,'{\"schemaVersion\":1,\"content\":[]}','{\"schemaVersion\":1,\"content\":{}}',true,now())", other, card))
                    .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));
        }
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load().migrate();
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private void execute(Connection db, String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
    private String text(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Expected one verification row");
            return result.getString(1);
        }
    }
}

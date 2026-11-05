package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL migration checks with synthetic project data; no provider calls. */
@Testcontainers
class EnglishDirectorDefaultMigrationPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @BeforeEach void previousVersion() {
        migration("45").clean();
        migration("43").migrate();
    }

    @Test void upgradesOnlyTheExactGlobalDefaultAndPreservesExistingProjectInstructions() throws Exception {
        String oldBody;
        String projectBefore;
        String frozenBefore;
        try (var db = connection()) {
            oldBody = scalar(db, "select content from prompt_definition where key='agent.director'");
            UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), agent = UUID.randomUUID();
            execute(db, "insert into app_user(id,login_name,password_hash,status,created_at) values (?,?,'synthetic-hash','ACTIVE',now())", owner, "english-default-" + owner);
            execute(db, "insert into project(id,owner_id,name,aspect_ratio,status,event_seq,created_at,updated_at) values (?,?,'Synthetic existing project','LANDSCAPE_16_9','ACTIVE',7,now(),now())", project, owner);
            execute(db, "insert into agent_instance(id,project_id,name,profile_key,profile_version,instruction,version,created_at,updated_at) values (?,?,'Synthetic retained director','creator',1,?,4,now(),now())", agent, project, oldBody);
            execute(db, "update prompt_definition set name='Synthetic renamed default',description='Synthetic description',version=12 where key='agent.director'");
            UUID conversation = UUID.randomUUID();
            execute(db, "insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at) values (?,?,?,'Synthetic frozen conversation',now(),now())", conversation, project, agent);
            execute(db, """
                    insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,context_snapshot_json,
                        policy_snapshot_json,profile_version,created_at,updated_at,conversation_id,conversation_turn)
                    values (?,?,?,?,'WAITING_TASKS','Synthetic frozen request',jsonb_build_object('agentInstruction',?::text),
                        '{"systemPromptVersion":14,"toolPolicyVersion":3}',1,now(),now(),?,1)
                    """, UUID.randomUUID(), project, agent, owner, oldBody, conversation);
            projectBefore = snapshot(db, "agent_instance");
            frozenBefore = snapshot(db, "agent_run");
        }
        migration("45").migrate();
        try (var db = connection()) {
            String current = scalar(db, "select content from prompt_definition where key='agent.director'");
            assertThat(current).startsWith("You are the Director Agent").doesNotMatch("(?s).*\\p{IsHan}.*");
            assertThat(current).contains("user's language", "read_storyboard", "write_storyboard_shots", "expectedShotRevision", "UNKNOWN");
            assertThat(current.length()).isLessThanOrEqualTo(8000);
            assertThat(scalar(db, "select name from prompt_definition where key='agent.director'")).isEqualTo("Synthetic renamed default");
            assertThat(scalar(db, "select description from prompt_definition where key='agent.director'")).isEqualTo("Synthetic description");
            assertThat(scalar(db, "select version::text from prompt_definition where key='agent.director'")).isEqualTo("13");
            assertThat(snapshot(db, "agent_instance")).isEqualTo(projectBefore);
            assertThat(snapshot(db, "agent_run")).isEqualTo(frozenBefore);
            assertThat(scalar(db, "select event_seq::text from project")).isEqualTo("7");
            assertThat(scalar(db, "select count(*)::text from project_event")).isEqualTo("0");
        }
        assertThat(migration("45").migrate().migrationsExecuted).isZero();
    }

    @Test void retainsCustomizedAndNearMatchingGlobalDefaults() throws Exception {
        for (String suffix : List.of(" ", "\nSynthetic customized workflow")) {
            previousVersion();
            String before;
            try (var db = connection()) {
                execute(db, "update prompt_definition set content=content || ?,version=27 where key='agent.director'", suffix);
                before = snapshot(db, "prompt_definition");
            }
            migration("45").migrate();
            try (var db = connection()) { assertThat(snapshot(db, "prompt_definition")).isEqualTo(before); }
        }
    }

    @Test void freshInstallUsesEnglishDefaultWithoutRewritingOtherBuiltInContent() throws Exception {
        migration("45").clean();
        migration("45").migrate();
        try (var db = connection()) {
            assertThat(scalar(db, "select name from prompt_definition where key='agent.director'")).isEqualTo("Director Agent");
            assertThat(scalar(db, "select content from prompt_definition where key='agent.director'"))
                    .startsWith("You are the Director Agent").contains("propose_media_generation", "shotId");
            assertThat(scalar(db, "select count(*)::text from agent_instance")).isEqualTo("0");
        }
    }

    private Flyway migration(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).target(target).load();
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private String snapshot(Connection db, String table) throws SQLException {
        return scalar(db, "select coalesce(jsonb_agg(value order by value::text),'[]'::jsonb)::text from (select to_jsonb(t) value from " + table + " t) rows");
    }
    private void execute(Connection db, String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
    private String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue(); return result.getString(1);
        }
    }
}

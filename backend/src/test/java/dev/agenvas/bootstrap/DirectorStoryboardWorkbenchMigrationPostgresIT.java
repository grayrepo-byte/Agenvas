package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL upgrade checks; all project history is synthetic and never calls a model. */
@Testcontainers
class DirectorStoryboardWorkbenchMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @BeforeEach
    void previousVersion() {
        migration("37").clean();
        migration("36").migrate();
    }

    @Test
    void upgradesOnlyExactOldBodiesAndRetainsNamesCustomizationsAndFrozenHistory() throws Exception {
        String previous = previousDirector();
        UUID owner = UUID.randomUUID();
        UUID firstProject = UUID.randomUUID(), renamedProject = UUID.randomUUID();
        UUID customProject = UUID.randomUUID(), emptyProject = UUID.randomUUID();
        UUID first = UUID.randomUUID(), renamed = UUID.randomUUID(), custom = UUID.randomUUID();
        Map<String, String> history = new LinkedHashMap<>();
        String customBefore;
        String functionsBefore;
        try (Connection db = connection()) {
            owner(db, owner);
            project(db, firstProject, owner, 7);
            project(db, renamedProject, owner, 13);
            project(db, customProject, owner, 21);
            project(db, emptyProject, owner, 4);
            director(db, firstProject, first, "Synthetic original director", previous, 0);
            director(db, renamedProject, renamed, "Synthetic renamed director", previous.strip(), 8);
            // A near match is still user-owned, even when it differs only by whitespace.
            director(db, customProject, custom, "Synthetic custom director", previous + " ", 4);
            execute(db, """
                    update prompt_definition set name='Synthetic renamed global director',
                        description='Synthetic retained global description',content=?,version=11,
                        updated_at='2000-01-01T00:00:00Z' where key='agent.director'
                    """, previous);
            frozenHistory(db, owner, firstProject, first, previous);
            execute(db, "insert into storyboard(project_id,current_version) values (?,1)", firstProject);
            execute(db, """
                    insert into storyboard_revision(project_id,version,content_json,created_at)
                    values (?,1,'{"schemaVersion":1,"shots":[],"compositionNeedsReview":false}',now())
                    """, firstProject);
            customBefore = scalar(db, "select to_jsonb(a)::text from agent_instance a where id=?", custom);
            functionsBefore = snapshot(db, "prompt_definition", "kind='FUNCTION'");
            for (String table : List.of("agent_run", "agent_conversation", "task", "agent_media_approval", "storyboard", "storyboard_revision"))
                history.put(table, snapshot(db, table, "true"));
        }

        assertThat(migration("37").migrate().migrationsExecuted).isEqualTo(1);

        try (Connection db = connection()) {
            String current = scalar(db, "select content from prompt_definition where key='agent.director'");
            assertThat(current).contains("read_storyboard", "write_storyboard_shots", "propose_media_generation", "每批最多六镜").isNotEqualTo(previous);
            assertThat(scalar(db, "select name from prompt_definition where key='agent.director'"))
                    .isEqualTo("Synthetic renamed global director");
            assertThat(scalar(db, "select description from prompt_definition where key='agent.director'"))
                    .isEqualTo("Synthetic retained global description");
            assertThat(scalar(db, "select version::text from prompt_definition where key='agent.director'"))
                    .isEqualTo("12");
            assertThat(scalar(db, "select (updated_at>'2000-01-01T00:00:00Z')::text from prompt_definition where key='agent.director'"))
                    .isEqualTo("true");
            assertDirector(db, firstProject, first, "Synthetic original director", 1, 8, current);
            assertDirector(db, renamedProject, renamed, "Synthetic renamed director", 9, 14, current);
            assertThat(scalar(db, "select to_jsonb(a)::text from agent_instance a where id=?", custom))
                    .isEqualTo(customBefore);
            assertThat(scalar(db, "select event_seq::text from project where id=?", customProject)).isEqualTo("21");
            assertThat(scalar(db, "select event_seq::text from project where id=?", emptyProject)).isEqualTo("4");
            assertThat(scalar(db, "select count(*)::text from project_event")).isEqualTo("2");
            assertThat(snapshot(db, "prompt_definition", "kind='FUNCTION'")).isEqualTo(functionsBefore);
            for (var entry : history.entrySet()) assertThat(snapshot(db, entry.getKey(), "true"))
                    .as("preserved frozen %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(scalar(db, "select count(*)::text from agent_instance")).isEqualTo("3");
        }
    }

    @Test
    void preservesEditedGlobalBodyWhileIndependentlyUpgradingAnUntouchedProjectDirector() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), agent = UUID.randomUUID();
        String globalBefore;
        try (Connection db = connection()) {
            owner(db, owner);
            project(db, project, owner, 2);
            director(db, project, agent, "Synthetic independent project director", previousDirector(), 3);
            execute(db, """
                    update prompt_definition set name='Synthetic custom global name',description='Synthetic custom purpose',
                        content='Synthetic user-authored director workflow',version=17,
                        updated_at='2000-01-01T00:00:00Z' where key='agent.director'
                    """);
            globalBefore = scalar(db, "select to_jsonb(p)::text from prompt_definition p where key='agent.director'");
        }
        migration("37").migrate();
        try (Connection db = connection()) {
            assertThat(scalar(db, "select to_jsonb(p)::text from prompt_definition p where key='agent.director'"))
                    .isEqualTo(globalBefore);
            String current = scalar(db, "select instruction from agent_instance where id=?", agent);
            assertThat(current).contains("read_storyboard", "write_storyboard_shots", "propose_media_generation", "每批最多六镜");
            assertDirector(db, project, agent, "Synthetic independent project director", 4, 3, current);
        }
    }

    @Test
    void anEventWriteFailureRollsBackPromptInstanceAndProjectSequenceTogether() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), agent = UUID.randomUUID();
        Map<String, String> before = new LinkedHashMap<>();
        try (Connection db = connection()) {
            owner(db, owner);
            project(db, project, owner, 5);
            director(db, project, agent, "Synthetic rollback director", previousDirector(), 2);
            execute(db, """
                    create function reject_storyboard_event() returns trigger language plpgsql as $$
                    begin raise exception 'Synthetic event write failure'; end;
                    $$
                    """);
            execute(db, "create trigger reject_storyboard_event before insert on project_event for each row execute function reject_storyboard_event()");
            for (String table : List.of("prompt_definition", "agent_instance", "project", "project_event"))
                before.put(table, snapshot(db, table, "true"));
        }
        assertThatThrownBy(() -> migration("37").migrate()).hasStackTraceContaining("Synthetic event write failure");
        try (Connection db = connection()) {
            for (var entry : before.entrySet()) assertThat(snapshot(db, entry.getKey(), "true"))
                    .as("transactionally retained %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(scalar(db, "select version from flyway_schema_history order by installed_rank desc limit 1"))
                    .isEqualTo("36");
        }
    }

    @Test
    void freshInstallHasTheWorkflowAndRepeatedStartupDoesNotRewriteIt() throws Exception {
        migration("37").clean();
        migration("37").migrate();
        String before;
        try (Connection db = connection()) {
            before = snapshot(db, "prompt_definition", "true");
            assertThat(scalar(db, "select content from prompt_definition where key='agent.director'"))
                    .contains("read_storyboard", "write_storyboard_shots", "propose_media_generation", "每批最多六镜");
            assertThat(scalar(db, "select count(*)::text from agent_instance")).isEqualTo("0");
            assertThat(scalar(db, "select count(*)::text from project_event")).isEqualTo("0");
        }
        assertThat(migration("37").migrate().migrationsExecuted).isZero();
        try (Connection db = connection()) {
            // The guarded SQL itself is safe to replay, independently of Flyway's applied-version check.
            execute(db, resource("db/migration/V37__director_storyboard_workbench_tools.sql"));
            assertThat(snapshot(db, "prompt_definition", "true")).isEqualTo(before);
            assertThat(scalar(db, "select count(*)::text from project_event")).isEqualTo("0");
        }
    }

    @Test
    void replayDoesNotIncrementAnAlreadyUpgradedDirectorOrEventSequence() throws Exception {
        UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), agent = UUID.randomUUID();
        try (Connection db = connection()) {
            owner(db, owner);
            project(db, project, owner, 11);
            director(db, project, agent, "Synthetic replay director", previousDirector(), 4);
        }
        migration("37").migrate();
        Map<String, String> before = new LinkedHashMap<>();
        try (Connection db = connection()) {
            for (String table : List.of("prompt_definition", "agent_instance", "project", "project_event"))
                before.put(table, snapshot(db, table, "true"));
            execute(db, resource("db/migration/V37__director_storyboard_workbench_tools.sql"));
            for (var entry : before.entrySet()) assertThat(snapshot(db, entry.getKey(), "true"))
                    .as("idempotently retained %s", entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private void assertDirector(Connection db, UUID project, UUID agent, String name, int version, int sequence,
            String instruction) throws SQLException {
        assertThat(scalar(db, "select instruction from agent_instance where id=?", agent)).isEqualTo(instruction);
        assertThat(scalar(db, "select name from agent_instance where id=?", agent)).isEqualTo(name);
        assertThat(scalar(db, "select version::text from agent_instance where id=?", agent)).isEqualTo(Integer.toString(version));
        assertThat(scalar(db, "select (updated_at>'2000-01-01T00:00:00Z')::text from agent_instance where id=?", agent)).isEqualTo("true");
        assertThat(scalar(db, "select event_seq::text from project where id=?", project)).isEqualTo(Integer.toString(sequence));
        assertThat(scalar(db, """
                select count(*)::text from project_event where project_id=? and seq=? and type='agent.instance.changed'
                    and schema_version=1 and aggregate_id=? and aggregate_version=?
                    and payload_json=jsonb_build_object('agentId',?::uuid)
                """, project, sequence, agent, version, agent)).isEqualTo("1");
    }

    private void owner(Connection db, UUID owner) throws SQLException {
        execute(db, "insert into app_user(id,login_name,password_hash,status,created_at) values (?,?,'synthetic-hash','ACTIVE',now())",
                owner, "storyboard-" + owner);
    }

    private void project(Connection db, UUID project, UUID owner, int sequence) throws SQLException {
        execute(db, """
                insert into project(id,owner_id,name,aspect_ratio,status,event_seq,created_at,updated_at)
                values (?,?,'Synthetic storyboard project','LANDSCAPE_16_9','ACTIVE',?,now(),now())
                """, project, owner, sequence);
    }

    private void director(Connection db, UUID project, UUID agent, String name, String instruction, int version) throws SQLException {
        execute(db, """
                insert into agent_instance(id,project_id,profile_key,profile_version,name,instruction,version,created_at,updated_at)
                values (?,?,'creator',1,?,?,?,'2000-01-01T00:00:00Z','2000-01-01T00:00:00Z')
                """, agent, project, name, instruction, version);
    }

    private void frozenHistory(Connection db, UUID owner, UUID project, UUID agent, String instruction) throws SQLException {
        UUID conversation = UUID.randomUUID(), run = UUID.randomUUID(), task = UUID.randomUUID();
        execute(db, "insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at) values (?,?,?,'Synthetic frozen conversation',now(),now())",
                conversation, project, agent);
        execute(db, "update agent_instance set current_conversation_id=? where id=?", conversation, agent);
        execute(db, """
                insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,context_snapshot_json,
                    policy_snapshot_json,profile_version,created_at,updated_at,conversation_id,conversation_turn)
                values (?,?,?,?,'WAITING_TASKS','Synthetic frozen request',jsonb_build_object('agentInstruction',?::text),
                    '{"systemPromptVersion":14,"toolPolicyVersion":3}',1,now(),now(),?,1)
                """, run, project, agent, owner, instruction, conversation);
        execute(db, """
                insert into task(id,project_id,run_id,step_key,kind,status,input_json,input_hash,attempt_no,next_action_at,created_at,updated_at)
                values (?,?,?,'synthetic-frozen-media','IMAGE_GENERATION','UNKNOWN','{"synthetic":true}',repeat('0',64),1,now(),now(),now())
                """, task, project, run);
        execute(db, """
                insert into agent_media_approval(id,owner_id,project_id,run_id,step_index,tool_call_id,operation_id,request_json,target_json,status,created_at,expires_at)
                values (?,?,?,?,0,'synthetic-frozen-approval',?,'{"schemaVersion":1,"outputs":[{"kind":"IMAGE"}]}',
                    '{"schemaVersion":1,"outputs":[{}]}','PENDING',now(),now()+interval '1 day')
                """, UUID.randomUUID(), owner, project, run, UUID.randomUUID());
    }

    private String previousDirector() throws Exception {
        var match = Pattern.compile("\\$storyboard_director\\$(.*?)\\$storyboard_director\\$", Pattern.DOTALL)
                .matcher(resource("db/migration/V34__director_storyboard_workflow.sql"));
        assertThat(match.find()).isTrue();
        return match.group(1);
    }

    private String resource(String path) throws Exception {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Flyway migration(String target) {
        // Pin both endpoints so future migrations cannot silently expand this suite's scope.
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).target(target).load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String snapshot(Connection db, String table, String filter) throws SQLException {
        // SQL fragments here are fixed test constants, never external input.
        return scalar(db, "select coalesce(jsonb_agg(row_json order by row_json::text),'[]'::jsonb)::text from "
                + "(select to_jsonb(t) row_json from " + table + " t where " + filter + ") rows");
    }

    private void execute(Connection db, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, values)) { statement.executeUpdate(); }
    }

    private String scalar(Connection db, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(db, sql, values); var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private PreparedStatement prepare(Connection db, String sql, Object... values) throws SQLException {
        var statement = db.prepareStatement(sql);
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
        return statement;
    }
}

package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Production V44 → V45 upgrade over historical Runs and approved, unresolved media work. */
@Testcontainers
class AgentConversationUpgradePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void preservesIndependentLegacyRunsAndBusinessHistoryWithScopedConversationPointers() throws Exception {
        flyway("44").migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID otherAgent = UUID.randomUUID();
        UUID foreignAgent = UUID.randomUUID();
        UUID emptyAgent = UUID.randomUUID();
        UUID oldRun = UUID.randomUUID();
        UUID recentRun = UUID.randomUUID();
        UUID otherRun = UUID.randomUUID();
        UUID foreignRun = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID artifactVersion = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID approval = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        String originalInstruction = "旧任务背景" + "中文😀".repeat(20);
        Map<String, String> before = new LinkedHashMap<>();
        try (Connection connection = connection()) {
            execute(connection, """
                    insert into app_user(id,login_name,password_hash,status,created_at)
                    values (?,'conversation-upgrade-owner','hash','ACTIVE',now())
                    """, owner);
            insertProject(connection, project, owner);
            insertProject(connection, otherProject, owner);
            insertAgent(connection, project, agent);
            insertAgent(connection, project, otherAgent);
            insertAgent(connection, project, emptyAgent);
            insertAgent(connection, otherProject, foreignAgent);
            insertRun(connection, owner, project, agent, oldRun, "CANCELED", originalInstruction,
                    "2026-09-01T00:00:00Z", true);
            insertRun(connection, owner, project, agent, recentRun, "BLOCKED", "较新的独立任务",
                    "2026-09-02T00:00:00Z", false);
            insertRun(connection, owner, project, otherAgent, otherRun, "SUCCEEDED", "另一张 Agent 卡片",
                    "2026-09-03T00:00:00Z", true);
            insertRun(connection, owner, otherProject, foreignAgent, foreignRun, "FAILED", "另一项目任务",
                    "2026-09-04T00:00:00Z", true);
            execute(connection, "update project set active_run_id=? where id=?", recentRun, project);
            execute(connection, """
                    insert into artifact(id,project_id,kind,title,created_at,updated_at)
                    values (?,?,'TEXT','旧产物',now(),now())
                    """, artifact, project);
            execute(connection, """
                    insert into artifact_version(id,project_id,artifact_id,version_no,schema_version,
                      content_json,input_refs_json,created_by_kind,run_id,created_at)
                    values (?,?,?,1,1,'{"text":"不可变旧内容"}','[]','AGENT',?,now())
                    """, artifactVersion, project, artifact, oldRun);
            execute(connection, "update artifact set current_version_id=? where id=?", artifactVersion, artifact);
            execute(connection, """
                    insert into execution_plan(id,project_id,run_id,revision,stage,status,objective,
                      plan_json,input_snapshot_json,input_snapshot_hash,plan_hash,
                      provider_config_version,workflow_version,estimate_json,created_at,updated_at)
                    values (?,?,?,1,'IMAGE','APPROVED','旧审批计划','{"legacy":true}','{}',?,?,
                      1,'mock-image-v1','{"costStatus":"UNKNOWN"}',now(),now())
                    """, plan, project, recentRun, "a".repeat(64), "b".repeat(64));
            execute(connection, """
                    insert into plan_approval(id,plan_id,project_id,run_id,approved_by_user_id,
                      approved_plan_hash,approved_input_hash,reservation_json,created_at)
                    values (?,?,?,?,?,?,?,'{"images":1}',now())
                    """, approval, plan, project, recentRun, owner, "b".repeat(64), "a".repeat(64));
            execute(connection, """
                    insert into task(id,project_id,run_id,plan_id,step_key,kind,status,input_json,
                      input_hash,provider_request_id,attempt_no,next_action_at,created_at,updated_at)
                    values (?,?,?,?,'legacy-image','IMAGE_GENERATION','UNKNOWN',
                      '{"fixedInput":"original"}',?,'original-provider-request',1,now(),now(),now())
                    """, task, project, recentRun, plan, "c".repeat(64));
            for (String table : new String[] {"project", "artifact", "artifact_version", "task",
                    "execution_plan", "plan_approval"}) {
                before.put(table, rows(connection, table, false));
            }
            before.put("agent_run", rows(connection, "agent_run", false));
        }

        Flyway upgrade = flyway("45");
        upgrade.migrate();
        upgrade.migrate();

        try (Connection connection = connection()) {
            for (var saved : before.entrySet()) {
                assertThat(rows(connection, saved.getKey(), saved.getKey().equals("agent_run")))
                        .as("legacy %s rows stay unchanged", saved.getKey()).isEqualTo(saved.getValue());
            }
            assertThat(text(connection, "select count(*)::text from agent_conversation")).isEqualTo("4");
            assertThat(text(connection, """
                    select count(*)::text from agent_run r join agent_conversation c on c.id=r.id
                    where r.conversation_id=r.id and r.conversation_turn=1 and c.turn_count=1
                      and c.version=1 and c.project_id=r.project_id and c.agent_instance_id=r.agent_instance_id
                      and c.created_at=r.created_at and c.updated_at=r.updated_at
                    """)).isEqualTo("4");
            assertThat(text(connection, "select title from agent_conversation where id=?", oldRun))
                    .isEqualTo(originalInstruction.substring(0, originalInstruction.offsetByCodePoints(0, 40)));
            assertThat(text(connection, "select current_conversation_id::text from agent_instance where id=?", agent))
                    .isEqualTo(recentRun.toString());
            assertThat(text(connection, "select current_conversation_id::text from agent_instance where id=?", otherAgent))
                    .isEqualTo(otherRun.toString());
            assertThat(text(connection, "select current_conversation_id::text from agent_instance where id=?", foreignAgent))
                    .isEqualTo(foreignRun.toString());
            assertThat(text(connection, "select current_conversation_id::text from agent_instance where id=?", emptyAgent))
                    .isNull();

            assertForeignKeyRejected(connection,
                    "update agent_run set conversation_id=?, conversation_turn=2 where id=?", otherRun, oldRun);
            assertForeignKeyRejected(connection,
                    "update agent_run set conversation_id=?, conversation_turn=2 where id=?", foreignRun, oldRun);
            assertForeignKeyRejected(connection,
                    "update agent_instance set current_conversation_id=? where id=?", otherRun, agent);
            assertForeignKeyRejected(connection,
                    "update agent_instance set current_conversation_id=? where id=?", foreignRun, agent);
            assertForeignKeyRejected(connection, """
                    insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at)
                    values (?,?,?,'wrong project',now(),now())
                    """, UUID.randomUUID(), otherProject, agent);
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertProject(Connection connection, UUID project, UUID owner) throws SQLException {
        execute(connection, """
                insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at)
                values (?,?,'Upgrade','LANDSCAPE_16_9','ACTIVE',now(),now())
                """, project, owner);
    }

    private void insertAgent(Connection connection, UUID project, UUID agent) throws SQLException {
        execute(connection, """
                insert into agent_instance(id,project_id,profile_key,profile_version,name,instruction,
                  output_group_id,created_at,updated_at)
                values (?,?,'creator',1,'Creator','创作',?,now(),now())
                """, agent, project, UUID.randomUUID());
    }

    private void insertRun(Connection connection, UUID owner, UUID project, UUID agent, UUID run,
            String status, String instruction, String timestamp, boolean terminal) throws SQLException {
        execute(connection, """
                insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,
                  context_snapshot_json,policy_snapshot_json,profile_version,created_at,updated_at,completed_at)
                values (?,?,?,?,?,?,'{"legacyContext":"preserved"}','{"systemPromptVersion":2}',1,
                  cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                """, run, project, agent, owner, status, instruction, timestamp, timestamp, terminal ? timestamp : null);
    }

    private String rows(Connection connection, String table, boolean excludeConversation) throws SQLException {
        // Table names are fixed test-owned identifiers, never request input.
        String projection = excludeConversation
                ? "to_jsonb(t)-'conversation_id'-'conversation_turn'" : "to_jsonb(t)";
        return text(connection, "select jsonb_agg(" + projection + " order by t.id)::text from " + table + " t");
    }

    private void assertForeignKeyRejected(Connection connection, String sql, Object... parameters) {
        assertThatThrownBy(() -> execute(connection, sql, parameters)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23503"));
    }

    private void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) { statement.executeUpdate(); }
    }

    private String text(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
        return statement;
    }
}

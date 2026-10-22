package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises the destructive one-time reset against a disposable, real PostgreSQL database. */
@Testcontainers
class DirectorHistoryResetPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @BeforeEach
    void previousVersion() {
        migration(null).clean();
        migration("26").migrate();
    }

    @Test
    void freshInstallAndRepeatedStartupHaveNoHistoryToReset() throws Exception {
        migration(null).clean();
        migration(null).migrate();
        assertThat(migration(null).migrate().migrationsExecuted).isZero();
        try (Connection db = connection()) {
            assertThat(scalar(db, "select count(*)::text from agent_instance")).isEqualTo("0");
            assertThat(scalar(db, "select count(*)::text from project_event")).isEqualTo("0");
            assertThat(scalar(db, "select count(*)::text from media_capability")).isNotEqualTo("0");
        }
    }

    @Test
    void deletesLegacyExecutionAndReceiptsButPreservesContentDirectTasksSkillsAndBilling() throws Exception {
        Fixture f = new Fixture();
        Map<String, String> preserved = new LinkedHashMap<>();
        String directRows;
        try (Connection db = connection()) {
            seedHistory(db, f, "SUCCEEDED", "CANCELED");
            UUID emptyProject = UUID.randomUUID();
            project(db, emptyProject, f.owner());
            execute(db, "update project set active_run_id=?,event_seq=8 where id=?", f.run(), f.project());
            execute(db, "update project set event_seq=4 where id=?", emptyProject);
            UUID secondCard = UUID.randomUUID();
            execute(db, """
                    insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,selected_version_id,x,y,width,height,z_index,created_at,updated_at)
                    values (?,?,'ARTIFACT',?,?,'Synthetic retained second card',?,300,0,280,240,0,now(),now())
                    """, secondCard, f.project(), f.artifact(), f.artifact(), f.version());
            execute(db, """
                    insert into canvas_connection(id,project_id,source_canvas_item_id,target_canvas_item_id,relation_type,source_artifact_version_id,created_at,updated_at)
                    values (?,?,?,?,'MEDIA_DERIVATION',?,now(),now())
                    """, UUID.randomUUID(), f.project(), f.card(), secondCard, f.version());
            for (String type : List.of("agent.turn.stream.delta", "llm.turn.recorded", "task.status.changed"))
                event(db, f.project(), type.equals("agent.turn.stream.delta") ? 1 : type.equals("llm.turn.recorded") ? 2 : 3,
                        type, type.equals("task.status.changed") ? f.task() : f.run(), "{\"text\":\"Synthetic old conversation\"}");
            event(db, f.project(), 8, "artifact.changed", f.artifact(), "{\"version\":1}");
            event(db, emptyProject, 4, "task.status.changed", f.directTask(), "{}");
            UUID skill = UUID.randomUUID(), skillVersion = UUID.randomUUID();
            execute(db, "insert into creative_skill(id,owner_id,title,created_at,updated_at) values (?,?,'Synthetic retained Skill',now(),now())", skill, f.owner());
            execute(db, """
                    insert into skill_version(id,owner_id,skill_id,version_number,bundle_hash,bundle_json,created_at)
                    values (?,?,?,1,repeat('a',64),'{"schemaVersion":1,"skillMd":"Synthetic retained instructions"}',now())
                    """, skillVersion, f.owner(), skill);
            execute(db, "insert into agent_skill_binding(agent_id,project_id,owner_id,skill_id,skill_version_id,updated_at) values (?,?,?,?,?,now())",
                    f.agent(), f.project(), f.owner(), skill, skillVersion);
            execute(db, """
                    insert into skill_binding_command(owner_id,project_id,agent_id,command_key,payload_hash,response_json,created_at)
                    values (?,?,?,'synthetic-binding',repeat('a',64),'{}',now())
                    """, f.owner(), f.project(), f.agent());
            for (UUID task : List.of(f.task(), f.directTask())) {
                execute(db, """
                        insert into provider_attempt(id,project_id,task_id,lease_epoch,status,request_key,created_at,updated_at)
                        values (?,?,?,1,'ACCEPTED',?,now(),now())
                        """, UUID.randomUUID(), f.project(), task, UUID.randomUUID());
                execute(db, """
                        insert into task_artifact_target(task_id,project_id,artifact_id,expected_artifact_version,canvas_item_id)
                        values (?,?,?,0,?)
                        """, task, f.project(), f.artifact(), f.card());
                execute(db, "insert into task_late_result(id,project_id,task_id,lease_epoch,output_json,created_at) values (?,?,?,1,'{}',now())",
                        UUID.randomUUID(), f.project(), task);
                execute(db, "insert into task_provider_poll_retry(task_id,failure_count,last_error_code,updated_at) values (?,1,'SYNTHETIC_POLL_FAILURE',now())", task);
            }
            // Both replacement directions must drop only the relation, not a direct task.
            replacement(db, f, f.task(), f.directTask(), "synthetic-forward");
            replacement(db, f, f.otherDirectTask(), f.task(), "synthetic-reverse");
            for (UUID task : List.of(f.task(), f.directTask())) {
                UUID call = UUID.randomUUID();
                execute(db, """
                        insert into call_log(id,project_id,task_id,run_id,kind,operation,status,trace_id,started_at,responded_at,duration_ms,mock)
                        values (?,?,?,?,'IMAGE','SUBMIT','SUCCEEDED',?,now(),now(),0,true)
                        """, call, f.project(), task, task.equals(f.task()) ? f.run() : null,
                        call.toString().replace("-", ""));
                execute(db, "insert into call_log_debug(call_id,schema_version,exchanges_json,llm_stream_content_json) values (?,1,'[]','{\"response\":\"Synthetic debug\"}')", call);
                execute(db, """
                        insert into usage_ledger(id,project_id,task_id,run_id,operation_key,entry_type,quantity_json,cost_status,cost_source,created_at)
                        values (?,?,?,?,?,'SETTLEMENT','{}','UNKNOWN','SYNTHETIC',now())
                        """, UUID.randomUUID(), f.project(), task, task.equals(f.task()) ? f.run() : null, "synthetic:" + task);
            }
            // Audit records deliberately outlive ordinary execution retention.
            UUID oldCall = UUID.randomUUID();
            execute(db, """
                    insert into call_log(id,project_id,run_id,step_index,kind,operation,status,trace_id,started_at,responded_at,duration_ms,mock)
                    values (?,?,?,0,'LLM','CHAT','SUCCEEDED',?,now(),now(),0,true)
                    """, oldCall, f.project(), UUID.randomUUID(), oldCall.toString().replace("-", ""));
            receipt(db, f, "project:" + f.project() + ":create-agent", "old-agent", f.agent(), "{}");
            receipt(db, f, "project:" + f.project() + ":create-run", "old-run", f.run(), "{}");
            receipt(db, f, "agent:" + f.agent() + ":create-conversation", "old-conversation", f.conversation(), "{}");
            receipt(db, f, "synthetic-resource", "old-resource", f.task(), "{}");
            receipt(db, f, "synthetic-nested", "old-response", UUID.randomUUID(), "{\"nested\":[{\"runId\":\"" + f.run() + "\"}]}");
            receipt(db, f, "project:" + f.project() + ":create-artifact", "keep-artifact", f.artifact(), "{\"text\":\"Mentioning " + f.run() + " inside text is not an execution reference\"}");
            receipt(db, f, "project:" + f.project() + ":create-artifact", "keep-exact-text", f.artifact(), "{\"text\":\"" + f.run() + "\"}");
            receipt(db, f, "synthetic-direct", "keep-direct", f.directTask(), "{\"taskId\":\"" + f.directTask() + "\"}");
            execute(db, """
                    insert into idempotency_record(principal_id,scope,idempotency_key,request_hash,state,expires_at,created_at,updated_at)
                    values (?,?,'old-in-progress',repeat('b',64),'IN_PROGRESS',now()+interval '1 day',now(),now())
                    """, f.owner(), "project:" + f.project() + ":create-agent");
            for (String table : List.of("artifact", "artifact_version", "asset", "canvas_item", "canvas_connection", "media_draft", "canvas_item_media_version",
                    "creative_skill", "skill_version", "usage_ledger")) preserved.put(table, snapshot(db, table, "true"));
            for (String table : List.of("provider_attempt", "task_artifact_target", "task_late_result", "task_provider_poll_retry"))
                preserved.put(table, snapshot(db, table, "task_id='" + f.directTask() + "'"));
            preserved.put("call_log", snapshot(db, "call_log", "run_id is null"));
            preserved.put("call_log_debug", snapshot(db, "call_log_debug", "call_id in (select id from call_log where run_id is null)"));
            directRows = snapshot(db, "task", "run_id is null");
        }
        assertThat(migration(null).migrate().migrationsExecuted).isEqualTo(1);
        try (Connection db = connection()) {
            for (String table : List.of("agent_instance", "agent_conversation", "agent_run", "tool_execution", "llm_turn",
                    "agent_media_approval", "agent_skill_binding", "skill_binding_command", "task_manual_replacement"))
                assertThat(scalar(db, "select count(*)::text from " + table)).as("removed %s", table).isEqualTo("0");
            for (var entry : preserved.entrySet()) assertThat(snapshot(db, entry.getKey(), "true"))
                    .as("preserved %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(snapshot(db, "task", "true")).isEqualTo(directRows);
            assertThat(scalar(db, "select run_id::text from artifact_version where id=?", f.version())).isEqualTo(f.run().toString());
            assertThat(scalar(db, "select active_run_id::text from project where id=?", f.project())).isNull();
            assertThat(scalar(db, "select event_seq::text from project where id=?", f.project())).isEqualTo("9");
            assertThat(scalar(db, "select count(*)::text from project_event")).isEqualTo("3");
            assertThat(scalar(db, "select payload_json::text from project_event where project_id=? and seq=9", f.project())).isEqualTo("{\"reset\": true}");
            assertThat(scalar(db, "select string_agg(idempotency_key,',' order by idempotency_key) from idempotency_record"))
                    .isEqualTo("keep-artifact,keep-direct,keep-exact-text");
        }
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,READY", "SUCCEEDED,RUNNING", "SUCCEEDED,SUBMITTING", "SUCCEEDED,WAITING_PROVIDER",
            "SUCCEEDED,UNKNOWN", "SUCCEEDED,BLOCKED", "QUEUED,SUCCEEDED", "RUNNING,SUCCEEDED",
            "WAITING_TASKS,SUCCEEDED", "BLOCKED,SUCCEEDED", "CANCEL_REQUESTED,SUCCEEDED"})
    void refusesUnsafeResetAtomically(String runStatus, String taskStatus) throws Exception {
        Fixture f = new Fixture();
        Map<String, String> before = new LinkedHashMap<>();
        try (Connection db = connection()) {
            seedHistory(db, f, runStatus, taskStatus);
            for (String table : List.of("project", "agent_instance", "agent_conversation", "agent_run", "task", "artifact_version", "llm_turn", "tool_execution", "agent_media_approval"))
                before.put(table, snapshot(db, table, "true"));
        }
        assertThatThrownBy(() -> migration(null).migrate()).hasStackTraceContaining("resolve active or UNKNOWN work before upgrading");
        try (Connection db = connection()) {
            for (var entry : before.entrySet()) assertThat(snapshot(db, entry.getKey(), "true"))
                    .as("rolled back %s", entry.getKey()).isEqualTo(entry.getValue());
            assertThat(scalar(db, "select version from flyway_schema_history order by installed_rank desc limit 1")).isEqualTo("26");
        }
    }

    private void seedHistory(Connection db, Fixture f, String runStatus, String taskStatus) throws SQLException {
        execute(db, "insert into app_user(id,login_name,password_hash,status,created_at) values (?,'synthetic-reset-owner','synthetic-hash','ACTIVE',now())", f.owner());
        project(db, f.project(), f.owner());
        execute(db, """
                insert into agent_instance(id,project_id,profile_key,profile_version,name,instruction,created_at,updated_at)
                values (?,?,'creator',1,'Synthetic old director','Synthetic old instructions',now(),now())
                """, f.agent(), f.project());
        execute(db, "insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at) values (?,?,?,'Synthetic old conversation',now(),now())", f.conversation(), f.project(), f.agent());
        execute(db, "update agent_instance set current_conversation_id=? where id=?", f.conversation(), f.agent());
        execute(db, """
                insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,context_snapshot_json,
                    policy_snapshot_json,profile_version,created_at,updated_at,completed_at,conversation_id,conversation_turn)
                values (?,?,?,?,?,'Synthetic old message','{}','{}',1,now(),now(),
                    case when ? in ('SUCCEEDED','FAILED','CANCELED') then now() else null end,?,1)
                """, f.run(), f.project(), f.agent(), f.owner(), runStatus, runStatus, f.conversation());
        execute(db, "insert into artifact(id,project_id,kind,title,created_at,updated_at) values (?,?,'IMAGE','Synthetic retained artifact',now(),now())", f.artifact(), f.project());
        UUID asset = UUID.randomUUID();
        execute(db, """
                insert into asset(id,project_id,media_kind,object_key,content_type,byte_size,sha256,width,height,created_at)
                values (?,?,'IMAGE',?,'image/png',64,repeat('0',64),1,1,now())
                """, asset, f.project(), "synthetic/" + asset + ".png");
        execute(db, """
                insert into artifact_version(id,project_id,artifact_id,version_no,schema_version,content_json,input_refs_json,created_by_kind,run_id,created_at)
                values (?,?,?,1,1,'{"prompt":"Synthetic retained result"}','[]','TASK',?,now())
                """, f.version(), f.project(), f.artifact(), f.run());
        execute(db, """
                insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,selected_version_id,x,y,width,height,z_index,created_at,updated_at)
                values (?,?,'ARTIFACT',?,?,'Synthetic retained card',?,0,0,280,240,0,now(),now())
                """, f.card(), f.project(), f.artifact(), f.artifact(), f.version());
        execute(db, "insert into canvas_item_media_version(project_id,canvas_item_id,artifact_version_id,created_at) values (?,?,?,now())", f.project(), f.card(), f.version());
        execute(db, "insert into media_draft(project_id,canvas_item_id,prompt,created_at,updated_at) values (?,?,'Synthetic retained draft',now(),now())", f.project(), f.card());
        for (UUID task : List.of(f.task(), f.directTask(), f.otherDirectTask())) {
            String status = task.equals(f.task()) ? taskStatus : "UNKNOWN";
            execute(db, """
                    insert into task(id,project_id,run_id,step_key,kind,status,input_json,input_hash,attempt_no,next_action_at,created_at,updated_at,completed_at)
                    values (?,?,?,?,'IMAGE_GENERATION',?,'{}',repeat('0',64),1,now(),now(),now(),
                        case when ? in ('SUCCEEDED','FAILED','CANCELED') then now() else null end)
                    """, task, f.project(), task.equals(f.task()) ? f.run() : null, "synthetic:" + task, status, status);
        }
        execute(db, """
                insert into llm_turn(project_id,run_id,step_index,status,model_config_version,request_json,response_json,created_at,responded_at)
                values (?,?,0,'RESPONDED',1,'{"message":"Synthetic request"}','{"text":"Synthetic response"}',now(),now())
                """, f.project(), f.run());
        execute(db, """
                insert into tool_execution(id,project_id,run_id,step_index,tool_call_id,tool_name,argument_hash,status,result_json,created_at,completed_at)
                values (?,?,?,0,'synthetic-tool-call','read_artifacts',repeat('0',64),'COMPLETED','{}',now(),now())
                """, UUID.randomUUID(), f.project(), f.run());
        execute(db, """
                insert into agent_media_approval(id,owner_id,project_id,run_id,step_index,tool_call_id,operation_id,request_json,target_json,status,created_at,expires_at)
                values (?,?,?,?,0,'synthetic-approval',?,'{"schemaVersion":1,"outputs":[{"kind":"IMAGE"}]}',
                    '{"schemaVersion":1,"outputs":[{}]}','CANCELED',now(),now()+interval '1 day')
                """, UUID.randomUUID(), f.owner(), f.project(), f.run(), UUID.randomUUID());
    }

    private void project(Connection db, UUID id, UUID owner) throws SQLException {
        execute(db, "insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Synthetic project','LANDSCAPE_16_9','ACTIVE',now(),now())", id, owner);
    }

    private void event(Connection db, UUID project, int seq, String type, UUID aggregate, String payload) throws SQLException {
        execute(db, """
                insert into project_event(project_id,seq,event_id,type,schema_version,aggregate_id,aggregate_version,payload_json,occurred_at)
                values (?,?,?,?,1,?,0,?::jsonb,now())
                """, project, seq, UUID.randomUUID(), type, aggregate, payload);
    }

    private void replacement(Connection db, Fixture f, UUID original, UUID replacement, String key) throws SQLException {
        execute(db, """
                insert into task_manual_replacement(original_task_id,project_id,replacement_task_id,approved_by_user_id,
                    original_task_version,idempotency_key,confirmation_code,created_at)
                values (?,?,?,?,0,?,'ACCEPT_POSSIBLE_DUPLICATE_COST',now())
                """, original, f.project(), replacement, f.owner(), key);
    }

    private void receipt(Connection db, Fixture f, String scope, String key, UUID resource, String response) throws SQLException {
        execute(db, """
                insert into idempotency_record(principal_id,scope,idempotency_key,request_hash,state,resource_id,response_json,expires_at,created_at,updated_at)
                values (?,?,?,repeat('b',64),'COMPLETED',?,?::jsonb,now()+interval '1 day',now(),now())
                """, f.owner(), scope, key, resource, response);
    }

    private Flyway migration(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        // This suite owns the one-time V27 reset, independently of later changes.
        config.target(target == null ? "27" : target);
        return config.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String snapshot(Connection db, String table, String filter) throws SQLException {
        // All dynamic SQL fragments are fixed test constants or generated synthetic UUIDs.
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

    private record Fixture(UUID owner, UUID project, UUID agent, UUID conversation, UUID run, UUID task,
                           UUID directTask, UUID otherDirectTask, UUID artifact, UUID version, UUID card) {
        Fixture() { this(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()); }
    }
}

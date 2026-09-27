package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V52 deliberately resets local creative data while retaining installation configuration. */
@Testcontainers
class CanvasMediaContextUpgradePostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @Test
    void clearsProjectDataButRetainsIdentityAndProviderSettings() throws Exception {
        flyway("51").migrate();
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID llmConfigId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("insert into app_user(id,login_name,password_hash,status,created_at)"
                    + " values ('" + ownerId + "','reset-admin','hash','ACTIVE',now())");
            statement.execute("insert into llm_provider_config(id,version,endpoint,model_id,"
                    + "credential_ciphertext,credential_nonce,key_version,key_mask,active,created_at)"
                    + " values ('" + llmConfigId + "',1,'https://example.test/v1','model',"
                    + "decode('01','hex'),decode('000000000000000000000000','hex'),7,'***key',true,now())");
            statement.execute("update llm_provider_config_counter set current_version=1 where id=1");
            statement.execute("update media_provider_connection set name='Preserved connection' "
                    + "where id=(select id from media_provider_connection order by id limit 1)");
            statement.execute("update media_capability set name='Preserved capability' "
                    + "where id=(select id from media_capability order by id limit 1)");
            statement.execute("update media_provider_connection_version "
                    + "set credential_ciphertext=decode('01','hex'),"
                    + "credential_nonce=decode('000000000000000000000000','hex'),"
                    + "credential_key_version=9 where (connection_id,version)=(select "
                    + "connection_id,version from media_provider_connection_version "
                    + "order by connection_id,version limit 1)");
            statement.execute("insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at)"
                    + " values ('" + projectId + "','" + ownerId
                    + "','Disposable','LANDSCAPE_16_9','ACTIVE',now(),now())");
            statement.execute("insert into artifact(id,project_id,kind,title,created_at,updated_at)"
                    + " values ('" + artifactId + "','" + projectId
                    + "','IMAGE','Disposable image',now(),now())");
            statement.execute("insert into asset(id,project_id,media_kind,status,object_key,"
                    + "content_type,byte_size,sha256,width,height,created_at) values ('"
                    + UUID.randomUUID() + "','" + projectId + "','IMAGE','READY','discard/asset',"
                    + "'image/png',1,repeat('0',64),1,1,now())");
            statement.execute("insert into agent_instance(id,project_id,profile_key,profile_version,"
                    + "name,instruction,output_group_id,created_at,updated_at) values ('" + agentId
                    + "','" + projectId + "','creator',1,'Disposable agent','Create','"
                    + UUID.randomUUID() + "',now(),now())");
            statement.execute("insert into agent_conversation(id,project_id,agent_instance_id,title,"
                    + "turn_count,created_at,updated_at) values ('" + conversationId + "','"
                    + projectId + "','" + agentId + "','Disposable conversation',1,now(),now())");
            statement.execute("insert into agent_run(id,project_id,agent_instance_id,user_id,status,"
                    + "instruction,context_snapshot_json,policy_snapshot_json,profile_version,"
                    + "created_at,updated_at,completed_at,conversation_id,conversation_turn) values ('"
                    + runId + "','" + projectId + "','" + agentId + "','" + ownerId
                    + "','SUCCEEDED','Discard','{}','{}',1,now(),now(),now(),'"
                    + conversationId + "',1)");
            statement.execute("insert into task(id,project_id,run_id,step_key,kind,status,input_json,"
                    + "input_hash,attempt_no,next_action_at,created_at,updated_at,completed_at,origin) "
                    + "values ('" + taskId + "','" + projectId + "','" + runId
                    + "','discard-task','AGENT_TURN','SUCCEEDED','{}',repeat('0',64),1,now(),now(),"
                    + "now(),now(),'AGENT')");
            statement.execute("insert into usage_ledger(id,project_id,run_id,task_id,operation_key,"
                    + "entry_type,quantity_json,cost_status,cost_source,created_at) values ('"
                    + UUID.randomUUID() + "','" + projectId + "','" + runId + "','" + taskId
                    + "','discard-usage','SETTLEMENT','{}','UNKNOWN','test',now())");
            statement.execute("insert into project_event(project_id,seq,event_id,type,schema_version,"
                    + "aggregate_id,aggregate_version,payload_json,occurred_at) values ('" + projectId
                    + "',1,'" + UUID.randomUUID() + "','discard.event',1,'" + artifactId
                    + "',0,'{}',now())");
            statement.execute("insert into idempotency_record(principal_id,scope,idempotency_key,"
                    + "request_hash,state,expires_at,created_at,updated_at) values ('" + ownerId
                    + "','project:" + projectId + "','discard-key',repeat('0',64),'IN_PROGRESS',"
                    + "now()+interval '1 day',now(),now())");
            statement.execute("insert into media_draft(project_id,artifact_id,prompt,version,"
                    + "created_at,updated_at,display_mode) values ('" + projectId + "','"
                    + artifactId + "','discard me',0,now(),now(),'DRAFT')");
        }

        Flyway upgrade = flyway(null);
        upgrade.migrate();
        upgrade.migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(count(statement, "select count(*) from project")).isZero();
            assertThat(count(statement, "select count(*) from artifact")).isZero();
            assertThat(count(statement, "select count(*) from media_draft")).isZero();
            assertThat(count(statement, "select count(*) from asset")).isZero();
            assertThat(count(statement, "select count(*) from task")).isZero();
            assertThat(count(statement, "select count(*) from agent_run")).isZero();
            assertThat(count(statement, "select count(*) from project_event")).isZero();
            assertThat(count(statement, "select count(*) from usage_ledger")).isZero();
            assertThat(count(statement, "select count(*) from idempotency_record")).isZero();
            assertThat(count(statement, "select count(*) from app_user where id='" + ownerId + "'"))
                    .isEqualTo(1);
            assertThat(count(statement, "select count(*) from llm_provider_config where id='"
                    + llmConfigId + "' and key_version=7")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from media_provider_connection "
                    + "where name='Preserved connection'"))
                    .isEqualTo(1);
            assertThat(count(statement, "select count(*) from media_capability "
                    + "where name='Preserved capability'")).isEqualTo(1);
            assertThat(count(statement, "select count(*) from media_provider_connection_version "
                    + "where credential_key_version=9 and credential_ciphertext=decode('01','hex')"))
                    .isEqualTo(1);
            assertThat(count(statement, "select count(*) from information_schema.columns where "
                    + "table_name='artifact' and column_name='resource_default_version_id'"))
                    .isEqualTo(1);
            assertThat(count(statement, "select count(*) from information_schema.columns where "
                    + "table_name='canvas_item' and column_name='selected_version_id'"))
                    .isEqualTo(1);
            assertThat(count(statement, "select count(*) from information_schema.columns where "
                    + "table_name='media_draft' and column_name='canvas_item_id'"))
                    .isEqualTo(1);
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}

package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.testing.MigrationVersions;
import dev.agenvas.artifact.infrastructure.JooqArtifactRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Fresh-install evidence for the consolidated schema, installation seeds and database boundaries. */
@Testcontainers
class FlywayBaselinePostgresIT {

    private static final String INITIAL_BASELINE_VERSION = "1";
    private static final String BEFORE_PERMANENT_SETUP_VERSION = "8";
    private static final String INITIAL_BASELINE_SCRIPT = "V1__initial_schema.sql";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String IMMUTABILITY_VIOLATION = "P0001";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    private Flyway flyway;

    @BeforeEach
    void prepareEmptySchema() {
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).load();
        // This database belongs exclusively to the disposable container; V1 targets public.
        flyway.clean();
    }

    @Test
    void initializesEmptyDatabaseAndRepeatedMigrationPreservesBusinessData() throws Exception {
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(MigrationVersions.sorted().size());
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(MigrationVersions.latest());

        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        try (Connection connection = connection()) {
            assertThat(text(connection, "select script from flyway_schema_history where version=?",
                    INITIAL_BASELINE_VERSION)).isEqualTo(INITIAL_BASELINE_SCRIPT);
            insertOwner(connection, owner);
            insertProject(connection, project, owner);
            insertArtifact(connection, project, artifact, "TEXT");
            insertVersion(connection, project, artifact, version);
            execute(connection, "update artifact set resource_default_version_id=? where id=?", version, artifact);
        }

        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = connection()) {
            assertThat(text(connection, "select content_json->>'text' from artifact_version where id=?", version))
                    .isEqualTo("Synthetic baseline content");
            assertThat(text(connection, "select resource_default_version_id::text from artifact where id=?", artifact))
                    .isEqualTo(version.toString());
            assertThat(text(connection, "select name from project where id=?", project))
                    .isEqualTo("Synthetic baseline project");
            assertThat(count(connection, "select count(*) from flyway_schema_history where success and version is not null"))
                    .isEqualTo(MigrationVersions.sorted().size());
        }
    }

    @Test
    void permanentSetupUpgradeRecognizesAnExistingDisabledAdministrator() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(BEFORE_PERMANENT_SETUP_VERSION).load().migrate();
        UUID owner = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertOwner(connection, owner);
            execute(connection, "update app_user set status='DISABLED' where id=?", owner);
        }
        flyway.migrate();
        try (Connection connection = connection()) {
            assertThat(count(connection, "select count(*) from installation_lock where initialized_at is not null"))
                    .isEqualTo(1);
            assertThat(count(connection, "select count(*) from app_user where id=? and status='DISABLED'", owner))
                    .isEqualTo(1);
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void relayUpgradeDefaultsToDisabledAndPreservesTheArchiveConnection() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("3").load().migrate();
        UUID profile = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(connection, """
                    insert into storage_profile(id,name,provider,endpoint,region,bucket,key_prefix,path_style,
                        credential_version,credential_ciphertext,credential_nonce,credential_key_version,access_key_mask,created_at)
                    values (?,'Synthetic archive','S3','https://s3.example.com','us-east-1','synthetic-bucket','archive',true,
                        1,decode('00','hex'),decode(repeat('00',12),'hex'),1,'masked',now())
                    """, profile);
            execute(connection, "update storage_settings set active_profile_id=?,version=7 where singleton", profile);
        }
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(MigrationVersions.sorted().size() - 3);
        try (Connection connection = connection()) {
            assertThat(text(connection, "select active_profile_id::text from storage_settings where singleton")).isEqualTo(profile.toString());
            assertThat(text(connection, "select (relay_profile_id is null)::text from storage_settings where singleton")).isEqualTo("true");
            assertThat(count(connection, "select version from storage_settings where singleton")).isEqualTo(7);
            assertThat(count(connection, "select count(*) from media_relay_object")).isZero();
            assertThat(text(connection, "select name from storage_profile where id=?", profile)).isEqualTo("Synthetic archive");
        }
    }

    @Test
    void streamLogUpgradeRemovesDeliveryFieldsAndPreservesModelResponseAndUsage() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target("2").load().migrate();
        UUID call = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(connection, """
                    insert into call_log(id,project_id,task_id,kind,operation,status,trace_id,mock,
                        started_at,responded_at,duration_ms,llm_stream_metrics_json)
                    values (?,?,?,'LLM','CHAT','SUCCEEDED',?,true,now(),now(),100,
                        '{"schemaVersion":1,"firstTextMs":35,"durationMs":100,"totalTokens":6,
                          "firstOutputMs":60,"outputBatchCount":2,"outputChars":12}'::jsonb)
                    """, call, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString().replace("-", ""));
            execute(connection, """
                    insert into call_log_debug(call_id,exchanges_json,llm_stream_content_json)
                    values (?,'[]'::jsonb,jsonb_build_object('response',?::text,'output','synthetic duplicate output','truncated',false))
                    """, call, "{\"text\":\"synthetic model reply\"}");
        }
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(MigrationVersions.sorted().size() - 2);
        try (Connection connection = connection()) {
            assertThat(count(connection, "select (llm_stream_metrics_json->>'schemaVersion')::int from call_log where id=?", call)).isEqualTo(2);
            assertThat(count(connection, "select (llm_stream_metrics_json->>'firstTextMs')::int from call_log where id=?", call)).isEqualTo(35);
            assertThat(count(connection, "select (llm_stream_metrics_json->>'totalTokens')::int from call_log where id=?", call)).isEqualTo(6);
            assertThat(count(connection, """
                    select count(*) from call_log where id=? and jsonb_exists_any(llm_stream_metrics_json, array['firstOutputMs','outputBatchCount','outputChars'])
                    """, call)).isZero();
            assertThat(text(connection, "select llm_stream_content_json->>'response' from call_log_debug where call_id=?", call))
                    .isEqualTo("{\"text\":\"synthetic model reply\"}");
            assertThat(count(connection, "select count(*) from call_log_debug where call_id=? and jsonb_exists(llm_stream_content_json, 'output')", call)).isZero();
        }
    }

    @Test
    void seedsInstallationSettingsMockMediaAndLocalImageProcessing() throws Exception {
        flyway.migrate();
        try (Connection connection = connection()) {
            for (String table : List.of("installation_lock",
                    "llm_provider_config_counter",
                    "audit_debug_settings", "storage_settings", "audit_log_retention_settings")) {
                // Identifiers are fixed test-owned constants, never request input.
                assertThat(count(connection, "select count(*) from " + table)).as(table).isEqualTo(1);
            }
            assertThat(count(connection, "select current_version from llm_provider_config_counter where id=1"))
                    .isZero();
            assertThat(text(connection, "select debug_mode::text from audit_debug_settings where id=1"))
                    .isEqualTo("false");
            assertThat(text(connection, "select (active_profile_id is null)::text from storage_settings where singleton"))
                    .isEqualTo("true");
            assertThat(text(connection, "select (retention_days is null)::text from audit_log_retention_settings where id=1"))
                    .isEqualTo("true");
            assertThat(strings(connection, """
                    select d.kind || ':' || v.adapter_id from media_default d
                    join media_capability c on c.id=d.capability_id
                    join media_capability_version v on v.capability_id=c.id and v.version=c.current_version
                    join media_provider_connection p on p.id=c.connection_id
                    where p.platform='MOCK' and p.enabled and c.enabled
                    order by d.kind
                    """)).containsExactly("AUDIO_GENERATION:MOCK_AUDIO", "IMAGE_GENERATION:MOCK_IMAGE",
                            "VIDEO_GENERATION:MOCK_VIDEO");
            assertThat(count(connection, """
                    select count(*) from media_provider_connection p
                    join media_provider_connection_version pv on pv.connection_id=p.id and pv.version=p.current_version
                    join media_capability c on c.connection_id=p.id
                    join media_capability_version v on v.capability_id=c.id and v.version=c.current_version
                    where p.platform='LOCAL' and p.enabled and c.enabled and v.adapter_id='LOCAL_IMAGE_PROCESSOR'
                      and pv.origin is null and pv.credential_ciphertext is null
                    """)).isEqualTo(1);
            assertThat(strings(connection, "select builtin_key from media_style where enabled order by builtin_key"))
                    .containsExactly("anime", "cinematic", "clay", "cyberpunk", "ink-wash", "photographic",
                            "three-dimensional", "watercolor");
            assertThat(strings(connection, """
                    select table_name from information_schema.tables where table_schema='public'
                      and table_name in ('execution_plan','plan_step','plan_approval','shot_keyframe_selection',
                        'export_proposal','shot_duration_upgrade','artifact_relation','provider_dispatch_gate',
                        'creative_data_reset_marker','comfyui_config_version','media_legacy_import_marker',
                        'media_legacy_origin_map','task_dependency','recovery_pending_migration_marker')
                    """)).isEmpty();
        }
    }

    @Test
    void documentsEveryDatabaseObjectAndOmitsRetiredColumns() throws Exception {
        flyway.migrate();
        try (Connection connection = connection()) {
            assertThat(strings(connection, """
                    select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
                    where n.nspname='public' and c.relkind in ('r','i')
                      and c.relname <> 'flyway_schema_history'
                      and c.relname not like 'flyway_schema_history_%'
                      and nullif(btrim(obj_description(c.oid,'pg_class')),'') is null
                    """)).as("undocumented tables or indexes").isEmpty();
            assertThat(strings(connection, """
                    select c.relname||'.'||a.attname from pg_attribute a
                    join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace
                    where n.nspname='public' and c.relkind='r' and c.relname <> 'flyway_schema_history'
                      and a.attnum>0 and not a.attisdropped
                      and nullif(btrim(col_description(c.oid,a.attnum)),'') is null
                    """)).as("undocumented columns").isEmpty();
            assertThat(strings(connection, """
                    select c.conname from pg_constraint c join pg_namespace n on n.oid=c.connamespace
                    join pg_class t on t.oid=c.conrelid
                    where n.nspname='public' and t.relname <> 'flyway_schema_history'
                      and nullif(btrim(obj_description(c.oid,'pg_constraint')),'') is null
                    """)).as("undocumented constraints").isEmpty();
            assertThat(strings(connection, """
                    select p.proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace
                    where n.nspname='public' and nullif(btrim(obj_description(p.oid,'pg_proc')),'') is null
                    union all
                    select t.tgname from pg_trigger t join pg_class c on c.oid=t.tgrelid
                    join pg_namespace n on n.oid=c.relnamespace
                    where n.nspname='public' and not t.tgisinternal
                      and nullif(btrim(obj_description(t.oid,'pg_trigger')),'') is null
                    """)).as("undocumented functions or triggers").isEmpty();
            assertThat(strings(connection, """
                    select table_name||'.'||column_name from information_schema.columns
                    where table_schema='public' and (table_name,column_name) in (
                      ('task','provider_id'),('task','origin'),('agent_binding','binding_type'),('asset','status'),
                      ('task_artifact_target','output_slot_key'),('installation_lock','purpose'),
                      ('provider_attempt','candidate_request_id'),('provider_attempt','candidate_origin_sha256'),
                      ('skill_install_operation','command_key'),('skill_install_operation','payload_hash'))
                    """)).isEmpty();
        }
    }

    @Test
    void enforcesImmutableVersionsAndProjectScopedCanvasReferences() throws Exception {
        flyway.migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID foreignProject = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID foreignArtifact = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID foreignVersion = UUID.randomUUID();
        UUID card = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertOwner(connection, owner);
            insertProject(connection, project, owner);
            insertProject(connection, foreignProject, owner);
            insertArtifact(connection, project, artifact, "IMAGE");
            insertArtifact(connection, foreignProject, foreignArtifact, "IMAGE");
            insertVersion(connection, project, artifact, version);
            insertVersion(connection, foreignProject, foreignArtifact, foreignVersion);
            insertCard(connection, project, artifact, card, version);
            execute(connection, """
                    insert into canvas_item_media_version(project_id,canvas_item_id,artifact_version_id,created_at)
                    values (?,?,?,now())
                    """, project, card, version);
            execute(connection, """
                    insert into task(id,project_id,step_key,kind,status,input_json,input_hash,
                      attempt_no,next_action_at,created_at,updated_at)
                    values (?,?,'synthetic-direct-task','IMAGE_GENERATION','READY','{}',repeat('0',64),
                      1,now(),now(),now())
                    """, task, project);

            assertRejected(connection, IMMUTABILITY_VIOLATION,
                    "update artifact_version set content_json='{}' where id=?", version);
            assertRejected(connection, IMMUTABILITY_VIOLATION, "delete from artifact_version where id=?", version);
            assertRejected(connection, FOREIGN_KEY_VIOLATION,
                    "update canvas_item set selected_version_id=? where id=?", foreignVersion, card);
            assertRejected(connection, FOREIGN_KEY_VIOLATION, """
                    insert into canvas_item_media_version(project_id,canvas_item_id,artifact_version_id,created_at)
                    values (?,?,?,now())
                    """, project, card, foreignVersion);
            assertRejected(connection, FOREIGN_KEY_VIOLATION, """
                    insert into artifact_version_reference(source_version_id,project_id,target_version_id,
                      reference_role,reference_order) values (?,?,?,'REFERENCE',0)
                    """, version, project, foreignVersion);
            assertRejected(connection, CHECK_VIOLATION, "update canvas_item set title='   ' where id=?", card);
            for (String removedKind : List.of("CHARACTER", "SCENE", "SHOT")) {
                assertRejected(connection, CHECK_VIOLATION, "update artifact set kind=? where id=?", removedKind, artifact);
            }
            assertRejected(connection, CHECK_VIOLATION, "update task set kind='MEDIA_EXPORT' where id=?", task);
            assertRejected(connection, CHECK_VIOLATION, "update task set kind='AGENT_TURN' where id=?", task);
            // TEXT has no file_id: its owner must still exist even though the file relation is nullable.
            assertRejected(connection, FOREIGN_KEY_VIOLATION, """
                    insert into library_entry(id,owner_id,name,category,kind,text_content,
                      source_json,created_at,updated_at)
                    values (?,?,'Synthetic text','OTHER','TEXT','{"format":"PLAIN_TEXT","text":"Synthetic"}',
                      '{"schemaVersion":1}',now(),now())
                    """, UUID.randomUUID(), UUID.randomUUID());

            execute(connection, "delete from canvas_item where id=?", card);
            assertThat(count(connection, "select count(*) from canvas_item_media_version where canvas_item_id=?", card))
                    .isZero();
            assertThat(count(connection, "select count(*) from artifact_version where id=?", version)).isEqualTo(1);
        }
    }

    @Test
    void conversationAndRunOwnershipStayWithinTheirProjectAndAgent() throws Exception {
        flyway.migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID foreignProject = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID otherAgent = UUID.randomUUID();
        UUID foreignAgent = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID otherConversation = UUID.randomUUID();
        UUID foreignConversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertOwner(connection, owner);
            insertProject(connection, project, owner);
            insertProject(connection, foreignProject, owner);
            insertAgent(connection, project, agent);
            insertAgent(connection, project, otherAgent);
            insertAgent(connection, foreignProject, foreignAgent);
            insertConversation(connection, project, agent, conversation);
            insertConversation(connection, project, otherAgent, otherConversation);
            insertConversation(connection, foreignProject, foreignAgent, foreignConversation);
            execute(connection, """
                    insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,
                      context_snapshot_json,policy_snapshot_json,profile_version,created_at,updated_at,
                      conversation_id,conversation_turn)
                    values (?,?,?,?,'QUEUED','Synthetic instruction','{}','{}',1,now(),now(),?,1)
                    """, run, project, agent, owner, conversation);
            execute(connection, "update agent_instance set current_conversation_id=? where id=?", conversation, agent);

            for (UUID invalidConversation : List.of(otherConversation, foreignConversation)) {
                assertRejected(connection, FOREIGN_KEY_VIOLATION,
                        "update agent_run set conversation_id=? where id=?", invalidConversation, run);
            }
            assertRejected(connection, FOREIGN_KEY_VIOLATION, """
                    insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at)
                    values (?,?,?,'Wrong project',now(),now())
                    """, UUID.randomUUID(), foreignProject, agent);
        }
    }

    @Test
    void auditAndUsageHistoryKeepOriginalIdentifiersAfterExecutionCleanup() throws Exception {
        flyway.migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID log = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertOwner(connection, owner);
            insertProject(connection, project, owner);
            insertAgent(connection, project, agent);
            insertConversation(connection, project, agent, conversation);
            execute(connection, """
                    insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,
                      context_snapshot_json,policy_snapshot_json,profile_version,created_at,updated_at,
                      conversation_id,conversation_turn,completed_at)
                    values (?,?,?,?,'SUCCEEDED','Synthetic history','{}','{}',1,now(),now(),?,1,now())
                    """, run, project, agent, owner, conversation);
            execute(connection, """
                    insert into task(id,project_id,run_id,step_key,kind,status,input_json,input_hash,
                      attempt_no,next_action_at,created_at,updated_at,completed_at)
                    values (?,?,?,'synthetic-history-task','IMAGE_GENERATION','SUCCEEDED','{}',repeat('0',64),
                      1,now(),now(),now(),now())
                    """, task, project, run);
            execute(connection, """
                    insert into call_log(id,project_id,task_id,run_id,kind,operation,status,trace_id,
                      provider_request_id,started_at,responded_at,duration_ms,mock)
                    values (?,?,?,?,'IMAGE','SUBMIT','SUCCEEDED',repeat('0',32),
                      'synthetic-external-request',now(),now(),0,true)
                    """, log, project, task, run);
            execute(connection, """
                    insert into usage_ledger(id,project_id,task_id,run_id,operation_key,entry_type,
                      quantity_json,cost_status,cost_source,created_at)
                    values (?,?,?,?,'synthetic-history-settlement','SETTLEMENT','{}','UNKNOWN','SYNTHETIC',now())
                    """, entry, project, task, run);

            execute(connection, "delete from task where id=?", task);
            execute(connection, "delete from agent_run where id=?", run);
            execute(connection, "delete from agent_conversation where id=?", conversation);
            execute(connection, "delete from agent_instance where id=?", agent);
            execute(connection, "delete from project where id=?", project);

            assertThat(text(connection, "select project_id::text from call_log where id=?", log))
                    .isEqualTo(project.toString());
            assertThat(text(connection, "select task_id::text from call_log where id=?", log))
                    .isEqualTo(task.toString());
            assertThat(text(connection, "select provider_request_id from call_log where id=?", log))
                    .isEqualTo("synthetic-external-request");
            assertThat(text(connection, "select run_id::text from call_log where id=?", log))
                    .isEqualTo(run.toString());
            assertThat(text(connection, "select project_id::text from usage_ledger where id=?", entry))
                    .isEqualTo(project.toString());
            assertThat(text(connection, "select task_id::text from usage_ledger where id=?", entry))
                    .isEqualTo(task.toString());
            assertThat(text(connection, "select run_id::text from usage_ledger where id=?", entry))
                    .isEqualTo(run.toString());
        }
    }

    @Test
    void applicationVersionSelectionRejectsMissingAndUnrelatedInitialVersions() throws Exception {
        flyway.migrate();
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID otherArtifact = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID otherVersion = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertOwner(connection, owner);
            insertProject(connection, project, owner);
            insertArtifact(connection, project, artifact, "TEXT");
            insertArtifact(connection, project, otherArtifact, "TEXT");
            insertVersion(connection, project, artifact, version);
            insertVersion(connection, project, otherArtifact, otherVersion);
            var repository = new JooqArtifactRepository(DSL.using(connection, SQLDialect.POSTGRES), new ObjectMapper());
            for (UUID invalidVersion : List.of(otherVersion, UUID.randomUUID())) {
                assertThatThrownBy(() -> repository.setInitialResourceDefaultVersion(artifact, invalidVersion, Instant.now()))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(text(connection, "select resource_default_version_id::text from artifact where id=?", artifact))
                        .isNull();
            }
            repository.setInitialResourceDefaultVersion(artifact, version, Instant.now());
            assertThat(text(connection, "select resource_default_version_id::text from artifact where id=?", artifact))
                    .isEqualTo(version.toString());
            assertThat(repository.setResourceDefaultVersion(owner, project, artifact, 0, otherVersion,
                    "Synthetic title", Instant.now())).isFalse();
            assertThat(text(connection, "select resource_default_version_id::text from artifact where id=?", artifact))
                    .isEqualTo(version.toString());
        }
    }

    @Test
    void foreignKeyDependenciesHaveNoCyclesOrSelfReferences() throws Exception {
        flyway.migrate();
        try (Connection connection = connection()) {
            assertThat(strings(connection, """
                    with recursive edges(source,target) as (
                      select conrelid,confrelid from pg_constraint
                      where contype='f' and connamespace='public'::regnamespace
                    ), reach(source,target) as (
                      select source,target from edges
                      union
                      select r.source,e.target from reach r join edges e on e.source=r.target
                    )
                    select distinct source::regclass::text from reach where source=target
                    """)).as("cyclic foreign-key dependencies").isEmpty();
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertOwner(Connection connection, UUID owner) throws SQLException {
        execute(connection, """
                insert into app_user(id,login_name,password_hash,status,created_at)
                values (?,'synthetic-baseline-owner','synthetic-hash','ACTIVE',now())
                """, owner);
    }

    private void insertProject(Connection connection, UUID project, UUID owner) throws SQLException {
        execute(connection, """
                insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at)
                values (?,?,'Synthetic baseline project','LANDSCAPE_16_9','ACTIVE',now(),now())
                """, project, owner);
    }

    private void insertArtifact(Connection connection, UUID project, UUID artifact, String kind) throws SQLException {
        execute(connection, """
                insert into artifact(id,project_id,kind,title,created_at,updated_at)
                values (?,?,?,'Synthetic artifact',now(),now())
                """, artifact, project, kind);
    }

    private void insertVersion(Connection connection, UUID project, UUID artifact, UUID version) throws SQLException {
        execute(connection, """
                insert into artifact_version(id,project_id,artifact_id,version_no,schema_version,content_json,
                  input_refs_json,created_by_kind,created_at)
                values (?,?,?,1,1,'{"text":"Synthetic baseline content"}','[]','USER',now())
                """, version, project, artifact);
    }

    private void insertCard(Connection connection, UUID project, UUID artifact, UUID card, UUID version) throws SQLException {
        execute(connection, """
                insert into canvas_item(id,project_id,subject_type,subject_id,artifact_id,title,
                  selected_version_id,x,y,width,height,z_index,created_at,updated_at)
                values (?,?,'ARTIFACT',?,?,'Synthetic card',?,0,0,280,240,0,now(),now())
                """, card, project, artifact, artifact, version);
    }

    private void insertAgent(Connection connection, UUID project, UUID agent) throws SQLException {
        execute(connection, """
                insert into agent_instance(id,project_id,profile_key,profile_version,name,instruction,
                  output_group_id,created_at,updated_at)
                values (?,?,'creator',1,'Synthetic agent','Create',?,now(),now())
                """, agent, project, UUID.randomUUID());
    }

    private void insertConversation(Connection connection, UUID project, UUID agent, UUID conversation) throws SQLException {
        execute(connection, """
                insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at)
                values (?,?,?,'Synthetic conversation',now(),now())
                """, conversation, project, agent);
    }

    private void assertRejected(Connection connection, String sqlState, String sql, Object... parameters) {
        assertThatThrownBy(() -> execute(connection, sql, parameters)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(sqlState));
    }

    private void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
        }
    }

    private long count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private String text(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters); var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private List<String> strings(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql); var rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) values.add(rows.getString(1));
            return values;
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) statement.setObject(index + 1, parameters[index]);
        return statement;
    }
}

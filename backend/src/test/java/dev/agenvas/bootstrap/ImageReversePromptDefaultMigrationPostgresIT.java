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

/** Real PostgreSQL upgrade checks with synthetic frozen tasks; no model calls. */
@Testcontainers
class ImageReversePromptDefaultMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @BeforeEach
    void previousVersion() {
        migration("49").clean();
        migration("48").migrate();
    }

    @Test
    void updatesOnlyTheExactDefaultAndLeavesFrozenTasksAndOtherPromptsIntact() throws Exception {
        String oldBody;
        String frozenBefore;
        String otherPromptsBefore;
        String metadataBefore;
        try (var db = connection()) {
            oldBody = scalar(db, "select content from prompt_definition where key='image.reverse-prompt'");
            UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
            execute(db,
                    "insert into app_user(id,login_name,password_hash,status,created_at) values (?,?,'synthetic-hash','ACTIVE',now())",
                    owner, "image-default-" + owner);
            execute(db,
                    "insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Synthetic image project','LANDSCAPE_16_9','ACTIVE',now(),now())",
                    project, owner);
            execute(db, """
                    insert into task(id,project_id,step_key,kind,status,input_json,input_hash,attempt_no,next_action_at,created_at,updated_at)
                    values (?,?,'frozen-image-prompt','TEXT_GENERATION','READY',
                        jsonb_build_object('schemaVersion',3,'purpose','IMAGE_REVERSE_PROMPT','language','ja',
                            'systemPrompt',?::text,'promptKey','image.reverse-prompt','promptVersion',7),
                        repeat('0',64),1,now(),now(),now())
                    """, UUID.randomUUID(), project, oldBody);
            execute(db,
                    "update prompt_definition set name='Synthetic renamed prompt',description='Synthetic retained description',version=7 where key='image.reverse-prompt'");
            frozenBefore = scalar(db, "select to_jsonb(t)::text from task t");
            metadataBefore = scalar(db,
                    "select (to_jsonb(p)-'content'-'version'-'updated_at')::text from prompt_definition p where key='image.reverse-prompt'");
            otherPromptsBefore = otherPrompts(db);
        }
        assertThat(migration("49").migrate().migrationsExecuted).isEqualTo(1);
        try (var db = connection()) {
            String body = scalar(db, "select content from prompt_definition where key='image.reverse-prompt'");
            assertThat(body).isNotEqualTo(oldBody).contains("spatial relationships", "illegible", "focal length",
                    "negative-prompt", "single paragraph");
            assertThat(body.length()).isLessThanOrEqualTo(8000);
            assertThat(scalar(db, "select version::text from prompt_definition where key='image.reverse-prompt'"))
                    .isEqualTo("8");
            assertThat(scalar(db,
                    "select (to_jsonb(p)-'content'-'version'-'updated_at')::text from prompt_definition p where key='image.reverse-prompt'"))
                    .isEqualTo(metadataBefore);
            assertThat(scalar(db, "select to_jsonb(t)::text from task t")).isEqualTo(frozenBefore);
            assertThat(otherPrompts(db)).isEqualTo(otherPromptsBefore);
        }
        assertThat(migration("49").migrate().migrationsExecuted).isZero();
    }

    @Test
    void preservesCustomizedNearMatchingAndNonBuiltinDefinitionsExactly() throws Exception {
        for (String suffix : List.of(" ", "\nSynthetic administrator instruction")) {
            previousVersion();
            String before;
            try (var db = connection()) {
                execute(db,
                        "update prompt_definition set content=content || ?,version=27 where key='image.reverse-prompt'",
                        suffix);
                before = definition(db);
            }
            migration("49").migrate();
            try (var db = connection()) {
                assertThat(definition(db)).isEqualTo(before);
            }
        }
        previousVersion();
        String before;
        try (var db = connection()) {
            execute(db, "update prompt_definition set built_in=false where key='image.reverse-prompt'");
            before = definition(db);
        }
        migration("49").migrate();
        try (var db = connection()) {
            assertThat(definition(db)).isEqualTo(before);
        }
    }

    @Test
    void freshInstallUsesTheSameImprovedEditableBuiltin() throws Exception {
        migration("49").migrate();
        String upgraded;
        try (var db = connection()) {
            upgraded = scalar(db, "select content from prompt_definition where key='image.reverse-prompt'");
        }
        migration("49").clean();
        migration("49").migrate();
        try (var db = connection()) {
            assertThat(definition(db)).isNotEmpty();
            assertThat(scalar(db, "select content from prompt_definition where key='image.reverse-prompt'"))
                    .isEqualTo(upgraded);
            assertThat(scalar(db, "select built_in::text from prompt_definition where key='image.reverse-prompt'"))
                    .isEqualTo("true");
            assertThat(scalar(db, "select version::text from prompt_definition where key='image.reverse-prompt'"))
                    .isEqualTo("2");
        }
    }

    private Flyway migration(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false).target(target).load();
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private String definition(Connection db) throws SQLException {
        return scalar(db, "select to_jsonb(p)::text from prompt_definition p where key='image.reverse-prompt'");
    }
    private String otherPrompts(Connection db) throws SQLException {
        return scalar(db,
                "select jsonb_agg(to_jsonb(p) order by key)::text from prompt_definition p where key<>'image.reverse-prompt'");
    }
    private void execute(Connection db, String sql, Object... values) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }
    private String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}

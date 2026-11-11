package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL upgrade evidence with synthetic historical task data only. */
@Testcontainers
class CharacterViewPromptMigrationPostgresIT {
    private static final String KEY = "image.three-view.character";
    private static final String DESCRIPTION = "基于参考图与用户描述生成横版 4:3 角色设定图：左侧头肩特写，右侧依次为正面、标准 90 度侧面、背面全身；保持同一角色的外貌、服装与细节一致，风格跟随参考图或用户描述。主体说明追加到正文末尾，新任务冻结当前配置。";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @BeforeEach
    void cleanDisposableDatabase() { migration(null).clean(); }

    @Test
    void upgradeRefreshesFactoryBodyAndDescriptionWithoutChangingOtherPromptsOrFrozenTasks() throws Exception {
        migration("60").migrate();
        String othersBefore;
        String tasksBefore;
        String metadataBefore;
        long versionBefore;
        try (Connection db = connection()) {
            execute(db, "update prompt_definition set updated_at='2000-01-01T00:00:00Z' where key=?", KEY);
            createHistoricalTask(db);
            othersBefore = otherPrompts(db);
            tasksBefore = scalar(db, "select jsonb_agg(to_jsonb(t) order by id)::text from task t");
            metadataBefore = scalar(db, "select (to_jsonb(p)-'content'-'description'-'version'-'updated_at')::text from prompt_definition p where key=?", KEY);
            versionBefore = Long.parseLong(scalar(db, "select version::text from prompt_definition where key=?", KEY));
        }
        migration(null).migrate();
        try (Connection db = connection()) {
            assertNewBody(db);
            assertThat(scalar(db, "select description from prompt_definition where key=?", KEY)).isEqualTo(DESCRIPTION);
            assertThat(scalar(db, "select version::text from prompt_definition where key=?", KEY)).isEqualTo(Long.toString(versionBefore + 1));
            assertThat(scalar(db, "select (updated_at > '2000-01-01T00:00:00Z')::text from prompt_definition where key=?", KEY)).isEqualTo("true");
            assertThat(scalar(db, "select (to_jsonb(p)-'content'-'description'-'version'-'updated_at')::text from prompt_definition p where key=?", KEY)).isEqualTo(metadataBefore);
            assertThat(otherPrompts(db)).isEqualTo(othersBefore);
            assertThat(scalar(db, "select jsonb_agg(to_jsonb(t) order by id)::text from task t")).isEqualTo(tasksBefore);
        }
        assertThat(migration(null).migrate().migrationsExecuted).isZero();
    }

    @Test
    void upgradePreservesCustomNameAndDescriptionWhileRefreshingFactoryBody() throws Exception {
        migration("60").migrate();
        try (Connection db = connection()) {
            execute(db, "update prompt_definition set name='Synthetic custom name',description='Synthetic custom description',version=8 where key=?", KEY);
        }
        migration(null).migrate();
        try (Connection db = connection()) {
            assertNewBody(db);
            assertThat(scalar(db, "select name from prompt_definition where key=?", KEY)).isEqualTo("Synthetic custom name");
            assertThat(scalar(db, "select description from prompt_definition where key=?", KEY)).isEqualTo("Synthetic custom description");
            assertThat(scalar(db, "select version::text from prompt_definition where key=?", KEY)).isEqualTo("9");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upgradeLeavesCustomBodyCompletelyUntouchedIncludingWhitespaceOnlyEdits(boolean whitespaceOnly) throws Exception {
        migration("60").migrate();
        String before;
        try (Connection db = connection()) {
            execute(db, whitespaceOnly
                    ? "update prompt_definition set content=content || ' ',version=7 where key=?"
                    : "update prompt_definition set content='Synthetic custom body',version=7 where key=?", KEY);
            before = scalar(db, "select to_jsonb(p)::text from prompt_definition p where key=?", KEY);
        }
        migration(null).migrate();
        try (Connection db = connection()) {
            assertThat(scalar(db, "select to_jsonb(p)::text from prompt_definition p where key=?", KEY)).isEqualTo(before);
        }
    }

    @Test
    void freshInstallUsesTheStyleFlexibleFourPartSheet() throws Exception {
        migration(null).migrate();
        try (Connection db = connection()) { assertNewBody(db); }
    }

    private void assertNewBody(Connection db) throws SQLException {
        assertThat(scalar(db, "select content from prompt_definition where key=?", KEY))
                .contains("landscape 4:3", "four clearly separated parts", "front-facing head-and-shoulders close-up on the left",
                        "full-body character turnaround", "front view, exact 90-degree side profile, and back view",
                        "same scale", "aligned foot baseline", "without cropping heads or feet", "facial identity", "hairstyle",
                        "skin tone", "clothing", "color palette", "body proportions", "accessories", "visible details",
                        "explicit user style instructions taking priority", "high-definition detail", "simple, unobtrusive background")
                .doesNotContain("16:9", "3D", "semi-realistic", "cinematic", "studio", "4K");
    }

    private void createHistoricalTask(Connection db) throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        execute(db, "insert into app_user(id,login_name,password_hash,status,created_at) values (?,'synthetic-migration-user','synthetic-hash','ACTIVE',now())", owner);
        execute(db, "insert into project(id,owner_id,name,aspect_ratio,status,created_at,updated_at) values (?,?,'Synthetic migration project','LANDSCAPE_16_9','ACTIVE',now(),now())", project, owner);
        execute(db, """
                insert into task(id,project_id,step_key,kind,status,input_json,input_hash,attempt_no,next_action_at,created_at,updated_at)
                select ?,?,'synthetic-frozen-task','IMAGE_GENERATION','READY',
                    jsonb_build_object('promptKey',key,'promptVersion',version,'prompt',content,
                        'mediaInput',jsonb_build_object('prompt',content,'renderedPrompt',content,
                            'parameters',jsonb_build_object('aspectRatio','16:9'))),
                    repeat('a',64),1,now(),now(),now() from prompt_definition where key=?
                """, UUID.randomUUID(), project, KEY);
    }

    private String otherPrompts(Connection db) throws SQLException {
        return scalar(db, "select jsonb_agg(to_jsonb(p) order by key)::text from prompt_definition p where key<>?", KEY);
    }

    private Flyway migration(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        // Pin preservation evidence to this upgrade, independently of future migrations.
        config.target(target == null ? "61" : target);
        return config.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

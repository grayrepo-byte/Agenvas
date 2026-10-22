package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL upgrade evidence; prompt bodies and identifiers are entirely synthetic. */
@Testcontainers
class FixedDirectorPromptMigrationPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @BeforeEach
    void cleanDisposableDatabase() { migration(null).clean(); }

    @Test
    void upgradeRemovesOnlyRetiredAgentDefinitionsAndProtectsTheEditedDirector() throws Exception {
        migration("28").migrate();
        String directorBefore;
        String functionsBefore;
        try (Connection db = connection()) {
            execute(db, """
                    update prompt_definition set name='Synthetic customized director',description='Synthetic retained purpose',
                        content='Synthetic customized workflow',version=9 where key='agent.director'
                    """);
            prompt(db, "agent.retired-custom", "AGENT", false);
            prompt(db, "agent.retired-builtin", "AGENT", true);
            prompt(db, "feature.retained", "FUNCTION", false);
            directorBefore = scalar(db, "select to_jsonb(p)::text from prompt_definition p where key='agent.director'");
            functionsBefore = functions(db);
        }
        assertThat(migration(null).migrate().migrationsExecuted).isEqualTo(1);
        try (Connection db = connection()) {
            assertThat(scalar(db, "select to_jsonb(p)::text from prompt_definition p where key='agent.director'"))
                    .isEqualTo(directorBefore);
            assertThat(functions(db)).isEqualTo(functionsBefore);
            assertThat(scalar(db, "select string_agg(key,',') from prompt_definition where kind='AGENT'"))
                    .isEqualTo("agent.director");
            assertThatThrownBy(() -> prompt(db, "agent.forbidden", "AGENT", false))
                    .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> execute(db, "update prompt_definition set built_in=false where key='agent.director'"))
                    .isInstanceOf(SQLException.class).satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
            prompt(db, "feature.still-creatable", "FUNCTION", false);
            execute(db, "update prompt_definition set content='Synthetic edited function' where key='feature.still-creatable'");
            execute(db, "delete from prompt_definition where key='feature.still-creatable'");
            assertThat(functions(db)).isEqualTo(functionsBefore);
            assertThat(scalar(db, "select obj_description(oid,'pg_constraint') from pg_constraint where conname='ck_prompt_fixed_director'"))
                    .isNotBlank();
        }
    }

    @Test
    void freshInstallAndRepeatedMigrationKeepExactlyOneBuiltInDirector() throws Exception {
        migration(null).migrate();
        assertThat(migration(null).migrate().migrationsExecuted).isZero();
        try (Connection db = connection()) {
            assertThat(scalar(db, "select count(*)::text from prompt_definition where kind='AGENT' and key='agent.director' and built_in"))
                    .isEqualTo("1");
            assertThat(scalar(db, "select count(*)::text from prompt_definition where kind='FUNCTION'"))
                    .isNotEqualTo("0");
        }
    }

    private String functions(Connection db) throws SQLException {
        return scalar(db, "select jsonb_agg(to_jsonb(p) order by key)::text from prompt_definition p where kind='FUNCTION'");
    }

    private void prompt(Connection db, String key, String kind, boolean builtIn) throws SQLException {
        execute(db, "insert into prompt_definition(id,key,kind,name,description,content,built_in) values (?,?,?,'Synthetic prompt','Synthetic purpose','Synthetic content',?)",
                UUID.randomUUID(), key, kind, builtIn);
    }

    private Flyway migration(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        // Keep this migration's preservation evidence independent of future upgrades.
        config.target(target == null ? "29" : target);
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

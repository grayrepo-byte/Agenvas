package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.provider.application.LegacyMediaImportService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.task.domain.Task;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Old addresses become immutable recovery versions; rerunning the importer changes nothing. */
@Testcontainers
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=legacy-media-import-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class MediaLegacyImportPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcClient jdbc;
    @Autowired private LegacyMediaImportService importer;
    @Autowired private MediaCapabilityService catalog;

    @Test
    void importsTwoHistoricalOriginsIntoOneConnectionOnlyOnce() throws Exception {
        assertThat(importer.ready()).isTrue();
        String first = "http://127.0.0.1:8188";
        String second = "http://127.0.0.1:8288";
        jdbc.sql("insert into comfyui_config_version "
                        + "(config_version,origin,origin_sha256) values (1,:origin,:sha)")
                .param("origin", first).param("sha", sha(first)).update();
        jdbc.sql("insert into comfyui_config_version "
                        + "(config_version,origin,origin_sha256) values (2,:origin,:sha)")
                .param("origin", second).param("sha", sha(second)).update();
        jdbc.sql("update media_legacy_import_marker set completed_at=null where id=1").update();

        importer.importBeforeWorkers();
        UUID connectionId = jdbc.sql("select imported_connection_id "
                        + "from media_legacy_import_marker where id=1")
                .query(UUID.class).single();
        assertThat(connectionId).isNotNull();
        assertThat(jdbc.sql("select count(*) from media_capability "
                        + "where connection_id=:id").param("id", connectionId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from media_legacy_origin_map "
                        + "where connection_id=:id").param("id", connectionId)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(distinct connection_version) "
                        + "from media_legacy_origin_map where connection_id=:id")
                .param("id", connectionId).query(Long.class).single()).isEqualTo(2);
        assertThat(catalog.defaultFor(Task.Kind.IMAGE_GENERATION).adapterId())
                .isEqualTo("MOCK_IMAGE");

        importer.importBeforeWorkers();
        assertThat(jdbc.sql("select count(*) from media_provider_connection "
                        + "where name='Legacy ComfyUI'").query(Long.class).single())
                .isEqualTo(1);
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

package dev.agenvas.asset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Startup completes V52's destructive development reset in the local asset volume. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=creative-reset-secret-2026")
class CreativeDataResetPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final Path ROOT = prepareRoot();
    static final Path PROJECT_DIRECTORY = ROOT.resolve(UUID.randomUUID().toString());
    static final Path UNRELATED_DIRECTORY = ROOT.resolve("operator-notes");

    static {
        try {
            Files.createDirectories(PROJECT_DIRECTORY);
            Files.writeString(PROJECT_DIRECTORY.resolve("archived.png"), "old media");
            Files.createDirectories(UNRELATED_DIRECTORY);
            Files.writeString(UNRELATED_DIRECTORY.resolve("keep.txt"), "keep");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", ROOT::toString);
    }

    @Autowired private JdbcClient jdbc;

    @Test
    void removesOnlyUuidProjectDirectoriesAndCompletesTheResetMarker() {
        assertThat(PROJECT_DIRECTORY).doesNotExist();
        assertThat(UNRELATED_DIRECTORY.resolve("keep.txt")).hasContent("keep");
        assertThat(jdbc.sql("select completed_at is not null from creative_data_reset_marker "
                        + "where id=1")
                .query(Boolean.class).single()).isTrue();
    }

    private static Path prepareRoot() {
        try {
            return Files.createTempDirectory("agenvas-creative-reset-");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}

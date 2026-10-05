package dev.agenvas.asset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A clean-baseline startup preserves any pre-existing private archive directories. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class BaselineAssetPreservationPostgresIT {
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

    @Test
    void baselineStartupPreservesExistingProjectFiles() {
        assertThat(PROJECT_DIRECTORY.resolve("archived.png")).hasContent("old media");
        assertThat(UNRELATED_DIRECTORY.resolve("keep.txt")).hasContent("keep");
    }

    private static Path prepareRoot() {
        try {
            return Files.createTempDirectory("agenvas-creative-reset-");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}

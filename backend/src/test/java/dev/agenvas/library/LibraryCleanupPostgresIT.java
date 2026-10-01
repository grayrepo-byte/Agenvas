package dev.agenvas.library;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.library.infrastructure.LibraryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL checks cleanup eligibility when its clock leads the application's clock. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=cleanup-clock-test-secret",
        "agenvas.tasks.scheduler-enabled=false", "agenvas.library.worker-enabled=false"})
class LibraryCleanupPostgresIT {
    private static final Instant APPLICATION_NOW = Instant.parse("2020-01-01T00:00:00Z");
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired LibraryRepository repository;
    @Autowired ObjectMapper mapper;

    @Test
    void cleanupUsesTheApplicationClockAndPreservesDeferredRetries() {
        var owner = identities.setup("cleanup-clock-test-secret", "cleanup-admin", "cleanup-password-123");
        var command = new LibraryCommand(UUID.randomUUID(), owner.userId(), "cleanup-clock", "0".repeat(64),
                LibraryCommand.Kind.SAVE, mapper.createObjectNode().put("pin", "cleanup-fixture.png"),
                LibraryCommand.Status.SUCCEEDED, 0, null, null, null, null, APPLICATION_NOW, APPLICATION_NOW);

        repository.enqueuePinCleanup(command, APPLICATION_NOW);
        var ready = repository.cleanup(APPLICATION_NOW);
        assertThat(ready).as("new cleanup is immediately due on the supplied application clock").isPresent();
        var cleanup = ready.orElseThrow();
        assertThat(cleanup.owner()).isEqualTo(owner.userId());
        assertThat(cleanup.objectKey()).isEqualTo("cleanup-fixture.png");

        Instant retryAt = APPLICATION_NOW.plus(RETRY_DELAY);
        repository.deferCleanup(cleanup.id(), retryAt);
        repository.enqueuePinCleanup(command, APPLICATION_NOW);
        assertThat(repository.cleanup(retryAt.minusSeconds(1))).isEmpty();
        assertThat(repository.cleanup(retryAt)).contains(cleanup);
        repository.cleaned(cleanup.id());
        assertThat(repository.cleanup(retryAt)).isEmpty();
    }
}

package dev.agenvas.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL integration evidence for migrations and one-time setup arbitration. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=integration-bootstrap-secret")
class IdentityPostgresIT {

    private static final int CONCURRENT_ATTEMPTS = 20;

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdentityService identityService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ProjectService projectService;

    @Test
    void emptyDatabaseMigratesAndConcurrentSetupCreatesOneAdministrator() throws Exception {
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("34");
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SetupOutcome>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS)) {
            for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                int suffix = attempt;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        AdminPrincipal principal = identityService.setup(
                                "integration-bootstrap-secret",
                                "admin" + suffix,
                                "integration-password-123");
                        return new SetupOutcome(true, principal.loginName());
                    } catch (ApiProblemException conflict) {
                        return new SetupOutcome(false, conflict.code());
                    }
                }));
            }
            start.countDown();
            List<SetupOutcome> outcomes = new ArrayList<>();
            for (Future<SetupOutcome> future : futures) {
                outcomes.add(future.get());
            }
            assertThat(outcomes).filteredOn(SetupOutcome::created).hasSize(1);
            assertThat(outcomes)
                    .filteredOn(outcome -> !outcome.created())
                    .extracting(SetupOutcome::result)
                    .containsOnly("SETUP_ALREADY_COMPLETED");
        }
        assertThat(jdbcClient.sql("select count(*) from app_user")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);

        UUID ownerId = jdbcClient.sql("select id from app_user")
                .query(UUID.class)
                .single();
        UUID otherUserId = UUID.randomUUID();
        OffsetDateTime fixtureTime = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                        insert into app_user (
                            id, login_name, password_hash, status, created_at,
                            password_changed_at, version
                        ) values (
                            :id, :loginName, :passwordHash, 'DISABLED', :createdAt,
                            :passwordChangedAt, 0
                        )
                        """)
                .param("id", otherUserId)
                .param("loginName", "disabled-fixture")
                .param("passwordHash", "not-used-by-this-fixture")
                .param("createdAt", fixtureTime)
                .param("passwordChangedAt", fixtureTime)
                .update();
        Project project = projectService.create(
                ownerId, "Coffee launch", Project.AspectRatio.LANDSCAPE_16_9);
        assertThat(project.version()).isZero();
        assertThatThrownByCode(
                "RESOURCE_NOT_FOUND", () -> projectService.get(otherUserId, project.id()));
        assertThatThrownByCode(
                "RESOURCE_NOT_FOUND",
                () -> projectService.update(
                        otherUserId, project.id(), 0, "Cross-owner edit", null));
        assertThatThrownByCode(
                "VALIDATION_ERROR",
                () -> projectService.update(ownerId, project.id(), 0, null, null));

        Project updated = projectService.update(
                ownerId,
                project.id(),
                0,
                "Coffee launch v2",
                Project.AspectRatio.PORTRAIT_9_16);
        assertThat(updated.version()).isEqualTo(1);
        assertThatThrownByCode(
                "VERSION_CONFLICT",
                () -> projectService.update(ownerId, project.id(), 0, "stale", null));

        Project archived = projectService.archive(ownerId, project.id(), 1);
        assertThat(archived.status()).isEqualTo(Project.Status.ARCHIVED);
        assertThat(projectService.archive(ownerId, project.id(), 1)).isEqualTo(archived);
        assertThatThrownByCode(
                "PROJECT_ARCHIVED",
                () -> projectService.update(ownerId, project.id(), 2, "not allowed", null));
        assertThatThrownByCode(
                "PROJECT_ARCHIVED",
                () -> projectService.requireActiveProject(ownerId, project.id()));
        assertThat(projectService.list(ownerId, false, null, 20).items()).isEmpty();
        assertThat(projectService.list(ownerId, true, null, 20).items())
                .extracting(Project::id)
                .containsExactly(project.id());

        Project first = projectService.create(ownerId, "First", Project.AspectRatio.SQUARE_1_1);
        Project second = projectService.create(ownerId, "Second", Project.AspectRatio.SQUARE_1_1);
        Project third = projectService.create(ownerId, "Third", Project.AspectRatio.SQUARE_1_1);
        ProjectService.ProjectPage firstPage = projectService.list(ownerId, false, null, 2);
        ProjectService.ProjectPage secondPage =
                projectService.list(ownerId, false, firstPage.nextCursor(), 2);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(firstPage.items())
                .extracting(Project::id)
                .doesNotContainAnyElementsOf(
                        secondPage.items().stream().map(Project::id).toList());
        assertThat(Stream.concat(firstPage.items().stream(), secondPage.items().stream()))
                .extracting(Project::id)
                .containsExactlyInAnyOrder(first.id(), second.id(), third.id());
    }

    private void assertThatThrownByCode(String expectedCode, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected ApiProblemException with code " + expectedCode);
        } catch (ApiProblemException problem) {
            assertThat(problem.code()).isEqualTo(expectedCode);
        }
    }

    private record SetupOutcome(boolean created, String result) {}
}

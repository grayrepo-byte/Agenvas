package dev.agenvas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminAccountRepository;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.identity.application.SetupStatusService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL and HTTP evidence for secret-free, permanently closed initialization. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class IdentitySetupPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired AdminAccountRepository accounts;
    @Autowired SetupStatusService setupStatus;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    @Autowired PlatformTransactionManager transactions;
    private MockMvc mvc;

    @BeforeEach
    void resetOnlyThisDisposableDatabase() {
        jdbc.sql("truncate app_user cascade").update();
        jdbc.sql("update installation_lock set initialized_at=null").update();
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void setupNeedsOnlyAnAccountAndStillEnforcesCsrfAndSingleCompletion() throws Exception {
        String account = "{\"loginName\":\"admin\",\"password\":\"synthetic-test-password\"}";
        mvc.perform(post("/api/v1/auth/setup").contentType("application/json").content(account))
                .andExpect(status().isForbidden());
        assertThat(setupStatus.isSetupRequired()).isTrue();
        mvc.perform(post("/api/v1/auth/setup").with(csrf()).contentType("application/json").content(account))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.loginName").value("admin"));
        mvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupRequired").value(false));
        mvc.perform(post("/api/v1/auth/setup").with(csrf()).contentType("application/json").content(account))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
        assertThat(jdbc.sql("select count(*) from app_user").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void disablingAndDeletingAdministratorNeverReopensSetup() {
        var admin = identities.setup("admin", "synthetic-test-password");
        OffsetDateTime completion = jdbc.sql("select initialized_at from installation_lock")
                .query(OffsetDateTime.class).single();
        assertThat(completion).isNotNull();
        jdbc.sql("update app_user set status='DISABLED' where id=:id").param("id", admin.userId()).update();
        assertSetupClosed();
        jdbc.sql("delete from app_user where id=:id").param("id", admin.userId()).update();
        // A new reader models a fresh application instance: state comes from PostgreSQL.
        assertThat(new SetupStatusService(accounts).isSetupRequired()).isFalse();
        assertSetupClosed();
        assertThat(jdbc.sql("select initialized_at from installation_lock").query(OffsetDateTime.class).single())
                .isEqualTo(completion);
    }

    @Test
    void failedTransactionRollsBackBothAccountAndCompletionThenAllowsRetry() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            identities.setup("admin", "synthetic-test-password");
            throw new IllegalStateException("synthetic rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(setupStatus.isSetupRequired()).isTrue();
        assertThat(jdbc.sql("select count(*) from app_user").query(Integer.class).single()).isZero();
        identities.setup("admin", "synthetic-test-password");
        assertSetupClosed();
    }

    @Test
    void anyPreexistingAccountIncludingDisabledBlocksInitializationWithoutAMarker() {
        var admin = identities.setup("admin", "synthetic-test-password");
        jdbc.sql("update installation_lock set initialized_at=null").update();
        jdbc.sql("update app_user set status='DISABLED' where id=:id").param("id", admin.userId()).update();
        assertSetupClosed();
    }

    private void assertSetupClosed() {
        assertThat(setupStatus.isSetupRequired()).isFalse();
        assertThatThrownBy(() -> identities.setup("another-admin", "synthetic-test-password"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(problem -> ((ApiProblemException) problem).code())
                .isEqualTo("SETUP_ALREADY_COMPLETED");
    }
}

package dev.agenvas.mediatemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSyncSettings;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptService;
import dev.agenvas.mediatemplate.infrastructure.ThirdPartyPromptHttpClient;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSyncSettingsService;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL migration/CAS and protected API; no catalogue or model network traffic. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = "agenvas.media-templates.sync-enabled=true")
class ThirdPartyPromptSyncSettingsPostgresIT {
    private static final String ENDPOINT = "/api/v1/settings/media-template-sync";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @MockitoSpyBean
    ThirdPartyPromptSyncSettingsService settings;
    @MockitoBean
    ThirdPartyPromptHttpClient http;
    @Autowired
    ThirdPartyPromptService prompts;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    DSLContext db;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WebApplicationContext application;
    private static ThirdPartyPromptSyncSettings migrated;
    private MockMvc mvc;
    @BeforeEach
    void fixture() {
        if (migrated == null)
            migrated = settings.get();
        jdbc.sql("update third_party_prompt_sync_settings set enabled=false, version=0 where id=1").update();
        mvc = webAppContextSetup(application).apply(springSecurity()).build();
    }
    @Test
    void migrationRequiresExplicitConsentAndStateSurvivesANewReaderWithoutTouchingSources() {
        assertThat(migrated).isEqualTo(new ThirdPartyPromptSyncSettings(false, true, 0));
        var sources = jdbc.sql("select id,enabled,version from third_party_prompt_source order by id").query()
                .listOfRows();
        var saved = settings.update(true, 0);
        assertThat(saved).isEqualTo(new ThirdPartyPromptSyncSettings(true, true, 1));
        assertThat(new ThirdPartyPromptSyncSettingsService(db, true, false).get()).isEqualTo(saved);
        assertThat(
                jdbc.sql("select id,enabled,version from third_party_prompt_source order by id").query().listOfRows())
                .isEqualTo(sources);
    }
    @Test
    void apiRequiresAdministratorCsrfCompleteInputAndCurrentVersion() throws Exception {
        String body = "{\"enabled\":true,\"expectedVersion\":0}";
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ENDPOINT).with(user("reader").roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(put(ENDPOINT).with(user("reader").roles("USER")).with(csrf()).contentType("application/json")
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN")).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        for (String invalid : new String[]{"{}", "{\"enabled\":true}", "{\"enabled\":true,\"expectedVersion\":-1}"}) {
            mvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                    .content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.deploymentEnabled").value(true)).andExpect(jsonPath("$.version").value(0));
        mvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(settings.get()).isEqualTo(new ThirdPartyPromptSyncSettings(true, true, 1));
    }
    @Test
    void concurrentAdministratorsHaveExactlyOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    settings.update(index == 0, 0);
                    return true;
                } catch (ApiProblemException failure) {
                    assertThat(failure.code()).isEqualTo("VERSION_CONFLICT");
                    return false;
                }
            })).toList();
            start.countDown();
            int successes = 0;
            for (var attempt : attempts)
                if (attempt.get(10, TimeUnit.SECONDS))
                    successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(settings.get().version()).isEqualTo(1);
    }
    @Test
    void disablingBetweenPreflightAndLeaseAcceptancePreventsTheDownload() throws Exception {
        settings.update(true, 0);
        var beforeClaim = new CountDownLatch(1);
        var resumeClaim = new CountDownLatch(1);
        var deniedClaim = new CountDownLatch(1);
        var reads = new AtomicInteger();
        ThirdPartyPromptSyncSettingsService target = org.springframework.test.util.AopTestUtils
                .getUltimateTargetObject(settings);
        doAnswer(call -> {
            var value = (ThirdPartyPromptSyncSettings) call.callRealMethod();
            // First read is the outer scan preflight; second read precedes one source claim.
            if (reads.incrementAndGet() == 2) {
                beforeClaim.countDown();
                assertThat(resumeClaim.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return value;
        }).when(target).get();
        doAnswer(call -> {
            boolean allowed = (boolean) call.callRealMethod();
            if (!allowed)
                deniedClaim.countDown();
            return allowed;
        }).when(target).allowScheduledClaim();
        try {
            prompts.syncDue();
            assertThat(beforeClaim.await(5, TimeUnit.SECONDS)).isTrue();
            settings.update(false, 1);
            resumeClaim.countDown();
            assertThat(deniedClaim.await(5, TimeUnit.SECONDS)).isTrue();
            verify(http, after(200).never()).feed(anyString());
        } finally {
            resumeClaim.countDown();
        }
    }

    @Test
    void disablingWaitsForAnAlreadyAcceptedClaimButDoesNotHoldAnyNetworkTransaction() throws Exception {
        settings.update(true, 0);
        var sharedLock = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var claim = workers.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                assertThat(settings.allowScheduledClaim()).isTrue();
                sharedLock.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return true;
            }));
            assertThat(sharedLock.await(5, TimeUnit.SECONDS)).isTrue();
            var disabled = workers.submit(() -> settings.update(false, 1));
            assertThatThrownBy(() -> disabled.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(claim.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(disabled.get(5, TimeUnit.SECONDS).enabled()).isFalse();
            Boolean allowed = new TransactionTemplate(transactions).execute(ignored -> settings.allowScheduledClaim());
            assertThat(allowed).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void deploymentLimitDoesNotBecomeConsentAndRecoveryCannotSave() {
        var deploymentOff = new ThirdPartyPromptSyncSettingsService(db, false, false);
        assertThat(deploymentOff.get()).isEqualTo(new ThirdPartyPromptSyncSettings(false, false, 0));
        assertThat(deploymentOff.update(true, 0)).isEqualTo(new ThirdPartyPromptSyncSettings(true, false, 1));
        var recovery = new ThirdPartyPromptSyncSettingsService(db, true, true);
        assertThatThrownBy(() -> recovery.update(false, 1)).isInstanceOfSatisfying(ApiProblemException.class,
                failure -> assertThat(failure.code()).isEqualTo("RECOVERY_MODE_READ_ONLY"));
        assertThat(settings.get().enabled()).isTrue();
    }
}

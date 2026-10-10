package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Synthetic configurations against real PostgreSQL; no external provider calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class MediaCapabilityDeletionPostgresIT {
    @Container
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES =
            new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MediaCapabilityService catalog;
    @Autowired MediaFunctionService functions;
    @Autowired JooqMediaCapabilityRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    @Autowired PlatformTransactionManager transactions;

    private JooqMediaCapabilityRepository.Capability publish() {
        var connection = catalog.createConnection(UUID.randomUUID().toString(), "Synthetic connection", "OPENAI", null, "synthetic-key");
        return catalog.publishCapability(connection.id(), "Synthetic capability", "OPENAI_GPT_IMAGE_2");
    }

    @Test
    void deletionClearsSelectionsAndRetainsPinnedHistory() {
        var capability = publish();
        var binding = catalog.resolve(capability.id(), Task.Kind.IMAGE_GENERATION, 0);
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability.id());
        long defaultVersion = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        var function = functions.list().stream().filter(item -> item.operation() == MediaFunction.IMAGE_SMART_EDIT).findFirst().orElseThrow();
        functions.update(function.operation(), function.version(), capability.id());
        long functionVersion = function.version() + 1;
        catalog.deleteCapability(capability.connectionId(), capability.id(), capability.version());

        assertThat(catalog.capabilities(capability.connectionId())).isEmpty();
        assertThat(catalog.publishedCandidates()).noneMatch(item -> item.binding().capabilityId().equals(capability.id()));
        assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isNull();
        assertThat(catalog.defaultVersion(Task.Kind.IMAGE_GENERATION)).isEqualTo(defaultVersion + 1);
        var cleared = functions.list().stream().filter(item -> item.operation() == function.operation()).findFirst().orElseThrow();
        assertThat(cleared.capabilityId()).isNull();
        assertThat(cleared.version()).isEqualTo(functionVersion + 1);
        assertThat(catalog.pinnedSnapshot(binding).specJson()).isEqualTo(catalog.capabilitySnapshot(capability.id()).specJson());
        assertThat(jdbc.sql("select count(*) from media_capability_version where capability_id=:id")
                .param("id", capability.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(catalog.declaredDraftInputs(capability.id())).isEmpty();
        assertThatThrownBy(() -> catalog.resolve(capability.id(), Task.Kind.IMAGE_GENERATION, 0)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.updateCapability(capability.connectionId(), capability.id(), capability.version() + 1,
                "Resurrected", true, "OPENAI_GPT_IMAGE_2")).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.setDefault(Task.Kind.IMAGE_GENERATION, defaultVersion + 1, capability.id())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> functions.update(function.operation(), cleared.version(), capability.id())).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void wrongConnectionStaleVersionAndBuiltinDoNotDeleteAnything() {
        var capability = publish();
        assertThatThrownBy(() -> catalog.deleteCapability(UUID.randomUUID(), capability.id(), 0)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> catalog.deleteCapability(capability.connectionId(), capability.id(), 1)).isInstanceOf(ApiProblemException.class);
        assertThat(catalog.capabilities(capability.connectionId())).hasSize(1);
        var local = catalog.connections().stream().filter(item -> item.platform().name().equals("LOCAL")).findFirst().orElseThrow();
        var builtin = catalog.capabilities(local.id()).getFirst();
        assertThatThrownBy(() -> catalog.deleteCapability(local.id(), builtin.id(), builtin.version())).isInstanceOf(ApiProblemException.class);
        assertThat(catalog.capabilities(local.id())).contains(builtin);
    }

    @Test
    void disabledCapabilitiesAndConnectionsCanBeDeletedWithoutChangingOtherDefaults() {
        var capability = publish();
        UUID defaultId = catalog.defaultCapabilityId(Task.Kind.VIDEO_GENERATION);
        long defaultVersion = catalog.defaultVersion(Task.Kind.VIDEO_GENERATION);
        catalog.updateCapability(capability.connectionId(), capability.id(), 0, capability.name(), false, "OPENAI_GPT_IMAGE_2");
        var connection = catalog.getConnection(capability.connectionId());
        catalog.setConnectionEnabled(connection.id(), connection.version(), false);
        catalog.deleteCapability(connection.id(), capability.id(), 1);
        assertThat(catalog.capabilities(connection.id())).isEmpty();
        assertThat(catalog.defaultCapabilityId(Task.Kind.VIDEO_GENERATION)).isEqualTo(defaultId);
        assertThat(catalog.defaultVersion(Task.Kind.VIDEO_GENERATION)).isEqualTo(defaultVersion);
    }

    @Test
    void failedTransactionRollsBackDeletionAndDefaultClearing() {
        var capability = publish();
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability.id());
        long version = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            catalog.deleteCapability(capability.connectionId(), capability.id(), 0);
            tx.setRollbackOnly();
        });
        assertThat(catalog.capabilities(capability.connectionId())).contains(capability);
        assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isEqualTo(capability.id());
        assertThat(catalog.defaultVersion(Task.Kind.IMAGE_GENERATION)).isEqualTo(version);
    }

    @Test
    void concurrentDeletesOnlySucceedOnce() throws Exception {
        var capability = publish();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = executor.invokeAll(List.of(
                    () -> attemptDelete(capability), () -> attemptDelete(capability)));
            int successes = 0;
            for (var attempt : attempts) if (Boolean.TRUE.equals(attempt.get(10, TimeUnit.SECONDS))) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(repository.capability(capability.id()).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void configurationSelectionsWaitForDeletionAndCannotRestoreItsBindings() throws Exception {
        var capability = publish();
        long defaultVersion = catalog.defaultVersion(Task.Kind.IMAGE_GENERATION);
        var function = functions.list().stream().filter(item -> item.operation() == MediaFunction.IMAGE_SMART_EDIT).findFirst().orElseThrow();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var started = new java.util.concurrent.CountDownLatch(2);
            var pending = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                catalog.deleteCapability(capability.connectionId(), capability.id(), 0);
                // Prime statistics before either selector starts, covering an initially empty observation.
                assertThat(capabilitySelectionWaiters()).isZero();
                pending.add(executor.submit(() -> {
                    started.countDown();
                    catalog.setDefault(Task.Kind.IMAGE_GENERATION, defaultVersion, capability.id());
                }));
                pending.add(executor.submit(() -> {
                    started.countDown();
                    functions.update(function.operation(), function.version(), capability.id());
                }));
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int blocked = 0;
                while (blocked < 2 && System.nanoTime() < deadline) {
                    // Statistics are cached per transaction; refresh them without releasing the deletion lock.
                    jdbc.sql("select pg_stat_clear_snapshot()").query().listOfRows();
                    blocked = capabilitySelectionWaiters();
                    if (blocked < 2) {
                        try { Thread.sleep(20); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                }
                assertThat(blocked).isEqualTo(2);
                assertThat(pending).allMatch(item -> !item.isDone());
            });
            for (var selection : pending) {
                assertThatThrownBy(() -> selection.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiProblemException.class);
            }
        }
        assertThat(catalog.defaultCapabilityId(Task.Kind.IMAGE_GENERATION)).isNotEqualTo(capability.id());
        assertThat(functions.list()).noneMatch(item -> capability.id().equals(item.capabilityId()));
    }

    private int capabilitySelectionWaiters() {
        return jdbc.sql("""
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and wait_event_type = 'Lock'
              and query like '%media_capability%' and query like '%for update%'
            """).query(Integer.class).single();
    }

    private boolean attemptDelete(JooqMediaCapabilityRepository.Capability capability) {
        try {
            catalog.deleteCapability(capability.connectionId(), capability.id(), 0);
            return true;
        } catch (ApiProblemException conflict) {
            assertThat(conflict.code()).isEqualTo("MEDIA_CAPABILITY_CONFLICT");
            return false;
        }
    }

    @Test
    void deleteHttpContractRequiresAdministratorCsrfAndVersion() throws Exception {
        var capability = publish();
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var principal = new AdminPrincipal(UUID.randomUUID(), "synthetic-admin");
        var admin = authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var user = authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        String path = "/api/v1/settings/media-connections/" + capability.connectionId() + "/capabilities/" + capability.id();
        mvc.perform(delete(path).with(csrf()).param("expectedVersion", "0")).andExpect(status().isUnauthorized());
        mvc.perform(delete(path).with(user).with(csrf()).param("expectedVersion", "0")).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(admin).param("expectedVersion", "0")).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(admin).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete(path).with(admin).with(csrf()).param("expectedVersion", "-1")).andExpect(status().isBadRequest());
        mvc.perform(delete(path).with(admin).with(csrf()).param("expectedVersion", "1")).andExpect(status().isConflict());
        mvc.perform(delete(path).with(admin).with(csrf()).param("expectedVersion", "0"))
                .andExpect(status().isOk()).andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).doesNotContain(capability.id().toString(), "synthetic-key", "deletedAt");
                    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
                });
        mvc.perform(get("/api/v1/settings/media-connections").with(admin))
                .andExpect(status().isOk()).andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(capability.id().toString()));
        mvc.perform(delete(path).with(admin).with(csrf()).param("expectedVersion", "0")).andExpect(status().isConflict());
    }
}

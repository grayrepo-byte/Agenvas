package dev.agenvas.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentConversationRepository;
import dev.agenvas.run.application.AgentConversationService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentConversation;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL evidence for conversation scope, sequence allocation, CAS and independent Run slots. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=conversation-integration-secret",
        "agenvas.llm.mode=mock"})
class AgentConversationPostgresIT {
    private static final String BOOTSTRAP_SECRET = "conversation-integration-secret";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private AgentConversationService conversations;
    @Autowired private AgentConversationRepository conversationRows;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext webContext;

    @Test
    void sessionsKeepIndependentHistoryWhileMessagesShareOneProjectSlot() throws Exception {
        AdminPrincipal owner = identities.setup(BOOTSTRAP_SECRET, "conversation-admin", "conversation-password-123");
        Project project = projects.create(owner.userId(), "Conversations", Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator", "制作短片", List.of());
        var empty = conversations.list(owner.userId(), project.id(), agent.id(), null, null);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.currentConversationId()).isNull();
        var initialPreflight = runs.preflight(owner.userId(), project.id(), agent.id());
        assertThat(initialPreflight.conversationId()).isNull();
        assertThat(initialPreflight.conversationVersion()).isNull();
        assertThat(initialPreflight.conversationTurnCount()).isZero();
        assertThat(initialPreflight.memoryTruncated()).isFalse();

        AgentRun first = runs.create(owner.userId(), project.id(), agent.id(), "第一会话的背景", "default-first").run();
        UUID firstConversation = first.conversationId();
        assertThat(first.conversationTurn()).isEqualTo(1);
        assertThat(first.contextSnapshot().path("conversationMemory").path("entries")).isEmpty();
        assertThat(conversations.get(owner.userId(), project.id(), agent.id(), firstConversation).title())
                .isEqualTo(first.instruction());

        AgentConversation secondConversation = conversations.create(owner.userId(), project.id(), agent.id(), "new-session");
        assertThat(secondConversation.version()).isZero();
        assertThat(conversations.create(owner.userId(), project.id(), agent.id(), "new-session").id())
                .isEqualTo(secondConversation.id());
        conversations.select(owner.userId(), project.id(), agent.id(), firstConversation);
        assertThat(conversations.create(owner.userId(), project.id(), agent.id(), "new-session").id())
                .isEqualTo(secondConversation.id());
        assertThat(conversations.list(owner.userId(), project.id(), agent.id(), null, null).currentConversationId())
                .isEqualTo(firstConversation);
        assertThat(activeRun(project.id())).isEqualTo(first.id());
        assertThat(runs.get(owner.userId(), project.id(), first.id()).status()).isEqualTo(AgentRun.Status.QUEUED);
        assertProblem("ACTIVE_RUN_EXISTS", () -> runs.preflight(owner.userId(), project.id(), agent.id(), firstConversation));
        assertProblem("ACTIVE_RUN_EXISTS", () -> send(owner, project, agent, secondConversation.id(), 0, "busy"));
        assertThat(conversations.get(owner.userId(), project.id(), agent.id(), secondConversation.id()).turnCount()).isZero();
        runs.cancel(owner.userId(), project.id(), first.id());

        var reviewed = runs.preflight(owner.userId(), project.id(), agent.id(), firstConversation);
        assertThat(reviewed.conversationId()).isEqualTo(firstConversation);
        assertThat(reviewed.conversationVersion()).isEqualTo(1);
        assertThat(reviewed.conversationTurnCount()).isEqualTo(1);
        assertProblem("CONVERSATION_VERSION_CONFLICT", () -> send(owner, project, agent, firstConversation, 0, "stale"));
        AgentRun second = send(owner, project, agent, firstConversation, 1, "second");
        var persistedSecondSnapshot = runs.get(owner.userId(), project.id(), second.id()).contextSnapshot();
        assertThat(second.conversationTurn()).isEqualTo(2);
        assertThat(second.contextSnapshot().path("conversationHistoryThroughTurn").asLong()).isEqualTo(1);
        assertThat(second.contextSnapshot().path("conversationMemory").toString()).contains(first.instruction());
        assertThat(send(owner, project, agent, firstConversation, 1, "second").id()).isEqualTo(second.id());
        assertThat(conversations.get(owner.userId(), project.id(), agent.id(), firstConversation).turnCount()).isEqualTo(2);
        assertProblem("IDEMPOTENCY_CONFLICT", () -> send(owner, project, agent, secondConversation.id(), 1, "second"));
        conversations.select(owner.userId(), project.id(), agent.id(), secondConversation.id());
        assertThat(runs.create(owner.userId(), project.id(), agent.id(), first.instruction(), "default-first").run().id())
                .isEqualTo(first.id());
        assertThat(activeRun(project.id())).isEqualTo(second.id());
        runs.cancel(owner.userId(), project.id(), second.id());

        // Two fresh keys race for the same conversation: exactly one commits turn 3 and the project slot.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Attempt> attempts;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of("race-a", "race-b").stream().map(key -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start timed out");
                try { return new Attempt(send(owner, project, agent, firstConversation, 2, key), null); }
                catch (ApiProblemException failure) { return new Attempt(null, failure.code()); }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            attempts = List.of(futures.getFirst().get(20, TimeUnit.SECONDS), futures.getLast().get(20, TimeUnit.SECONDS));
        }
        assertThat(attempts).filteredOn(attempt -> attempt.run() != null).singleElement()
                .satisfies(attempt -> assertThat(attempt.run().conversationTurn()).isEqualTo(3));
        assertThat(attempts).filteredOn(attempt -> attempt.code() != null).singleElement()
                .satisfies(attempt -> assertThat(attempt.code()).isEqualTo("ACTIVE_RUN_EXISTS"));
        AgentRun winner = attempts.stream().map(Attempt::run).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        assertThat(activeRun(project.id())).isEqualTo(winner.id());
        runs.cancel(owner.userId(), project.id(), winner.id());
        AgentRun fourth = send(owner, project, agent, firstConversation, 3, "fourth");
        assertThat(fourth.conversationTurn()).isEqualTo(4);
        runs.cancel(owner.userId(), project.id(), fourth.id());

        var newest = runs.listConversation(owner.userId(), project.id(), agent.id(), firstConversation, null, 1);
        assertThat(newest.items()).extracting(AgentRun::id).containsExactly(fourth.id());
        var older = runs.listConversation(owner.userId(), project.id(), agent.id(), firstConversation, newest.nextCursor(), 10);
        assertThat(older.items()).extracting(AgentRun::conversationTurn).containsExactly(3L, 2L, 1L);
        assertThat(older.nextCursor()).isNull();
        var sessionPage = conversations.list(owner.userId(), project.id(), agent.id(), null, 1);
        var sessionNext = conversations.list(owner.userId(), project.id(), agent.id(), sessionPage.nextCursor(), 1);
        assertThat(List.of(sessionPage.items().getFirst().id(), sessionNext.items().getFirst().id()))
                .containsExactlyInAnyOrder(firstConversation, secondConversation.id());
        assertThat(sessionNext.nextCursor()).isNull();
        assertProblem("VALIDATION_ERROR", () -> runs.listConversation(owner.userId(), project.id(), agent.id(), firstConversation, "bad", 1));
        assertProblem("VALIDATION_ERROR", () -> conversations.list(owner.userId(), project.id(), agent.id(), null, 101));

        AgentRun fresh = send(owner, project, agent, secondConversation.id(), 0, "fresh");
        assertThat(fresh.conversationTurn()).isEqualTo(1);
        assertThat(fresh.contextSnapshot().path("conversationMemory").path("entries")).isEmpty();
        assertThat(fresh.contextSnapshot().path("bindings")).isEmpty();
        assertThat(runs.get(owner.userId(), project.id(), second.id()).contextSnapshot())
                .isEqualTo(persistedSecondSnapshot);
        runs.cancel(owner.userId(), project.id(), fresh.id());

        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var ownerAuth = new UsernamePasswordAuthenticationToken(owner, null, List.of());
        String path = "/api/v1/projects/" + project.id() + "/agents/" + agent.id() + "/conversations";
        mvc.perform(get(path).with(authentication(ownerAuth))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.currentConversationId").value(secondConversation.id().toString()));
        mvc.perform(get(path + "/" + firstConversation + "/runs").with(authentication(ownerAuth)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].conversationId").value(firstConversation.toString()))
                .andExpect(jsonPath("$.items[0].conversationTurn").value(4))
                .andExpect(jsonPath("$.items[0].contextSnapshot").doesNotExist());
        mvc.perform(post(path + "/" + firstConversation + "/select").with(authentication(ownerAuth)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(firstConversation.toString()));
        mvc.perform(post(path).with(authentication(ownerAuth)).with(csrf()).header("Idempotency-Key", "new-session"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(secondConversation.id().toString()));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());

        UUID otherOwner = UUID.randomUUID();
        AgentInstance otherAgent = agents.create(owner.userId(), project.id(), "Other", "独立 Agent", List.of());
        Project otherProject = projects.create(owner.userId(), "Other project", Project.AspectRatio.LANDSCAPE_16_9);
        assertProblem("RESOURCE_NOT_FOUND", () -> conversations.get(otherOwner, project.id(), agent.id(), firstConversation));
        assertProblem("RESOURCE_NOT_FOUND", () -> conversations.select(owner.userId(), project.id(), otherAgent.id(), firstConversation));
        assertProblem("RESOURCE_NOT_FOUND", () -> runs.preflight(owner.userId(), otherProject.id(), agent.id(), firstConversation));
        assertProblem("RESOURCE_NOT_FOUND", () -> send(owner, project, otherAgent, firstConversation, 4, "cross-agent"));
        assertProblem("RESOURCE_NOT_FOUND", () -> runs.listConversation(owner.userId(), project.id(), otherAgent.id(), firstConversation, null, null));
        assertThat(conversationRows.find(otherOwner, project.id(), agent.id(), firstConversation)).isEmpty();
        assertThat(conversationRows.current(otherOwner, project.id(), agent.id())).isEmpty();
        assertThat(conversationRows.list(otherOwner, project.id(), agent.id(), null, null, 20)).isEmpty();
        assertThat(conversationRows.select(owner.userId(), project.id(), otherAgent.id(), firstConversation)).isFalse();
        mvc.perform(get(path).with(authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(otherOwner, "other"), null, List.of())))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/" + firstConversation + "/runs").with(authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(otherOwner, "other"), null, List.of())))).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select count(*) from agent_run where project_id = :projectId")
                .param("projectId", project.id()).query(Long.class).single()).isEqualTo(5);
        assertThat(conversations.get(owner.userId(), project.id(), agent.id(), firstConversation).version()).isEqualTo(4);
    }

    private AgentRun send(AdminPrincipal owner, Project project, AgentInstance agent, UUID conversationId,
            long expectedVersion, String key) {
        return runs.create(owner.userId(), project.id(), agent.id(), "消息 " + key, key,
                null, null, List.of(), null, null, null, conversationId, expectedVersion).run();
    }

    private UUID activeRun(UUID projectId) {
        return jdbc.sql("select active_run_id from project where id = :projectId").param("projectId", projectId)
                .query(UUID.class).optional().orElse(null);
    }

    private void assertProblem(String code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }

    private record Attempt(AgentRun run, String code) {}
}

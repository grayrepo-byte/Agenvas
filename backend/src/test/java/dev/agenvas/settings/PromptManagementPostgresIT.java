package dev.agenvas.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL; all prompts and identities are synthetic and no Provider is invoked. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=synthetic-agent-defaults-bootstrap",
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class PromptManagementPostgresIT {
    private static final String PATH = "/api/v1/settings/prompts";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AgentInstanceService agents;
    @Autowired PromptService defaults;
    @Autowired AgentRunService runs;
    @Autowired InitialModelContextService contexts;
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    private static AdminPrincipal owner;
    MockMvc mvc;
    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("synthetic-agent-defaults-bootstrap", "synthetic-director-admin", "synthetic-director-password");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }
    private UsernamePasswordAuthenticationToken asUser(String role) {
        return new UsernamePasswordAuthenticationToken(owner, "unused", List.of(new SimpleGrantedAuthority(role)));
    }

    private PromptService.Prompt updateDirector(long version, String name, String content) {
        return defaults.update(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).id(), version, name, "Synthetic purpose", content);
    }

    @Test void administratorCanCreateAndReadDistinctRecordsAndTheSingletonTableDoesNotExist() throws Exception {
        assertThat(jdbc.sql("select to_regclass('public.agent_default_prompt') is null").query(Boolean.class).single()).isTrue();
        var payload = mapper.createObjectNode().put("key", "agent.researcher").put("kind", "AGENT")
                .put("name", "Synthetic researcher").put("content", "Research synthetic inputs").toString();
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_USER"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        var created = mapper.readTree(mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(get(PATH + "/" + created.path("id").asText()).with(authentication(asUser("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        assertThat(created.path("builtIn").asBoolean()).isFalse();
        mvc.perform(get(PATH + "/" + UUID.randomUUID()).with(authentication(asUser("ROLE_ADMIN")))).andExpect(status().isNotFound());
    }

    @Test void multipleAgentAndFunctionPromptsHaveUniquePurposesAndSafeDeletion() throws Exception {
        var writer = defaults.create("agent.writer", PromptService.Kind.AGENT, "Synthetic writer", "Text workflow", "Write synthetic drafts");
        var function = defaults.create("feature.summary", PromptService.Kind.FUNCTION, "Synthetic summary", "Summarization", "Summarize synthetic inputs");
        var project = projects.create(owner.userId(), "Synthetic presets", Project.AspectRatio.LANDSCAPE_16_9);
        String creation = mapper.createObjectNode().put("promptKey", writer.key()).set("bindings", mapper.createArrayNode()).toString();
        var agent = mapper.readTree(mvc.perform(post("/api/v1/projects/" + project.id() + "/agents").with(authentication(asUser("ROLE_USER"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(creation)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(agent.path("name").asText()).isEqualTo(writer.name());
        assertThat(agent.path("instruction").asText()).isEqualTo(writer.content());
        assertThatThrownBy(() -> agents.create(owner.userId(), project.id(), null, null, List.of(), function.key())).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> defaults.create(writer.key(), PromptService.Kind.FUNCTION, "duplicate", "", "content"))
                .isInstanceOf(ApiProblemException.class);
        String presets = mvc.perform(get("/api/v1/agent-presets").with(authentication(asUser("ROLE_USER")))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(presets).contains(writer.key()).doesNotContain(function.key(), writer.content(), "content");
        mvc.perform(get("/api/v1/agent-presets")).andExpect(status().isUnauthorized());
        defaults.update(writer.id(), writer.version(), "Writer edited", writer.description(), "Updated writer prompt");
        assertThatThrownBy(() -> defaults.delete(writer.id(), writer.version())).isInstanceOf(ApiProblemException.class);
        defaults.delete(writer.id(), writer.version() + 1);
        assertThat(agents.get(owner.userId(), project.id(), UUID.fromString(agent.path("id").asText())).instruction()).isEqualTo(writer.content());
        assertThat(defaults.agentPresets()).noneMatch(value -> value.key().equals(writer.key()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(PATH + "/" + function.id())
                .param("expectedVersion", Long.toString(function.version())).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())).andExpect(status().isNoContent());
        var director = defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(PATH + "/" + director.id())
                .param("expectedVersion", Long.toString(director.version())).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())).andExpect(status().isConflict());
    }

    @Test void administratorSettingsRequireCsrfAndCasAndRejectBlankPrompts() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(authentication(asUser("ROLE_USER")))).andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(authentication(asUser("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        var current = defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT);
        String payload = mapper.createObjectNode().put("expectedVersion", current.version())
                .put("name", "Synthetic director").put("content", "Synthetic creative workflow").toString();
        mvc.perform(put(PATH + "/" + defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).id()).with(authentication(asUser("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(put(PATH + "/" + defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).id()).with(authentication(asUser("ROLE_USER"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(put(PATH + "/" + defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).id()).with(authentication(asUser("ROLE_ADMIN"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk());
        mvc.perform(put(PATH + "/" + defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).id()).with(authentication(asUser("ROLE_ADMIN"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        String blank = mapper.createObjectNode().put("expectedVersion", defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version()).put("name", "Synthetic").put("content", " ").toString();
        mvc.perform(put(PATH + "/" + defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).id()).with(authentication(asUser("ROLE_ADMIN"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(blank)).andExpect(status().isBadRequest());
    }

    @Test void defaultsAreCopiedOnceAndCreateReplayDoesNotRecalculateOrDuplicate() throws Exception {
        var project = projects.create(owner.userId(), "Synthetic defaults", Project.AspectRatio.LANDSCAPE_16_9);
        var preset = updateDirector(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version(), "Synthetic director A", "Synthetic workflow A");
        String path = "/api/v1/projects/" + project.id() + "/agents";
        String first = mvc.perform(post(path).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .header("Idempotency-Key", "synthetic-create-director").contentType(MediaType.APPLICATION_JSON).content("{\"bindings\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var original = mapper.readTree(first);
        assertThat(original.path("name").asText()).isEqualTo(preset.name());
        assertThat(original.path("instruction").asText()).isEqualTo(preset.content());
        updateDirector(preset.version(), "Synthetic director B", "Synthetic workflow B");
        String replay = mvc.perform(post(path).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .header("Idempotency-Key", "synthetic-create-director").contentType(MediaType.APPLICATION_JSON).content("{\"bindings\":[]}"))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "true")).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay)).isEqualTo(original);
        assertThat(agents.list(owner.userId(), project.id())).hasSize(1);
        mvc.perform(post(path).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .header("Idempotency-Key", "synthetic-create-director").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\",\"bindings\":[]}")).andExpect(status().isConflict());
        var next = agents.create(owner.userId(), project.id(), null, null, List.of());
        assertThat(next.instruction()).isEqualTo("Synthetic workflow B");
        var edited = agents.update(owner.userId(), project.id(), next.id(), next.version(), "Custom director", "Custom card prompt", List.of());
        updateDirector(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version(), "Synthetic director C", "Synthetic workflow C");
        assertThat(agents.get(owner.userId(), project.id(), edited.id()).instruction()).isEqualTo("Custom card prompt");
        assertThatThrownBy(() -> agents.createIdempotent(UUID.randomUUID(), project.id(), null, null, List.of(), "synthetic-create-director"))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void activeRunFreezesItsCardPromptAndUsesVersionedSystemMessage() {
        var project = projects.create(owner.userId(), "Synthetic frozen director", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = agents.create(owner.userId(), project.id(), "Synthetic frozen director", "Original card workflow", List.of());
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Synthetic video request", "synthetic-frozen-director").run();
        agents.update(owner.userId(), project.id(), agent.id(), agent.version(), agent.name(), "Changed card workflow", List.of());
        updateDirector(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version(), "Global director", "Changed global workflow");
        var messages = contexts.assemble(owner.userId(), project.id(), run.id());
        assertThat(messages.get(2)).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(messages.get(2).getText()).contains("Original card workflow").doesNotContain("Changed card", "Changed global");
        assertThat(run.policySnapshot().path("systemPromptVersion").asInt()).isEqualTo(InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION);
    }
}

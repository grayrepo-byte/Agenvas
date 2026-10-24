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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        if (owner == null) owner = identities.setup("synthetic-director-admin", "synthetic-director-password");
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
        var payload = mapper.createObjectNode().put("key", "feature.analysis")
                .put("name", "Synthetic analysis").put("content", "Analyze synthetic inputs").toString();
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_USER"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        var created = mapper.readTree(mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(get(PATH + "/" + created.path("id").asText()).with(authentication(asUser("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        assertThat(created.path("builtIn").asBoolean()).isFalse();
        assertThat(created.path("kind").asText()).isEqualTo("FUNCTION");
        mvc.perform(get(PATH + "/" + UUID.randomUUID()).with(authentication(asUser("ROLE_ADMIN")))).andExpect(status().isNotFound());
    }

    @Test void functionsHaveUniquePurposesAndCasDeletionWhileTheDirectorCannotBeDeleted() throws Exception {
        var function = defaults.createFunction("feature.summary", "Synthetic summary", "Summarization", "Summarize synthetic inputs");
        assertThat(function.kind()).isEqualTo(PromptService.Kind.FUNCTION);
        assertThat(defaults.list().stream().filter(prompt -> prompt.kind() == PromptService.Kind.AGENT))
                .extracting(PromptService.Prompt::key).containsExactly(PromptService.DIRECTOR_KEY);
        assertThatThrownBy(() -> defaults.createFunction(function.key(), "duplicate", "", "content"))
                .isInstanceOf(ApiProblemException.class);
        var changed = defaults.update(function.id(), function.version(), "Summary edited", function.description(), "Updated summary prompt");
        assertThatThrownBy(() -> defaults.delete(function.id(), function.version())).isInstanceOf(ApiProblemException.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(PATH + "/" + function.id())
                .param("expectedVersion", Long.toString(changed.version())).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())).andExpect(status().isNoContent());
        var director = defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(PATH + "/" + director.id())
                .param("expectedVersion", Long.toString(director.version())).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "preset", "presetKey", "promptKey"})
    void retiredAgentPresetFieldsAreRejectedOnCreationAndUpdate(String field) throws Exception {
        var create = mapper.createObjectNode().put("key", "feature.rejected").put("name", "Synthetic function")
                .put("content", "Synthetic content").put(field, "AGENT");
        mvc.perform(post(PATH).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(create.toString())).andExpect(status().isBadRequest());
        var director = defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT);
        var update = mapper.createObjectNode().put("expectedVersion", director.version()).put("name", director.name())
                .put("content", director.content()).put(field, "AGENT");
        mvc.perform(put(PATH + "/" + director.id()).with(authentication(asUser("ROLE_ADMIN"))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isBadRequest());
        assertThat(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version()).isEqualTo(director.version());
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

    @Test void directorDefaultsOnlyAffectNewProjectsAndDoNotRewriteExistingConfiguration() {
        var project = projects.create(owner.userId(), "Synthetic defaults", Project.AspectRatio.LANDSCAPE_16_9);
        var preset = updateDirector(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version(), "Synthetic director A", "Synthetic workflow A");
        var original = agents.ensureDirector(owner.userId(), project.id());
        assertThat(original.name()).isEqualTo(preset.name());
        assertThat(original.instruction()).isEqualTo(preset.content());
        updateDirector(preset.version(), "Synthetic director B", "Synthetic workflow B");
        assertThat(agents.ensureDirector(owner.userId(), project.id())).isEqualTo(original);
        var nextProject = projects.create(owner.userId(), "Synthetic new defaults", Project.AspectRatio.LANDSCAPE_16_9);
        var next = agents.ensureDirector(owner.userId(), nextProject.id());
        assertThat(next.instruction()).isEqualTo("Synthetic workflow B");
        var edited = agents.update(owner.userId(), nextProject.id(), next.id(), next.version(), "Custom director", "Custom project prompt");
        updateDirector(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version(), "Synthetic director C", "Synthetic workflow C");
        assertThat(agents.ensureDirector(owner.userId(), nextProject.id()).instruction()).isEqualTo("Custom project prompt");
        assertThatThrownBy(() -> agents.ensureDirector(UUID.randomUUID(), project.id()))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void activeRunFreezesItsCardPromptAndUsesVersionedSystemMessage() {
        var project = projects.create(owner.userId(), "Synthetic frozen director", Project.AspectRatio.LANDSCAPE_16_9);
        var agent = dev.agenvas.testing.AgentFixture.configureDirector(agents, owner.userId(), project.id(), "Synthetic frozen director", "Original card workflow");
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Synthetic video request", "synthetic-frozen-director").run();
        agents.update(owner.userId(), project.id(), agent.id(), agent.version(), agent.name(), "Changed card workflow");
        updateDirector(defaults.require(PromptService.DIRECTOR_KEY, PromptService.Kind.AGENT).version(), "Global director", "Changed global workflow");
        var messages = contexts.assemble(owner.userId(), project.id(), run.id());
        assertThat(messages.get(2)).isInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo("Original card workflow");
        assertThat(messages.getFirst().getText()).contains("server permissions", "media approval")
                .doesNotContain("For image-to-video work", "Preserve narrative and visual continuity");
        assertThat(run.policySnapshot().path("systemPromptVersion").asInt()).isEqualTo(InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION);
    }
}

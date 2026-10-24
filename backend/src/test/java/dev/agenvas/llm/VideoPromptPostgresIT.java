package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.DirectTextGenerationWorker;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.task.api.TaskController;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.VideoPromptTaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and a synthetic model; no real Provider calls or private media. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, VideoPromptPostgresIT.FakeConfig.class}, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class VideoPromptPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasService canvas;
    @Autowired VideoPromptTaskService prompts;
    @Autowired PromptService definitions;
    @Autowired SkillService skills;
    @Autowired TaskService tasks;
    @Autowired DirectTextGenerationWorker worker;
    @Autowired LlmProtocolCodec codec;
    @Autowired FakeGateway gateway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    AdminPrincipal owner;
    MockMvc mvc;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("video-prompt-admin", "synthetic-password-123");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        gateway.calls = 0; gateway.reply = "[[REF_1]] walks slowly; the camera tracks left.";
    }
    @Test void freezesSkillAndPromptThenPublishesOnlyTextWithoutChangingTheVideoDraft() {
        var source = source();
        var skill = skills.create(owner.userId(), "Synthetic motion", "Synthetic guide");
        skills.saveDraft(owner.userId(), skill.id(), 0, new SkillContent.DraftContent(1,
                "---\nname: motion-method\ndescription: Synthetic camera guidance\n---\nUse a slow tracking shot.",
                List.of(Artifact.Kind.VIDEO), List.of(), List.of(new SkillContent.Resource("references/motion.md", "Preserve subject identity.")), List.of()));
        var publish = skills.publish(owner.userId(), skill.id(), 1, "publish-method");
        assertThat(skills.processNext()).isTrue();
        var version = skills.getOperation(owner.userId(), publish.id()).resultVersionId();
        var request = new VideoPromptTaskService.Request("[[REF_1]] walks", 20000, new VideoPromptTaskService.SkillRef(skill.id(), version));
        var before = drafts.get(owner.userId(), source.project(), source.item());
        Task accepted = run(source, request, "skill-command");
        var definition = definitions.require(VideoPromptTaskService.PROMPT_KEY, PromptService.Kind.FUNCTION);
        var changed = definitions.update(definition.id(), definition.version(), definition.name(), definition.description(), "Later settings edit");
        var metadata = skills.get(owner.userId(), skill.id());
        skills.updateMetadata(owner.userId(), skill.id(), metadata.version(), metadata.title(), metadata.description(), true);
        assertThat(run(source, request, "skill-command").id()).isEqualTo(accepted.id());
        assertThat(worker.runOnce("video-prompt-worker")).isEqualTo(1);
        var completed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThatThrownBy(() -> run(source, request, "trashed-method")).isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code()).isNotEqualTo("VIDEO_PROMPT_IN_PROGRESS"));
        assertThat(gateway.messages.getFirst().getText()).isEqualTo(definition.content());
        assertThat(gateway.messages.get(1).getText()).contains("Use a slow tracking shot.", "references/motion.md", "Preserve subject identity.");
        var safe = TaskController.TaskResponse.from(completed);
        assertThat(safe.output().path("prompt").asText()).isEqualTo(gateway.reply);
        assertThat(safe.output().path("mock").asBoolean()).isTrue();
        assertThat(safe.input().has("skillContext")).isFalse();
        assertThat(safe.input().path("skillName").asText()).isEqualTo("motion-method");
        assertThat(safe.input().path("skillVersionNumber").asLong()).isEqualTo(1);
        assertThat(safe.input().has("systemPrompt")).isFalse();
        assertThat(safe.output().toString()).doesNotContain("private thought", "response", "request");
        assertThat(drafts.get(owner.userId(), source.project(), source.item())).isEqualTo(before);
        assertThat(artifacts.listVersions(owner.userId(), source.project(), source.artifact())).isEmpty();
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from task_artifact_target where task_id=:id").param("id", accepted.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:id").param("id", accepted.id()).query(Long.class).single()).isEqualTo(2);
        definitions.update(changed.id(), changed.version(), definition.name(), definition.description(), definition.content());
    }
    @Test void serializesConcurrentAcceptanceAndRejectsChangedReplayAndNewActiveCommands() throws Exception {
        var source = source();
        var request = request();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> run(source, request, "same-command"));
            var two = executor.submit(() -> run(source, request, "same-command"));
            assertThat(one.get(10, TimeUnit.SECONDS).id()).isEqualTo(two.get(10, TimeUnit.SECONDS).id());
        }
        assertThat(prompts.list(owner.userId(), source.project(), source.item())).hasSize(1);
        assertThatThrownBy(() -> run(source, new VideoPromptTaskService.Request("Changed", 20000, null), "same-command"))
                .isInstanceOf(ApiProblemException.class).satisfies(error -> assertThat(((ApiProblemException) error).code()).isEqualTo("VIDEO_PROMPT_CONFLICT"));
        assertThatThrownBy(() -> run(source, request, "other-command")).isInstanceOf(ApiProblemException.class);
        Task current = prompts.list(owner.userId(), source.project(), source.item()).getFirst();
        prompts.cancel(owner.userId(), source.project(), source.item(), current.id());
        projects.archive(owner.userId(), source.project(), projects.get(owner.userId(), source.project()).version());
        assertThat(run(source, request, "same-command").id()).isEqualTo(current.id());
        assertThatThrownBy(() -> run(source, request, "archived-project")).isInstanceOf(ApiProblemException.class);
    }
    @Test void recoversTheCheckpointWithoutAnotherCallAndFencesLateCanceledResponses() {
        var source = source();
        var accepted = run(source, request(), "recover");
        var lease = tasks.claimTextGenerations("before-crash", 1).getFirst();
        var checkpoint = mapper.createObjectNode().put("schemaVersion", 1);
        checkpoint.set("request", codec.request(List.of(new UserMessage("Synthetic request")), List.of()));
        checkpoint.set("response", codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage(gateway.reply))))));
        tasks.checkpointTextResponse(lease, "before-crash", checkpoint);
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", lease.id()).update();
        worker.runOnce("after-crash");
        assertThat(tasks.get(owner.userId(), source.project(), accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(gateway.calls).isZero();
        var next = run(source, request(), "cancel");
        var old = tasks.claimTextGenerations("late-worker", 1).getFirst();
        assertThat(prompts.cancel(owner.userId(), source.project(), source.item(), next.id()).status()).isEqualTo(Task.Status.CANCELED);
        assertThat(prompts.cancel(owner.userId(), source.project(), source.item(), next.id()).id()).isEqualTo(next.id());
        assertThatThrownBy(() -> tasks.checkpointTextResponse(old, "late-worker", checkpoint)).isInstanceOf(ApiProblemException.class);
        tasks.succeed(old, "late-worker", checkpoint);
        assertThat(TaskController.TaskResponse.from(tasks.get(owner.userId(), source.project(), next.id())).output()).isNull();
    }
    @Test void rejectsModelOutputThatDropsAReference() {
        var source = source();
        var accepted = run(source, request(), "invalid-output");
        gateway.reply = "Missing reference";
        worker.runOnce("invalid-worker");
        var failed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(TaskController.TaskResponse.from(failed).output()).isNull();
    }
    @Test void enforcesHttpAuthorizationCsrfRequestContractAndProjectBoundary() throws Exception {
        var source = source();
        String path = path(source);
        String body = mapper.writeValueAsString(request());
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, "unused", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(auth).header("Idempotency-Key", "http").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(auth).with(csrf()).header("Idempotency-Key", "invalid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"Test\"}")).andExpect(status().isBadRequest());
        var another = source();
        mvc.perform(get(path.replace(source.project().toString(), another.project().toString())).with(auth)).andExpect(status().isNotFound());
        mvc.perform(post(path).with(auth).with(csrf()).header("Idempotency-Key", "http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.input.systemPrompt").doesNotExist()).andExpect(jsonPath("$.output").isEmpty());
        Task accepted = prompts.list(owner.userId(), source.project(), source.item()).getFirst();
        mvc.perform(post(path + "/" + accepted.id() + "/cancel").with(auth).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        var outsider = new AdminPrincipal(UUID.randomUUID(), "outsider");
        assertThatThrownBy(() -> prompts.list(outsider.userId(), source.project(), source.item())).isInstanceOf(ApiProblemException.class);
    }
    @Test void upgradesV39WithoutChangingExistingPromptEdits() throws Exception {
        jdbc.sql("create database video_prompt_upgrade").update();
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/video_prompt_upgrade";
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("39")).load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.executeUpdate("update prompt_definition set content='Synthetic existing edit' where key='image.reverse-prompt'");
        }
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement();
                var rows = statement.executeQuery("select key,content from prompt_definition where key in ('image.reverse-prompt','video.optimize-prompt') order by key")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString("content")).isEqualTo("Synthetic existing edit");
            assertThat(rows.next()).isTrue(); assertThat(rows.getString("key")).isEqualTo(VideoPromptTaskService.PROMPT_KEY);
        }
    }
    private VideoPromptTaskService.Request request() { return new VideoPromptTaskService.Request("[[REF_1]] walks", 20000, null); }
    private Task run(Source source, VideoPromptTaskService.Request request, String key) {
        return prompts.run(owner.userId(), source.project(), source.item(), request, "zh", key);
    }
    private Source source() {
        var project = projects.create(owner.userId(), "Synthetic video prompt", Project.AspectRatio.LANDSCAPE_16_9);
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Synthetic video", null);
        UUID item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.artifact().id());
        return new Source(project.id(), artifact.artifact().id(), item);
    }
    private String path(Source source) { return "/api/v1/projects/" + source.project() + "/canvas-items/" + source.item() + "/video-prompt-optimizations"; }
    record Source(UUID project, UUID artifact, UUID item) {}
    @TestConfiguration static class FakeConfig { @Bean @Primary FakeGateway gateway() { return new FakeGateway(); } }
    static class FakeGateway implements ChatGateway {
        int calls; String reply; List<Message> messages;
        public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(tools).isEmpty(); assertThat(context).isEmpty();
            this.messages = messages; calls++;
            return new Exchange(1, new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(reply)
                    .properties(Map.of("reasoning_content", "private thought")).build()))));
        }
        public Capabilities capabilities() { return new Capabilities(true, false, false); }
        public int configVersion() { return 1; }
        public String configSource() { return "mock"; }
        public ModelDetails modelDetails() { return new ModelDetails(true, "synthetic", "synthetic-text", true); }
    }
}

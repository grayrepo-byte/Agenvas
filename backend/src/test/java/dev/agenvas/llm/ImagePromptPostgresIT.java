package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.DirectTextGenerationWorker;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.api.TaskController;
import dev.agenvas.task.application.ImagePromptTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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

/** Real PostgreSQL, synthetic archived image and fake model: no real Provider or private materials. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, ImagePromptPostgresIT.FakeConfig.class}, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class ImagePromptPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE = temporaryRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE::toString);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired ImagePromptTaskService prompts;
    @Autowired PromptService definitions;
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
        if (owner == null) owner = identities.setup("image-prompt-admin", "synthetic-password-123");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        gateway.failure = false; gateway.calls = 0;
    }

    @Test void freezesInputsSendsAnImageAndPublishesOnlyThePromptWithoutChangingTheSource() throws Exception {
        var source = source();
        var definition = definitions.require(ImagePromptTaskService.PROMPT_KEY, PromptService.Kind.FUNCTION);
        assertThat(definition.builtIn()).isTrue();
        Task accepted = run(source, "first");
        assertThat(prompts.run(owner.userId(), source.project(), source.artifact(), source.item().id(), source.version(),
                source.item().version(), "en", "first").id()).isEqualTo(accepted.id());
        assertThatThrownBy(() -> run(source, "another-command")).isInstanceOf(ApiProblemException.class)
                .satisfies(failure -> assertThat(((ApiProblemException) failure).code()).isEqualTo("IMAGE_PROMPT_IN_PROGRESS"));
        assertThat(accepted.input().path("language").asText()).isEqualTo("zh");
        var changed = definitions.update(definition.id(), definition.version(), definition.name(), definition.description(),
                "A later creative prompt that must not affect existing work.");
        assertThat(accepted.input().path("systemPrompt").asText()).isEqualTo(definition.content());
        assertThatThrownBy(() -> prompts.run(owner.userId(), source.project(), source.artifact(), source.item().id(),
                UUID.randomUUID(), source.item().version(), "zh", "first")).isInstanceOf(ApiProblemException.class);
        assertThat(worker.runOnce("image-prompt-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(gateway.messages.getFirst().getText()).isEqualTo(definition.content());
        assertThat(gateway.messages.get(1).getText()).contains("Chinese", "untrusted");
        var publicResult = TaskController.TaskResponse.from(completed);
        assertThat(publicResult.output().path("prompt").asText()).isEqualTo("Synthetic lakeside, soft morning light.");
        assertThat(publicResult.output().path("mock").asBoolean()).isTrue();
        assertThat(publicResult.input().has("systemPrompt")).isFalse();
        assertThat(publicResult.output().toString()).doesNotContain("response", "private thought", "request");
        assertThat(completed.output().path("request").toString()).doesNotContain("data:", "mediaType", "image/png");
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        assertThat(canvas.list(owner.userId(), source.project()).getFirst().item().selectedVersionId()).isEqualTo(source.version());
        assertThat(artifacts.listVersions(owner.userId(), source.project(), source.artifact())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from task_artifact_target where task_id=:id").param("id", accepted.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:id").param("id", accepted.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id())).hasSize(1);
        definitions.update(changed.id(), changed.version(), definition.name(), definition.description(), definition.content());
    }

    @Test void restoresACheckpointWithoutAnotherModelCallAndFencesCancellation() throws Exception {
        var source = source();
        Task accepted = run(source, "recover");
        Task lease = tasks.claimTextGenerations("before-crash", 1).getFirst();
        assertThat(lease.id()).isEqualTo(accepted.id());
        var checkpoint = mapper.createObjectNode().put("schemaVersion", 1);
        checkpoint.set("request", codec.request(List.of(new UserMessage("Synthetic immutable image reference")), List.of()));
        checkpoint.set("response", codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("Recovered prompt"))))));
        tasks.checkpointTextResponse(lease, "before-crash", checkpoint);
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", lease.id()).update();
        worker.runOnce("after-crash");
        Task restored = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(restored.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(restored.output().path("result").path("prompt").asText()).isEqualTo("Recovered prompt");
        assertThat(gateway.calls).isZero();

        Task next = run(source, "cancel-running");
        Task oldLease = tasks.claimTextGenerations("cancel-worker", 1).getFirst();
        Task canceled = prompts.cancel(owner.userId(), source.project(), source.artifact(), next.id());
        assertThat(canceled.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(prompts.cancel(owner.userId(), source.project(), source.artifact(), next.id()).id()).isEqualTo(next.id());
        assertThatThrownBy(() -> tasks.checkpointTextResponse(oldLease, "cancel-worker", checkpoint)).isInstanceOf(ApiProblemException.class);
        tasks.succeed(oldLease, "cancel-worker", checkpoint);
        assertThat(TaskController.TaskResponse.from(tasks.get(owner.userId(), source.project(), next.id())).output()).isNull();
        Task queued = run(source, "cancel-queued");
        assertThat(prompts.cancel(owner.userId(), source.project(), source.artifact(), queued.id()).status()).isEqualTo(Task.Status.CANCELED);
    }

    @Test void enforcesHttpAuthorizationCsrfSourceCasAndSafeTaskProjection() throws Exception {
        var source = source();
        String path = path(source);
        String body = mapper.createObjectNode().put("canvasItemId", source.item().id().toString())
                .put("sourceVersionId", source.version().toString()).put("expectedCanvasItemVersion", source.item().version()).toString();
        mvc.perform(get(path).param("canvasItemId", source.item().id().toString())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(authentication(auth(owner))).header("Idempotency-Key", "http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var outsider = new AdminPrincipal(UUID.randomUUID(), "outsider");
        mvc.perform(get(path).with(authentication(auth(outsider))).param("canvasItemId", source.item().id().toString())).andExpect(status().isNotFound());
        mvc.perform(post(path).with(authentication(auth(owner))).with(csrf()).header("Idempotency-Key", "invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("expectedCanvasItemVersion", "missingVersion"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(authentication(auth(owner))).with(csrf()).header("Idempotency-Key", "http")
                .header("Accept-Language", "ja").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.input.language").value("ja")).andExpect(jsonPath("$.input.systemPrompt").doesNotExist());
        Task accepted = prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id()).getFirst();
        mvc.perform(get("/api/v1/projects/" + source.project() + "/tasks/" + accepted.id()).with(authentication(auth(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.output").isEmpty());
        mvc.perform(post(path + "/" + accepted.id() + "/cancel").with(authentication(auth(owner))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        assertThatThrownBy(() -> prompts.run(owner.userId(), source.project(), source.artifact(), source.item().id(),
                source.version(), source.item().version() + 1, "zh", "stale")).isInstanceOf(ApiProblemException.class);
        var other = source();
        assertThatThrownBy(() -> prompts.run(owner.userId(), source.project(), source.artifact(), source.item().id(),
                other.version(), source.item().version(), "zh", "cross-project")).isInstanceOf(ApiProblemException.class);
        assertThat(gateway.calls).isZero();
    }

    @Test void refusesNewWorkForArchivedProjectsWhileRetainingExistingTaskHistory() throws Exception {
        var source = source();
        Task prior = run(source, "before-archive");
        prompts.cancel(owner.userId(), source.project(), source.artifact(), prior.id());
        projects.archive(owner.userId(), source.project(), projects.get(owner.userId(), source.project()).version());
        assertThatThrownBy(() -> run(source, "after-archive")).isInstanceOf(ApiProblemException.class);
        assertThat(run(source, "before-archive").id()).isEqualTo(prior.id());
        assertThat(prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id())).hasSize(1);
        assertThat(gateway.calls).isZero();
    }

    @Test void concurrentCommandsShareOneActiveTaskAndModelFailureCreatesNoOutput() throws Exception {
        var source = source();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> run(source, "concurrent-same-command"));
            var second = executor.submit(() -> run(source, "concurrent-same-command"));
            assertThat(first.get(10, TimeUnit.SECONDS).id()).isEqualTo(second.get(10, TimeUnit.SECONDS).id());
        }
        assertThat(prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id())).hasSize(1);
        gateway.failure = true;
        worker.runOnce("failure-worker");
        var failed = prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id()).getFirst();
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(TaskController.TaskResponse.from(failed).output()).isNull();
        assertThat(artifacts.listVersions(owner.userId(), source.project(), source.artifact())).hasSize(1);
    }

    @Test void upgradesFromV23WithoutOverwritingExistingCreativePrompts() throws Exception {
        jdbc.sql("create database image_prompt_upgrade").update();
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/image_prompt_upgrade";
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("23")).load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("update prompt_definition set content='Synthetic administrator edit' where key='text.generate'");
        }
        var upgraded = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        assertThat(upgraded.migrationsExecuted).isEqualTo(1);
        try (var connection = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select key,content,built_in from prompt_definition where key in ('text.generate','image.reverse-prompt') order by key")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString("key")).isEqualTo(ImagePromptTaskService.PROMPT_KEY);
            assertThat(rows.getBoolean("built_in")).isTrue();
            assertThat(rows.next()).isTrue(); assertThat(rows.getString("content")).isEqualTo("Synthetic administrator edit");
            assertThat(rows.next()).isFalse();
        }
    }

    private Task run(Source source, String key) {
        return prompts.run(owner.userId(), source.project(), source.artifact(), source.item().id(), source.version(),
                source.item().version(), "zh", key);
    }
    private Source source() throws Exception {
        var project = projects.create(owner.userId(), "Synthetic image prompt", Project.AspectRatio.LANDSCAPE_16_9);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", png);
        var asset = assets.archiveImage(owner.userId(), project.id(), new ByteArrayInputStream(png.toByteArray()));
        var artifact = artifacts.createTemplateImport(owner.userId(), project.id(), "Synthetic image", asset.id());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(UUID.randomUUID(), artifact.artifact().id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false)));
        return new Source(project.id(), artifact.artifact().id(), artifact.resourceDefaultVersion().id(), canvas.list(owner.userId(), project.id()).getFirst().item());
    }
    private String path(Source source) { return "/api/v1/projects/" + source.project() + "/artifacts/" + source.artifact() + "/image-prompts"; }
    private UsernamePasswordAuthenticationToken auth(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, "unused", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
    private record Source(UUID project, UUID artifact, UUID version, CanvasItem item) {}
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-image-prompt-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway gateway() { return new FakeGateway(); }
    }
    static class FakeGateway implements ChatGateway {
        int calls;
        boolean failure;
        List<Message> messages;
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(tools).isEmpty(); assertThat(context).isEmpty();
            this.messages = messages; calls++;
            var user = (UserMessage) messages.getLast();
            assertThat(user.getMedia()).hasSize(1);
            assertThat(user.getMedia().getFirst().getMimeType().toString()).isEqualTo("image/png");
            assertThat(user.getMedia().getFirst().getDataAsByteArray()).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
            if (failure) throw new IllegalStateException("Synthetic transport failure");
            return new Exchange(1, new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("Synthetic lakeside, soft morning light.").properties(Map.of("reasoning_content", "private thought")).build()))));
        }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public int configVersion() { return 1; }
        @Override public String configSource() { return "mock"; }
        @Override public ModelDetails modelDetails() { return new ModelDetails(true, "synthetic", "synthetic-vision", true); }
    }
}

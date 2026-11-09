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
import dev.agenvas.llm.application.LlmProperties;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmRequestBudget;
import dev.agenvas.llm.infrastructure.ConfiguredChatGateway;
import dev.agenvas.llm.infrastructure.StoredChatModelFactory;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.settings.application.LlmModelPolicy;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import dev.agenvas.settings.application.LlmProviderConfigService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.api.TaskController;
import dev.agenvas.task.application.ImagePromptTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.MigrationVersions;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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

/** Real PostgreSQL and synthetic images/responses, including the SDK against local HTTP; no real Provider or private materials. */
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
        registry.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired dev.agenvas.canvas.application.CanvasConnectionService connections;
    @Autowired ImagePromptTaskService prompts;
    @Autowired PromptService definitions;
    @Autowired TaskService tasks;
    @Autowired dev.agenvas.task.application.TaskRepository taskRepository;
    @Autowired dev.agenvas.usage.application.UsageService usage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired DirectTextGenerationWorker worker;
    @Autowired LlmProtocolCodec codec;
    @Autowired FakeGateway gateway;
    @Autowired LlmProviderConfigService configs;
    @Autowired LlmProviderConfigRepository configRepository;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    AdminPrincipal owner;
    MockMvc mvc;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("image-prompt-admin", "synthetic-password-123");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        gateway.failure = false; gateway.calls = 0; gateway.useStoredConfig = false;
    }

    @Test void acceptsLegacyConnectionWithFrozenDefaultBudgetAndPublishesItsOriginalImagePrompt() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var sent = new java.util.concurrent.atomic.AtomicReference<String>();
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            sent.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"synthetic-image-prompt\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"synthetic-vision\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Synthetic legacy image prompt.\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4,\"total_tokens\":9}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        http.start();
        try {
            var saved = configs.replace(configs.status().version(), "http://127.0.0.1:" + http.getAddress().getPort() + "/v1",
                    "synthetic-vision", "synthetic-policy-key");
            var legacy = configRepository.findVersion(saved.version()).orElseThrow();
            assertThat(legacy.modelPolicy()).isNull();
            gateway.useStoredConfig = true;
            var source = source();
            String body = mapper.createObjectNode().put("canvasItemId", source.item().id().toString())
                    .put("sourceVersionId", source.version().toString()).put("expectedCanvasItemVersion", source.item().version()).toString();
            mvc.perform(post(path(source)).with(authentication(auth(owner))).with(csrf()).header("Idempotency-Key", "legacy-capacity")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            var accepted = prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id()).getFirst();
            var frozenInput = accepted.input().deepCopy();
            assertThat(accepted.input().path("modelConfigSource").asText()).isEqualTo("stored");
            assertThat(accepted.input().path("modelConfigVersion").asInt()).isEqualTo(saved.version());
            var budget = codec.requestBudget(accepted.input());
            assertThat(budget.contextWindowTokens()).isEqualTo(32768);
            assertThat(budget.responseReserveTokens()).isEqualTo(4096);
            assertThat(budget.inputBudget()).isEqualTo(28672);
            assertThat(budget.outputParameter()).isEqualTo(LlmModelPolicy.OutputParameter.MAX_TOKENS);

            assertThat(worker.runOnce("legacy-capacity-worker")).isEqualTo(1);
            var completed = tasks.get(owner.userId(), source.project(), accepted.id());
            assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(completed.input()).isEqualTo(frozenInput);
            assertThat(codec.requestBudget(completed.output().path("request"))).isEqualTo(budget);
            assertThat(requests).hasValue(1);
            var wire = mapper.readTree(sent.get());
            assertThat(wire.path("max_tokens").asInt()).isEqualTo(4096);
            assertThat(wire.has("max_completion_tokens")).isFalse();
            var content = wire.path("messages").get(wire.path("messages").size() - 1).path("content");
            String imageUrl = java.util.stream.StreamSupport.stream(content.spliterator(), false)
                    .filter(part -> "image_url".equals(part.path("type").asText()))
                    .findFirst().orElseThrow().path("image_url").path("url").asText();
            assertThat(imageUrl).startsWith("data:image/png;base64,");
            assertThat(Base64.getDecoder().decode(imageUrl.substring(imageUrl.indexOf(',') + 1))).isEqualTo(source.bytes());
            var result = TaskController.TaskResponse.from(completed).output();
            assertThat(result.path("prompt").asText()).isEqualTo("Synthetic legacy image prompt.");
            assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
            assertThat(artifacts.get(owner.userId(), source.project(), UUID.fromString(result.path("artifactId").asText()))
                    .resourceDefaultVersion().content().path("text").asText()).isEqualTo("Synthetic legacy image prompt.");
            assertThat(configRepository.findVersion(saved.version()).orElseThrow()).usingRecursiveComparison().isEqualTo(legacy);
            assertThat(configs.status().version()).isEqualTo(saved.version());
            assertThat(configs.status().modelPolicy()).isNull();
        } finally { http.stop(0); }
    }

    @Test void freezesInputsAndPublishesOneTextCardWithoutChangingTheSource() throws Exception {
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
        byte[] sent = ((UserMessage) gateway.messages.getLast()).getMedia().getFirst().getDataAsByteArray();
        assertThat(sent).isEqualTo(source.bytes());
        var decoded = ImageIO.read(new ByteArrayInputStream(sent));
        assertThat(decoded.getWidth()).isEqualTo(1600); assertThat(decoded.getHeight()).isEqualTo(900);
        var publicResult = TaskController.TaskResponse.from(completed);
        assertThat(publicResult.output().path("prompt").asText()).isEqualTo("Synthetic lakeside, soft morning light.");
        assertThat(publicResult.output().path("mock").asBoolean()).isTrue();
        assertThat(publicResult.input().has("systemPrompt")).isFalse();
        assertThat(publicResult.output().toString()).doesNotContain("response", "private thought", "request");
        assertThat(completed.output().path("request").toString()).doesNotContain("data:", "mediaType", "image/png");
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        var resultItem = canvas.list(owner.userId(), source.project()).stream()
                .filter(entry -> !entry.item().id().equals(source.item().id())).findFirst().orElseThrow();
        assertThat(resultItem.artifact().artifact().kind()).isEqualTo(dev.agenvas.artifact.domain.Artifact.Kind.TEXT);
        assertThat(resultItem.item().x()).isEqualByComparingTo("344");
        assertThat(resultItem.item().y()).isEqualByComparingTo("0");
        assertThat(connections.list(owner.userId(), source.project())).singleElement().satisfies(connection -> {
            assertThat(connection.relationType()).isEqualTo(dev.agenvas.canvas.domain.CanvasConnection.RelationType.MEDIA_DERIVATION);
            assertThat(connection.sourceCanvasItemId()).isEqualTo(source.item().id());
            assertThat(connection.targetCanvasItemId()).isEqualTo(resultItem.item().id());
            assertThat(connection.sourceArtifactVersionId()).isEqualTo(source.version());
        });
        var text = artifacts.get(owner.userId(), source.project(), resultItem.artifact().artifact().id());
        assertThat(text.resourceDefaultVersion().content().path("text").asText()).isEqualTo("Synthetic lakeside, soft morning light.");
        assertThat(text.resourceDefaultVersion().createdByKind()).isEqualTo(dev.agenvas.artifact.domain.ArtifactVersion.CreatedByKind.TASK);
        assertThat(text.artifact().title()).contains("Mock");
        assertThat(publicResult.output().path("artifactId").asText()).isEqualTo(text.artifact().id().toString());
        assertThat(publicResult.output().path("artifactVersionId").asText()).isEqualTo(text.resourceDefaultVersion().id().toString());
        assertThat(publicResult.output().path("canvasItemId").asText()).isEqualTo(resultItem.item().id().toString());
        assertThat(run(source, "first").id()).isEqualTo(accepted.id());
        assertThat(worker.runOnce("replay-worker")).isZero();
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        assertThat(canvas.list(owner.userId(), source.project()).stream().filter(entry -> entry.item().id().equals(source.item().id())).findFirst().orElseThrow().item()).isEqualTo(source.item());
        assertThat(artifacts.listVersions(owner.userId(), source.project(), source.artifact())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from task_artifact_target where task_id=:id").param("id", accepted.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:id").param("id", accepted.id()).query(Long.class).single()).isEqualTo(2);
        assertThat(prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id())).hasSize(1);
        definitions.update(changed.id(), changed.version(), definition.name(), definition.description(), definition.content());
    }

    @Test void placesTextBesideTheCurrentSourceAndAvoidsOccupiedSpace() throws Exception {
        var source = source();
        var accepted = run(source, "near-source");
        var blocker = artifacts.create(owner.userId(), source.project(), dev.agenvas.artifact.domain.Artifact.Kind.TEXT,
                "Existing text", mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Existing work"));
        canvas.apply(owner.userId(), source.project(), List.of(
                new CanvasService.UpdateLayout(source.item().id(), source.item().version(), new BigDecimal("1200"),
                        new BigDecimal("900"), source.item().width(), source.item().height(), 0, null),
                new CanvasService.PlaceArtifact(UUID.randomUUID(), blocker.artifact().id(), new BigDecimal("1544"),
                        new BigDecimal("900"), new BigDecimal("300"), new BigDecimal("300"), 1, null, false),
                new CanvasService.PlaceArtifact(UUID.randomUUID(), blocker.artifact().id(), new BigDecimal("10000"),
                        BigDecimal.ZERO, new BigDecimal("300"), new BigDecimal("300"), 2, null, false)));
        worker.runOnce("near-source-worker");
        var completed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        var resultId = UUID.fromString(TaskController.TaskResponse.from(completed).output().path("canvasItemId").asText());
        var result = canvas.list(owner.userId(), source.project()).stream()
                .filter(entry -> entry.item().id().equals(resultId)).findFirst().orElseThrow().item();
        assertThat(result.x()).isEqualByComparingTo("1544");
        assertThat(result.y()).isEqualByComparingTo("1224");
        assertThat(connections.list(owner.userId(), source.project())).singleElement()
                .satisfies(connection -> assertThat(connection.targetCanvasItemId()).isEqualTo(resultId));
    }

    @Test void publishesAcceptedTextAndLineageAfterProjectArchival() throws Exception {
        var source = source();
        var accepted = run(source, "archived-source");
        projects.archive(owner.userId(), source.project(), projects.get(owner.userId(), source.project()).version());
        worker.runOnce("archived-source-worker");
        assertThat(tasks.get(owner.userId(), source.project(), accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        assertThat(connections.list(owner.userId(), source.project())).hasSize(1);
    }

    @Test void dispatchKeepsPinnedOriginalAfterANewerVersionIsAddedAndNeedsNoThumbnail() throws Exception {
        var source = source();
        Task accepted = run(source, "pinned-original");
        byte[] newer = dev.agenvas.testing.ImageAssetFixture.encoded("png", 600, 800);
        var asset = assets.archiveImage(owner.userId(), source.project(), new ByteArrayInputStream(newer));
        var content = mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString());
        canvas.uploadVersion(owner.userId(), source.project(), source.item().id(), UUID.randomUUID(),
                source.item().version(), content, null, null);
        var original = assets.metadata(owner.userId(), source.project(), source.asset());
        Files.delete(STORAGE.resolve(original.thumbnailKey()));
        worker.runOnce("pinned-original-worker");
        Task completed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("result").path("sourceVersionId").asText()).isEqualTo(source.version().toString());
        assertThat(((UserMessage) gateway.messages.getLast()).getMedia().getFirst().getDataAsByteArray()).isEqualTo(source.bytes());
        assertThat(artifacts.listVersions(owner.userId(), source.project(), source.artifact())).hasSize(2);
        var textItem = UUID.fromString(TaskController.TaskResponse.from(completed).output().path("canvasItemId").asText());
        assertThat(connections.list(owner.userId(), source.project()).stream()
                .filter(connection -> connection.targetCanvasItemId().equals(textItem))).singleElement()
                .satisfies(connection -> assertThat(connection.sourceArtifactVersionId()).isEqualTo(source.version()));
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test void missingOriginalFailsClearlyWithoutSendingItsAvailableThumbnail() throws Exception {
        var source = source();
        Task accepted = run(source, "missing-original");
        var original = assets.metadata(owner.userId(), source.project(), source.asset());
        assertThat(STORAGE.resolve(original.thumbnailKey())).exists();
        Files.delete(STORAGE.resolve(original.objectKey()));
        worker.runOnce("missing-original-worker");
        Task failed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("IMAGE_PROMPT_INPUT_UNAVAILABLE");
        assertThat(TaskController.TaskResponse.from(failed).output()).isNull();
        assertThat(gateway.calls).isZero();
    }

    @Test void rejectsAnimationBeforeModelCallAndReleasesReservedUsageWithoutAnOutput() throws Exception {
        var source = source(dev.agenvas.testing.GifFixture.animation(2));
        Task accepted = run(source, "animated-original");
        worker.runOnce("animated-original-worker");
        Task failed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(failed.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("IMAGE_PROMPT_INPUT_UNSUPPORTED");
        assertThat(TaskController.TaskResponse.from(failed).output()).isNull();
        assertThat(gateway.calls).isZero();
        assertThat(artifacts.listVersions(owner.userId(), source.project(), source.artifact())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from usage_ledger where task_id=:id and entry_type='RELEASE'")
                .param("id", accepted.id()).query(Long.class).single()).isEqualTo(1);
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
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        assertThat(restored.output().path("result").path("canvasItemId").asText()).isNotBlank();

        Task next = run(source, "cancel-running");
        Task oldLease = tasks.claimTextGenerations("cancel-worker", 1).getFirst();
        Task canceled = prompts.cancel(owner.userId(), source.project(), source.artifact(), next.id());
        assertThat(canceled.status()).isEqualTo(Task.Status.CANCELED);
        assertThat(prompts.cancel(owner.userId(), source.project(), source.artifact(), next.id()).id()).isEqualTo(next.id());
        assertThatThrownBy(() -> tasks.checkpointTextResponse(oldLease, "cancel-worker", checkpoint)).isInstanceOf(ApiProblemException.class);
        tasks.succeed(oldLease, "cancel-worker", checkpoint);
        assertThat(TaskController.TaskResponse.from(tasks.get(owner.userId(), source.project(), next.id())).output()).isNull();
        assertThatThrownBy(() -> tasks.succeedWithImagePromptCard(oldLease, "cancel-worker", checkpoint,
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Late text")))
                .isInstanceOf(ApiProblemException.class);
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        Task queued = run(source, "cancel-queued");
        assertThat(prompts.cancel(owner.userId(), source.project(), source.artifact(), queued.id()).status()).isEqualTo(Task.Status.CANCELED);
        // Replaying an older command or cancellation does not promote it in current history.
        Task replay = run(source, "recover");
        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(replay.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(replay.version()).isGreaterThan(accepted.version());
        assertThat(prompts.cancel(owner.userId(), source.project(), source.artifact(), next.id()).id()).isEqualTo(next.id());
        assertThat(prompts.list(owner.userId(), source.project(), source.artifact(), source.item().id()))
                .extracting(Task::id).containsExactly(queued.id(), next.id(), accepted.id());
        assertThat(gateway.calls).isZero();
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
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
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
        assertThat(upgraded.migrationsExecuted).isEqualTo(MigrationVersions.sorted().size() - 23);
        try (var connection = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("select key,content,built_in from prompt_definition where key in ('text.generate','image.reverse-prompt') order by key")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getString("key")).isEqualTo(ImagePromptTaskService.PROMPT_KEY);
            assertThat(rows.getBoolean("built_in")).isTrue();
            assertThat(rows.next()).isTrue(); assertThat(rows.getString("content")).isEqualTo("Synthetic administrator edit");
            assertThat(rows.next()).isFalse();
        }
    }

    @Test void explicitRerunCreatesANewIndependentCardAndOldResultRemainsEditable() throws Exception {
        var source = source();
        var first = run(source, "first-card");
        worker.runOnce("first-card-worker");
        var firstOutput = TaskController.TaskResponse.from(tasks.get(owner.userId(), source.project(), first.id())).output();
        var artifactId = UUID.fromString(firstOutput.path("artifactId").asText());
        var original = artifacts.get(owner.userId(), source.project(), artifactId);
        artifacts.revise(owner.userId(), source.project(), artifactId, original.artifact().version(),
                original.artifact().title(), mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "User edited prompt"));
        run(source, "second-card");
        worker.runOnce("second-card-worker");
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(3);
        assertThat(artifacts.get(owner.userId(), source.project(), artifactId).resourceDefaultVersion().content().path("text").asText())
                .isEqualTo("User edited prompt");
        assertThat(run(source, "first-card").id()).isEqualTo(first.id());
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(3);
        assertThat(connections.list(owner.userId(), source.project())).hasSize(2);
        assertThat(gateway.calls).isEqualTo(2);
    }

    @Test void concurrentResultPublicationCreatesExactlyOneCard() throws Exception {
        var source = source();
        var accepted = run(source, "concurrent-publication");
        var lease = tasks.claimTextGenerations("publication-worker", 1).getFirst();
        var output = mapper.createObjectNode().put("schemaVersion", 1);
        output.set("response", codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("One saved prompt"))))));
        tasks.checkpointTextResponse(lease, "publication-worker", output);
        output.putObject("result").put("prompt", "One saved prompt").put("mock", true).put("sourceVersionId", source.version().toString());
        var content = mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "One saved prompt");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publish = (java.util.concurrent.Callable<Boolean>) () -> {
                try { tasks.succeedWithImagePromptCard(lease, "publication-worker", output, content); return true; }
                catch (ApiProblemException fenced) { assertThat(fenced.code()).isEqualTo("TASK_LEASE_LOST"); return false; }
            };
            var first = executor.submit(publish); var second = executor.submit(publish);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(tasks.get(owner.userId(), source.project(), accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        assertThat(jdbc.sql("select count(*) from artifact where project_id=:project and kind='TEXT'").param("project", source.project()).query(Long.class).single()).isEqualTo(1);
        assertThat(connections.list(owner.userId(), source.project())).hasSize(1);
        assertThat(gateway.calls).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"canvas_item", "canvas_connection"})
    void publicationFailureRollsBackAndRecoversCheckpointWithoutAnotherModelCall(String rejectedTable) throws Exception {
        var source = source();
        var accepted = run(source, "publication-recovery");
        jdbc.sql("create function synthetic_reject_prompt_card() returns trigger language plpgsql as $$ begin raise exception 'Synthetic placement failure'; end $$").update();
        jdbc.sql("create trigger synthetic_reject_prompt_card before insert on " + rejectedTable + " for each row execute function synthetic_reject_prompt_card()").update();
        try {
            worker.runOnce("publication-failure-worker");
            var deferred = tasks.get(owner.userId(), source.project(), accepted.id());
            assertThat(deferred.status()).isEqualTo(Task.Status.RUNNING);
            assertThat(deferred.output().has("response")).isTrue();
            assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
            assertThat(connections.list(owner.userId(), source.project())).isEmpty();
            assertThat(jdbc.sql("select count(*) from artifact where project_id=:project").param("project", source.project()).query(Long.class).single()).isEqualTo(1);
        } finally {
            jdbc.sql("drop trigger synthetic_reject_prompt_card on " + rejectedTable).update();
            jdbc.sql("drop function synthetic_reject_prompt_card()").update();
        }
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", accepted.id()).update();
        worker.runOnce("publication-recovery-worker");
        assertThat(tasks.get(owner.userId(), source.project(), accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(2);
        assertThat(connections.list(owner.userId(), source.project())).hasSize(1);
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test void checkpointedResultSurvivesSourceRemovalAndProjectArchival() throws Exception {
        var source = source();
        var accepted = run(source, "removed-source");
        var lease = tasks.claimTextGenerations("removed-source-worker", 1).getFirst();
        var checkpoint = mapper.createObjectNode().put("schemaVersion", 1);
        checkpoint.set("response", codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("Retained prompt"))))));
        tasks.checkpointTextResponse(lease, "removed-source-worker", checkpoint);
        canvas.apply(owner.userId(), source.project(), List.of(new CanvasService.Remove(source.item().id(), source.item().version())));
        projects.archive(owner.userId(), source.project(), projects.get(owner.userId(), source.project()).version());
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=:id").param("id", lease.id()).update();
        worker.runOnce("retained-result-worker");
        var completed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        assertThat(canvas.list(owner.userId(), source.project()).getFirst().artifact().resourceDefaultVersion().content().path("text").asText())
                .isEqualTo("Retained prompt");
        assertThat(connections.list(owner.userId(), source.project())).isEmpty();
        assertThat(gateway.calls).isZero();
    }

    @Test void legacySchemaThreeStillExecutesWithoutCreatingCards() throws Exception {
        var source = source();
        var template = run(source, "template");
        prompts.cancel(owner.userId(), source.project(), source.artifact(), template.id());
        var input = (tools.jackson.databind.node.ObjectNode) template.input().deepCopy();
        input.put("schemaVersion", 3); input.remove("outputTitle");
        var now = java.time.Instant.now();
        Task accepted = new Task(UUID.randomUUID(), source.project(), null, "legacy", Task.Kind.TEXT_GENERATION,
                Task.Status.READY, false, input, dev.agenvas.shared.crypto.Sha256.hex(input.toString()),
                null, null, 1, now, null, null, 0, 0, null, now, now, null);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            taskRepository.create(accepted); usage.reserveDirectTextTask(owner.userId(), accepted);
        });
        worker.runOnce("legacy-worker");
        var completed = tasks.get(owner.userId(), source.project(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(TaskController.TaskResponse.from(completed).output().path("prompt").asText()).isNotBlank();
        assertThat(TaskController.TaskResponse.from(completed).output().has("canvasItemId")).isFalse();
        assertThat(canvas.list(owner.userId(), source.project())).hasSize(1);
        assertThat(run(source, "legacy").id()).isEqualTo(accepted.id());
        assertThat(gateway.calls).isEqualTo(1);
    }

    private Task run(Source source, String key) {
        return prompts.run(owner.userId(), source.project(), source.artifact(), source.item().id(), source.version(),
                source.item().version(), "zh", key);
    }
    private Source source() throws Exception {
        return source(dev.agenvas.testing.ImageAssetFixture.encoded("png", 1600, 900));
    }
    private Source source(byte[] bytes) throws Exception {
        var project = projects.create(owner.userId(), "Synthetic image prompt", Project.AspectRatio.LANDSCAPE_16_9);
        var asset = assets.archiveImage(owner.userId(), project.id(), new ByteArrayInputStream(bytes));
        var artifact = artifacts.createTemplateImport(owner.userId(), project.id(), "Synthetic image", asset.id());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(UUID.randomUUID(), artifact.artifact().id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false)));
        return new Source(project.id(), artifact.artifact().id(), artifact.resourceDefaultVersion().id(), canvas.list(owner.userId(), project.id()).getFirst().item(), asset.id(), bytes);
    }
    private String path(Source source) { return "/api/v1/projects/" + source.project() + "/artifacts/" + source.artifact() + "/image-prompts"; }
    private UsernamePasswordAuthenticationToken auth(AdminPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, "unused", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
    private record Source(UUID project, UUID artifact, UUID version, CanvasItem item, UUID asset, byte[] bytes) {}
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-image-prompt-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway gateway(ObjectProvider<ChatModel> models, LlmProperties properties,
                LlmProviderConfigRepository configs, StoredChatModelFactory factory) {
            return new FakeGateway(new ConfiguredChatGateway(models, properties, configs, factory));
        }
    }
    static class FakeGateway implements ChatGateway {
        private final ConfiguredChatGateway configured;
        int calls;
        boolean failure;
        boolean useStoredConfig;
        List<Message> messages;
        FakeGateway(ConfiguredChatGateway configured) { this.configured = configured; }
        @Override public LlmRequestBudget requestBudgetFor(ConfigIdentity expected) {
            return useStoredConfig ? configured.requestBudgetFor(expected) : null;
        }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> context,
                ConfigIdentity expected, LlmRequestBudget budget) {
            return useStoredConfig ? configured.call(messages, tools, context, expected, budget)
                    : ChatGateway.super.call(messages, tools, context, expected, budget);
        }
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
        @Override public int configVersion() { return useStoredConfig ? configured.configVersion() : 1; }
        @Override public String configSource() { return useStoredConfig ? configured.configSource() : "mock"; }
        @Override public ConfigIdentity configIdentity() {
            return useStoredConfig ? configured.configIdentity() : ChatGateway.super.configIdentity();
        }
        @Override public ModelDetails modelDetails() {
            return useStoredConfig ? configured.modelDetails() : new ModelDetails(true, "synthetic", "synthetic-vision", true);
        }
    }
}

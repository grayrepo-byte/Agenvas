package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real Task/Asset path against a loopback fake OpenAI API; no paid call is made. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class OpenAiImage2PostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_EDIT = new java.util.concurrent.atomic.AtomicReference<>();
    private static final AtomicInteger EDITS = new AtomicInteger();
    private static final AtomicInteger DOWNLOADS = new AtomicInteger();
    private static final AtomicBoolean DROP_NEXT_GENERATION = new AtomicBoolean();
    private static final AtomicBoolean REJECT_NEXT_GENERATION = new AtomicBoolean();
    /** 中转站只回结果 URL 的形态；官方 API 不走这条分支。 */
    private static final AtomicBoolean URL_RESULT = new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicReference<byte[]> RESULT_BYTES = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.JsonNode> LAST_GENERATION = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<String> TOKEN_USAGE = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Runnable> RESPONSE_ACTION = new java.util.concurrent.atomic.AtomicReference<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private dev.agenvas.asset.storage.ObjectStorageClient objectStorage;
    @Autowired
    private dev.agenvas.asset.storage.StorageSettingsService storageSettings;
    @Autowired
    private IdentityService identities;
    @Autowired
    private ProjectService projects;
    @Autowired
    private ArtifactService artifacts;
    @Autowired
    private AssetService assets;
    @Autowired
    private MediaDraftService drafts;
    @Autowired
    private CanvasService canvas;
    @Autowired
    private DirectMediaTaskService directMedia;
    @Autowired
    private MediaCapabilityService catalog;
    @Autowired
    private MediaExecutionWorker worker;
    @Autowired
    private TaskService tasks;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private dev.agenvas.usage.application.UsageService usage;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired
    private org.springframework.web.context.WebApplicationContext webContext;

    @Test
    void approvedReferenceUsesEditsAndLostGenerationResponseRemainsUnknown() throws Exception {
        var connection = catalog.createConnection("openai-it-connection", "OpenAI fake",
                "OPENAI", "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1", "fake-secret");
        var ability = catalog.publishCapability(connection.id(), "GPT Image 2",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"quality\":\"medium\"}"));
        catalog.setDefault(Task.Kind.IMAGE_GENERATION,
                catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), ability.id());
        AdminPrincipal owner = identities.setup("openai-admin", "openai-password-123");
        Fixture edited = fixture(owner.userId(), "Reference edit", 2);
        Task editTask = approve(owner.userId(), edited);
        assertThat(editTask.input().path("mediaInput").path("images").size()).isEqualTo(2);
        assertThat(editTask.input().path("mediaInput").path("images").path(0)
                .path("order").asInt()).isZero();
        assertThat(editTask.input().path("mediaInput").path("images").path(1)
                .path("order").asInt()).isEqualTo(1);
        assertThat(worker.submitOnce("openai-edit-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), edited.project().id(), editTask.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(EDITS).hasValue(1);
        assertThat(GENERATIONS).hasValue(0);
        assertThat(jdbc.sql("select status from call_log where task_id=:task")
                .param("task", editTask.id()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select model from call_log where task_id=:task")
                .param("task", editTask.id()).query(String.class).single()).isEqualTo("gpt-image-2");
        UUID completedVersionId = UUID.fromString(
                completed.output().path("artifactVersionId").asText());
        assertThat(artifacts.requireVersion(owner.userId(), edited.project().id(),
                edited.card().artifact().id(), completedVersionId)
                .content().path("assetId").asText()).isNotBlank();
        assertThat(artifacts.get(owner.userId(), edited.project().id(),
                edited.card().artifact().id()).resourceDefaultVersion()).isNull();
        assertThat(canvas.list(owner.userId(), edited.project().id()).stream()
                .filter(entry -> entry.item().id().toString().equals(
                        editTask.input().path("canvasItemId").asText()))
                .findFirst().orElseThrow().item().selectedVersionId())
                .isEqualTo(completedVersionId);

        Fixture generated = fixture(owner.userId(), "Lost generation", 0);
        Task uncertain = approve(owner.userId(), generated);
        DROP_NEXT_GENERATION.set(true);
        assertThat(worker.submitOnce("openai-unknown-worker")).isEqualTo(1);
        assertThat(GENERATIONS).hasValue(1);
        // 连接被切断当场就按原因码判定，不再等租约到期；码本身要能区分断线与超时。
        Task lost = tasks.get(owner.userId(), generated.project().id(), uncertain.id());
        assertThat(lost.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(lost.errorCode()).isEqualTo(ProviderFailureCodes.RESPONSE_LOST);
        assertThat(tasks.recoverExpiredSubmissions(1)).isZero();
        assertThat(jdbc.sql("select status from call_log where task_id=:task")
                .param("task", uncertain.id()).query(String.class).single()).isEqualTo("UNKNOWN");
        assertThat(jdbc.sql("select count(*) from call_log where task_id=:task")
                .param("task", uncertain.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(worker.submitOnce("openai-second-worker")).isZero();
        assertThat(GENERATIONS).hasValue(1);

        Fixture foreign = fixture(owner.userId(), "Foreign reference", 0);
        Task foreignTask = approve(owner.userId(), foreign);
        // 跨项目参考图：另一个项目里那张参考图的精确版本。
        jdbc.sql("update task set input_json=jsonb_set(input_json, '{mediaInput,images}', "
                + "jsonb_build_array(jsonb_build_object('artifactId', :artifactId, "
                + "'versionId', :versionId, 'role', 'REFERENCE', 'order', 0))) "
                + "where id=:id")
                .param("artifactId", edited.referenceImages().get(0).artifact().id().toString())
                .param("versionId", edited.referenceImages().get(0).resourceDefaultVersion()
                        .id().toString())
                .param("id", foreignTask.id()).update();
        assertThat(worker.submitOnce("openai-foreign-worker")).isEqualTo(1);
        Task blocked = tasks.get(owner.userId(), foreign.project().id(), foreignTask.id());
        assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
        assertThat(blocked.errorCode()).isEqualTo("PROVIDER_UNSUPPORTED_INPUT");
        assertThat(GENERATIONS).hasValue(1);
        assertThat(EDITS).hasValue(1);

        // 中转站只回结果 URL 时也必须下载并归档成 Asset，而不是判成 UNKNOWN。
        URL_RESULT.set(true);
        try {
            Fixture linked = fixture(owner.userId(), "Linked result", 0);
            Task linkedTask = approve(owner.userId(), linked);
            assertThat(worker.submitOnce("openai-url-worker")).isEqualTo(1);
            Task linkedDone = tasks.get(owner.userId(), linked.project().id(), linkedTask.id());
            assertThat(linkedDone.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(GENERATIONS).hasValue(2);
            assertThat(DOWNLOADS).hasValue(1);
            UUID linkedVersionId = UUID.fromString(
                    linkedDone.output().path("artifactVersionId").asText());
            assertThat(artifacts.requireVersion(owner.userId(), linked.project().id(),
                    linked.card().artifact().id(), linkedVersionId)
                    .content().path("assetId").asText()).isNotBlank();
            assertThat(artifacts.get(owner.userId(), linked.project().id(),
                    linked.card().artifact().id()).resourceDefaultVersion()).isNull();
        } finally {
            URL_RESULT.set(false);
        }

        var storage = storageSettings.create(0, "Synthetic image relay",
                dev.agenvas.asset.storage.StorageProfile.Provider.S3,
                "https://s3.example.com", "us-east-1", "synthetic-relay", "references", true, "synthetic-id",
                "synthetic-secret");
        UUID relayProfile = storage.profiles().getFirst().id();
        storageSettings.activateRelay(storage.version(), relayProfile, true, true);
        org.mockito.Mockito
                .when(objectStorage.signedGet(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.eq(dev.agenvas.asset.storage.MediaRelayService.URL_LIFETIME)))
                .thenAnswer(call -> "https://example.com/" + call.getArgument(1) + "?X-Amz-Signature=synthetic");
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            assertThat(jdbc.sql("select count(*) from media_relay_object where object_key=:key")
                    .param("key", call.getArgument(1)).query(Integer.class).single()).isEqualTo(1);
            var image = ImageIO.read(((java.nio.file.Path) call.getArgument(2)).toFile());
            assertThat(image.getWidth()).isEqualTo(1280);
            assertThat(image.getHeight()).isEqualTo(720);
            return null;
        }).when(objectStorage).put(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("image/png"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        Fixture relayed = fixture(owner.userId(), "Frozen relay references", 2);
        Task relayedTask = approve(owner.userId(), relayed);
        assertThat(relayedTask.input().path("imageRelayProfileId").asText()).isEqualTo(relayProfile.toString());
        assertThat(relayedTask.input().toString()).doesNotContain("Signature", "https://");
        // Disable image relay after acceptance: this queued task must retain its chosen route.
        storageSettings.activateRelay(storageSettings.status().version(), relayProfile, true, false);
        assertThat(worker.submitOnce("openai-relay-worker")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), relayed.project().id(), relayedTask.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);
        var editJson = mapper.readTree(LAST_EDIT.get());
        assertThat(editJson.at("/images/0/image_url").asText())
                .startsWith("https://example.com/references/media-relay/");
        assertThat(editJson.path("images").size()).isEqualTo(2);
        assertThat(editJson.toString()).doesNotContain("data:image");
        assertThat(jdbc.sql("select count(*) from media_relay_object").query(Integer.class).single()).isEqualTo(2);
        Fixture inline = fixture(owner.userId(), "LLM on image off", 2);
        Task inlineTask = approve(owner.userId(), inline);
        assertThat(inlineTask.input().path("imageRelayProfileId").isNull()).isTrue();
        assertThat(storageSettings.llmRelayProfileId()).isEqualTo(relayProfile);
        assertThat(storageSettings.imageRelayProfileId()).isNull();
        assertThat(worker.submitOnce("openai-inline-worker")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), inline.project().id(), inlineTask.id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);
        assertThat(LAST_EDIT.get()).contains("filename=\"reference-1.png\"");
        storageSettings.activateRelay(storageSettings.status().version(), relayProfile, true, true);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic failed upload")).when(objectStorage)
                .put(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());
        Fixture failed = fixture(owner.userId(), "Relay fails before generation", 2);
        Task failedTask = approve(owner.userId(), failed);
        int editsBeforeFailure = EDITS.get();
        assertThat(worker.submitOnce("openai-relay-failure-worker")).isEqualTo(1);
        Task failedResult = tasks.get(owner.userId(), failed.project().id(), failedTask.id());
        assertThat(failedResult.status()).isEqualTo(Task.Status.FAILED);
        assertThat(failedResult.errorCode()).isEqualTo("MEDIA_RELAY_PREPARATION_FAILED");
        assertThat(EDITS).hasValue(editsBeforeFailure);

        // A confirmed safety rejection must survive persistence as public, bounded diagnostics.
        Fixture moderated = fixture(owner.userId(), "Synthetic moderation rejection", 0);
        Task moderatedTask = approve(owner.userId(), moderated);
        int generationsBeforeModeration = GENERATIONS.get();
        REJECT_NEXT_GENERATION.set(true);
        assertThat(worker.submitOnce("openai-moderation-worker")).isEqualTo(1);
        Task rejected = tasks.get(owner.userId(), moderated.project().id(), moderatedTask.id());
        assertThat(rejected.status()).isEqualTo(Task.Status.FAILED);
        assertThat(rejected.errorCode()).isEqualTo("OPENAI_IMAGE_MODERATION_BLOCKED");
        var diagnostic = rejected.output().path("providerFailure");
        assertThat(diagnostic.path("category").asText()).isEqualTo("MODERATION");
        assertThat(diagnostic.path("httpStatus").asInt()).isEqualTo(400);
        assertThat(diagnostic.at("/moderation/code").asText()).isEqualTo("moderation_blocked");
        assertThat(diagnostic.at("/moderation/stage").asText()).isEqualTo("output");
        assertThat(diagnostic.at("/moderation/categories").get(0).asText()).isEqualTo("sexual");
        assertThat(rejected.output().toString()).doesNotContain(
                "synthetic-private-prompt", "req_synthetic", "https://private.example", "synthetic-key");
        var auth = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                owner, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        String response = webAppContextSetup(webContext).apply(springSecurity()).build()
                .perform(get("/api/v1/projects/" + moderated.project().id() + "/tasks/" + rejected.id()).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var publicDiagnostic = mapper.readTree(response).at("/output/providerFailure");
        assertThat(publicDiagnostic).isEqualTo(diagnostic);
        assertModerationContract(publicDiagnostic);
        assertThat(worker.submitOnce("openai-moderation-no-retry-worker")).isZero();
        assertThat(GENERATIONS).hasValue(generationsBeforeModeration + 1);

        verifyTokenBilling(owner.userId(), connection.id(), ability);
        verifyGifGeneration(owner, connection.id());
        verifyFrozenModerationAcrossTransports(owner.userId(), connection.id());
    }

    private void verifyFrozenModerationAcrossTransports(UUID owner, UUID connectionId) {
        // Restore the synthetic relay after the earlier failure-path scenarios.
        org.mockito.Mockito.doNothing().when(objectStorage).put(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        UUID relayProfile = storageSettings.status().profiles().getFirst().id();
        for (String transport : List.of("GENERATE", "MULTIPART", "URL")) {
            storageSettings.activateRelay(storageSettings.status().version(), relayProfile, false,
                    "URL".equals(transport));
            var low = mapper.createObjectNode().put("model", "gpt-image-2.5-sunburst").put("moderation", "low");
            var ability = catalog.publishCapability(connectionId, "Synthetic moderation " + transport,
                    "OPENAI_GPT_IMAGE_2", low);
            catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION),
                    ability.id());
            int references = "GENERATE".equals(transport) ? 0 : 2;
            Fixture queued = fixture(owner, "Frozen moderation " + transport, references);
            var updated = new java.util.concurrent.atomic.AtomicReference<dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability>();
            RESPONSE_ACTION
                    .set(() -> updated.set(catalog.updateCapability(connectionId, ability.id(), ability.version(),
                            "Changed moderation " + transport, true, "OPENAI_GPT_IMAGE_2",
                            low.deepCopy().put("moderation", "auto"))));
            assertThat(worker.submitOnce("moderation-low-" + transport)).isEqualTo(1);
            var queuedResult = tasks.get(owner, queued.project().id(), queued.task().id());
            assertThat(queuedResult.status()).as("%s low moderation, error %s", transport, queuedResult.errorCode())
                    .isEqualTo(Task.Status.SUCCEEDED);
            assertModerationRequest(transport, "low");
            Fixture current = fixture(owner, "Current moderation " + transport, references);
            assertThat(worker.submitOnce("moderation-auto-" + transport)).isEqualTo(1);
            assertThat(tasks.get(owner, current.project().id(), current.task().id()).status())
                    .isEqualTo(Task.Status.SUCCEEDED);
            assertModerationRequest(transport, "auto");
            Fixture stale = fixture(owner, "Queued moderation " + transport, references);
            catalog.updateCapability(connectionId, ability.id(), updated.get().version(),
                    "Queued setting changed " + transport,
                    true, "OPENAI_GPT_IMAGE_2", low);
            int submitted = GENERATIONS.get() + EDITS.get();
            assertThat(worker.submitOnce("moderation-stale-" + transport)).isEqualTo(1);
            Task blocked = tasks.get(owner, stale.project().id(), stale.task().id());
            assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
            assertThat(blocked.errorCode()).isEqualTo("MEDIA_CAPABILITY_CHANGED");
            assertThat(blocked.input().path("workflowVersion").asText())
                    .isEqualTo(stale.task().input().path("workflowVersion").asText());
            assertThat(blocked.input().at("/mediaInput/capabilityVersion").asInt())
                    .isEqualTo(stale.task().input().at("/mediaInput/capabilityVersion").asInt());
            assertThat(GENERATIONS.get() + EDITS.get()).isEqualTo(submitted);
        }
        assertThat(worker.submitOnce("moderation-no-duplicate")).isZero();
    }

    private void assertModerationRequest(String transport, String moderation) {
        if ("GENERATE".equals(transport))
            assertThat(LAST_GENERATION.get().path("moderation").asText()).isEqualTo(moderation);
        else if ("URL".equals(transport))
            assertThat(mapper.readTree(LAST_EDIT.get()).path("moderation").asText()).isEqualTo(moderation);
        else
            assertThat(LAST_EDIT.get()).contains("name=\"moderation\"", "\r\n\r\n" + moderation + "\r\n");
    }

    private void verifyGifGeneration(AdminPrincipal owner, UUID connectionId) throws Exception {
        byte[] animation = dev.agenvas.testing.GifFixture.animation(4);
        var mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        var auth = authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                owner, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        for (String model : List.of("gpt-image-2.6-flare", "gpt-image-2.6-sunburst")) {
            var settings = mapper.createObjectNode().put("model", model).put("outputFormat", "gif")
                    .put("frameCount", 4).put("frameDurationMs", 200).put("complexity", "medium")
                    .put("quality", "xhigh");
            settings.putObject("tokenPricing").put("currency", "USD").put("textInputPerMillion", "5")
                    .put("imageInputPerMillion", "8").put("imageOutputPerMillion", "30");
            var ability = catalog.publishCapability(connectionId, "Synthetic GIF " + model, "OPENAI_GPT_IMAGE_2",
                    settings);
            catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION),
                    ability.id());
            assertThat(catalog.inputPolicy(catalog.defaultFor(Task.Kind.IMAGE_GENERATION)).maxReferenceImages())
                    .isZero();
            RESULT_BYTES.set(animation);
            TOKEN_USAGE.set(
                    "{\"input_tokens\":100,\"output_tokens\":400,\"input_tokens_details\":{\"text_tokens\":100,\"image_tokens\":0}}");
            try {
                Fixture fixture = fixture(owner.userId(), "Synthetic animation", 0);
                int submissions = GENERATIONS.get();
                assertThat(worker.submitOnce("gif-worker")).isEqualTo(1);
                Task done = tasks.get(owner.userId(), fixture.project().id(), fixture.task().id());
                assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
                assertThat(GENERATIONS.get()).isEqualTo(submissions + 1);
                assertThat(LAST_GENERATION.get().path("model").asText()).isEqualTo(model);
                assertThat(LAST_GENERATION.get().path("output_format").asText()).isEqualTo("gif");
                assertThat(LAST_GENERATION.get().path("frame_count").asInt()).isEqualTo(4);
                assertThat(LAST_GENERATION.get().path("frame_duration_ms").asInt()).isEqualTo(200);
                assertThat(LAST_GENERATION.get().path("size").asText()).isEqualTo("auto");
                UUID versionId = UUID.fromString(done.output().path("artifactVersionId").asText());
                var version = artifacts.requireVersion(owner.userId(), fixture.project().id(),
                        fixture.card().artifact().id(), versionId);
                UUID assetId = UUID.fromString(version.content().path("assetId").asText());
                var file = assets.get(owner.userId(), fixture.project().id(), assetId);
                assertThat(file.asset().contentType()).isEqualTo("image/gif");
                assertThat(java.nio.file.Files.readAllBytes(file.path())).isEqualTo(animation);
                var result = mvc
                        .perform(get("/api/v1/projects/" + fixture.project().id() + "/assets/" + assetId + "/content")
                                .with(auth))
                        .andExpect(status().isOk()).andReturn().getResponse();
                assertThat(result.getContentType()).isEqualTo("image/gif");
                assertThat(result.getContentAsByteArray()).isEqualTo(animation);
                var settlement = usage.listProject(owner.userId(), fixture.project().id()).stream()
                        .filter(entry -> entry.entryType() == dev.agenvas.usage.domain.UsageEntry.EntryType.SETTLEMENT)
                        .findFirst().orElseThrow();
                assertThat(settlement.estimatedCost()).isEqualByComparingTo("0.012500");
                assertThat(settlement.actualCost()).isNull();
                var item = canvas.list(owner.userId(), fixture.project().id()).stream()
                        .filter(entry -> entry.item().subjectId().equals(fixture.card().artifact().id())).findFirst()
                        .orElseThrow().item();
                org.assertj.core.api.Assertions
                        .assertThatThrownBy(() -> directMedia.runImageOperation(owner.userId(), fixture.project().id(),
                                fixture.card().artifact().id(), item.id(), versionId, item.version(),
                                dev.agenvas.task.domain.ImageOperation.RESIZE, "", 0, 1, List.of(), null,
                                mapper.createObjectNode().put("resizeMode", "PERCENTAGE").put("percentage", 50),
                                "gif-static-tool-" + model))
                        .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,
                                error -> assertThat(error.detail().key()).isEqualTo("api.asset.gif-static-operation"));
                var staticAbility = catalog.publishCapability(connectionId, "Synthetic static reference " + model,
                        "OPENAI_GPT_IMAGE_2", mapper.createObjectNode().put("model", model));
                var target = artifacts.create(owner.userId(), fixture.project().id(), Artifact.Kind.IMAGE,
                        "Static target", null);
                UUID targetItem = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(),
                        fixture.project().id(), target.artifact().id());
                long draftVersion = drafts.save(owner.userId(), fixture.project().id(), targetItem, 0,
                        "Synthetic reference", mapper.createObjectNode(),
                        null, staticAbility.id(), null, List.of(new MediaDraftService.SaveMediaInput(versionId,
                                dev.agenvas.artifact.domain.MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                        List.of(), null).version();
                org.assertj.core.api.Assertions
                        .assertThatThrownBy(
                                () -> directMedia.run(owner.userId(), fixture.project().id(), target.artifact().id(),
                                        targetItem, draftVersion, "gif-reference-" + model))
                        .isInstanceOfSatisfying(dev.agenvas.shared.error.ApiProblemException.class,
                                error -> assertThat(error.detail().key()).isEqualTo("api.asset.gif-static-operation"));
                assertThat(worker.submitOnce("gif-no-resubmission")).isZero();
            } finally {
                RESULT_BYTES.set(null);
                TOKEN_USAGE.set(null);
            }
        }
    }

    private void verifyTokenBilling(UUID owner, UUID connectionId,
            dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability original) {
        storageSettings.activateRelay(storageSettings.status().version(),
                storageSettings.status().profiles().getFirst().id(), false, false);
        var pricing = mapper.readTree("""
                {"model":"gpt-image-2","tokenPricing":{"currency":"USD","textInputPerMillion":"5",
                "imageInputPerMillion":"8","imageOutputPerMillion":"30"}}
                """);
        var priced = catalog.updateCapability(connectionId, original.id(), original.version(), "Token priced", true,
                "OPENAI_GPT_IMAGE_2", pricing);
        TOKEN_USAGE.set("""
                {"input_tokens":300,"output_tokens":400,"total_tokens":700,
                "input_tokens_details":{"text_tokens":100,"image_tokens":200},"private":"discard"}
                """);
        Fixture generated = fixture(owner, "Synthetic token generation", 0);
        Task accepted = generated.task();
        assertThat(accepted.input().at("/mediaTokenPricing/textInputPerMillion").asText()).isEqualTo("5");
        assertThat(usage.listProject(owner, generated.project().id()).getFirst().estimatedCost()).isNull();
        // Repricing after submission cannot change the frozen rate used by an in-flight request.
        RESPONSE_ACTION
                .set(() -> catalog.updateCapability(connectionId, priced.id(), priced.version(), "New rates", true,
                        "OPENAI_GPT_IMAGE_2", mapper.readTree(pricing.toString().replace("\"5\"", "\"50\""))));
        assertThat(worker.submitOnce("token-generation-worker")).isEqualTo(1);
        Task done = tasks.get(owner, generated.project().id(), accepted.id());
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.output().at("/providerUsage/textInputTokens").asInt()).isEqualTo(100);
        assertThat(done.output().toString()).doesNotContain("private", "discard");
        assertThat(artifacts.requireVersion(owner, generated.project().id(), generated.card().artifact().id(),
                UUID.fromString(done.output().path("artifactVersionId").asText())).content()
                .at("/parameters/providerUsage/outputTokens").asInt()).isEqualTo(400);
        var settlement = usage.listProject(owner, generated.project().id()).stream()
                .filter(entry -> entry.entryType() == dev.agenvas.usage.domain.UsageEntry.EntryType.SETTLEMENT)
                .findFirst().orElseThrow();
        assertThat(settlement.estimatedCost()).isEqualByComparingTo("0.014100");
        assertThat(settlement.actualCost()).isNull();
        new org.springframework.transaction.support.TransactionTemplate(transactions)
                .executeWithoutResult(status -> usage.settleMediaTask(owner, done));
        assertThat(usage.listProject(owner, generated.project().id())).hasSize(2);
        assertThat(worker.submitOnce("token-no-duplicate-worker")).isZero();

        Fixture edited = fixture(owner, "Synthetic token edit", 2);
        assertThat(worker.submitOnce("token-edit-worker")).isEqualTo(1);
        assertThat(usage.listProject(owner, edited.project().id()).stream()
                .filter(entry -> entry.entryType() == dev.agenvas.usage.domain.UsageEntry.EntryType.SETTLEMENT)
                .findFirst().orElseThrow().estimatedCost())
                .isEqualByComparingTo("0.018600");

        Fixture canceled = fixture(owner, "Synthetic late canceled result", 0);
        RESPONSE_ACTION.set(() -> jdbc.sql("update task set cancel_requested=true where id=:id")
                .param("id", canceled.task().id()).update());
        assertThat(worker.submitOnce("token-canceled-worker")).isEqualTo(1);
        assertThat(tasks.get(owner, canceled.project().id(), canceled.task().id()).status())
                .isEqualTo(Task.Status.CANCELED);
        assertThat(usage.listProject(owner, canceled.project().id()).stream()
                .filter(entry -> entry.entryType() == dev.agenvas.usage.domain.UsageEntry.EntryType.SETTLEMENT)
                .findFirst().orElseThrow().estimatedCost())
                .isEqualByComparingTo("0.018600");

        TOKEN_USAGE.set(null);
        Fixture missing = fixture(owner, "Synthetic missing usage", 0);
        assertThat(worker.submitOnce("token-missing-worker")).isEqualTo(1);
        assertThat(tasks.get(owner, missing.project().id(), missing.task().id()).status())
                .isEqualTo(Task.Status.SUCCEEDED);
        assertThat(usage.listProject(owner, missing.project().id())).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.estimatedCost()).isNull();
            assertThat(entry.actualCost()).isNull();
            assertThat(entry.costStatus()).isEqualTo(dev.agenvas.usage.domain.UsageEntry.CostStatus.UNKNOWN);
        });
        TOKEN_USAGE.set("""
                {"input_tokens":300,"output_tokens":400,"total_tokens":700,
                "input_tokens_details":{"text_tokens":100,"image_tokens":200},"output_tokens_details":"invalid"}
                """);
        Fixture malformed = fixture(owner, "Synthetic malformed usage", 0);
        assertThat(worker.submitOnce("token-malformed-worker")).isEqualTo(1);
        Task malformedResult = tasks.get(owner, malformed.project().id(), malformed.task().id());
        assertThat(malformedResult.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(malformedResult.output().at("/providerUsage/valid").asBoolean()).isFalse();
        assertThat(usage.listProject(owner, malformed.project().id())).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.estimatedCost()).isNull();
            assertThat(entry.actualCost()).isNull();
            assertThat(entry.costStatus()).isEqualTo(dev.agenvas.usage.domain.UsageEntry.CostStatus.UNKNOWN);
        });
        TOKEN_USAGE.set(null);
    }

    /** 一张空图片卡片、其草稿和直连受理结果；参考图按传入顺序固定。 */
    private Fixture fixture(UUID ownerId, String name, int referenceCount) {
        Project project = projects.create(ownerId, name, Project.AspectRatio.LANDSCAPE_16_9);
        List<ArtifactService.ArtifactView> referenceImages = new ArrayList<>();
        for (int index = 0; index < referenceCount; index++) {
            UUID assetId = ImageAssetFixture.archive(assets, ownerId, project.id());
            ObjectNode image = mapper.createObjectNode();
            image.put("assetId", assetId.toString());
            image.put("sourceTaskId", UUID.randomUUID().toString());
            image.put("prompt", "Reference");
            image.put("workflowVersion", "fixture");
            image.putObject("parameters");
            referenceImages.add(artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE,
                    "Reference " + (index + 1), image));
        }
        var card = artifacts.create(ownerId, project.id(), Artifact.Kind.IMAGE, "Concept", null);
        UUID canvasItemId = dev.agenvas.support.CanvasMediaFixture.place(
                canvas, ownerId, project.id(), card.artifact().id());
        List<MediaDraftService.SaveMediaInput> inputs = referenceImages.stream()
                .map(reference -> new MediaDraftService.SaveMediaInput(
                        reference.resourceDefaultVersion().id(),
                        dev.agenvas.artifact.domain.MediaDraft.InputRole.REFERENCE,
                        "#7C3AED"))
                .toList();
        long draftVersion = drafts.save(ownerId, project.id(), canvasItemId, 0,
                "A detailed cinematic studio scene", mapper.createObjectNode(), null, null,
                null, inputs, List.of(), null).version();
        Task task = directMedia.run(ownerId, project.id(), card.artifact().id(), canvasItemId,
                draftVersion,
                "openai-" + UUID.randomUUID());
        return new Fixture(project, card, List.copyOf(referenceImages), task);
    }

    private Task approve(UUID ownerId, Fixture fixture) {
        return fixture.task();
    }

    /** Check the public HTTP diagnostic against the authoritative OpenAPI field and enum definitions. */
    private void assertModerationContract(tools.jackson.databind.JsonNode diagnostic) {
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.isRegularFile(directory.resolve("contracts/openapi.yaml"))) {
            directory = directory.getParent();
        }
        assertThat(directory).as("Repository OpenAPI contract").isNotNull();
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(
                new org.springframework.core.io.FileSystemResource(directory.resolve("contracts/openapi.yaml")));
        var schemas = mapper.valueToTree(yaml.getObject()).at("/components/schemas");
        var failureSchema = schemas.path("ProviderFailure");
        assertThat(failureSchema.path("properties").propertyNames()).containsAll(diagnostic.propertyNames());
        assertThat(diagnostic.propertyNames()).containsAll(
                failureSchema.path("required").valueStream().map(tools.jackson.databind.JsonNode::asText).toList());
        assertThat(failureSchema.at("/properties/category/enum")).contains(diagnostic.path("category"));
        var moderation = diagnostic.path("moderation");
        var moderationSchema = schemas.path("ProviderModeration");
        assertThat(moderation.propertyNames()).containsExactlyInAnyOrderElementsOf(
                moderationSchema.path("required").valueStream().map(tools.jackson.databind.JsonNode::asText).toList());
        for (String field : List.of("code", "stage")) {
            assertThat(moderationSchema.path("properties").path(field).path("enum")).contains(moderation.path(field));
        }
        var categoriesSchema = moderationSchema.at("/properties/categories");
        assertThat(moderation.path("categories").size()).isLessThanOrEqualTo(categoriesSchema.path("maxItems").asInt());
        for (var category : moderation.path("categories")) {
            assertThat(categoriesSchema.at("/items/enum")).contains(category);
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/images/generations", exchange -> {
                GENERATIONS.incrementAndGet();
                if (DROP_NEXT_GENERATION.getAndSet(false)) {
                    exchange.close();
                    return;
                }
                if (REJECT_NEXT_GENERATION.getAndSet(false)) {
                    byte[] response = ("{\"error\":{\"message\":\"synthetic-private-prompt req_synthetic "
                            + "https://private.example synthetic-key\",\"type\":\"image_generation_user_error\","
                            + "\"code\":\"moderation_blocked\",\"moderation_details\":{"
                            + "\"moderation_stage\":\"output\",\"categories\":[\"sexual\"]}}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                    return;
                }
                byte[] request = exchange.getRequestBody().readAllBytes();
                LAST_GENERATION.set(new ObjectMapper().readTree(request));
                respond(exchange, request);
            });
            server.createContext("/v1/images/edits", exchange -> {
                EDITS.incrementAndGet();
                byte[] body = exchange.getRequestBody().readAllBytes();
                String multipart = new String(body, StandardCharsets.ISO_8859_1);
                LAST_EDIT.set(multipart);
                if (!exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/json")) {
                    assertThat(multipart).contains("name=\"image[]\"",
                            "filename=\"reference-1.png\"",
                            "filename=\"reference-2.png\"");
                    assertThat(multipart.indexOf("filename=\"reference-1.png\""))
                            .isLessThan(multipart.indexOf("filename=\"reference-2.png\""));
                }
                respond(exchange, body);
            });
            // 结果托管端点与 API 不同路径，且不接收凭证。
            server.createContext("/cdn/result.png", exchange -> {
                DOWNLOADS.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                byte[] png = pngBytes();
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(png);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer fake-secret");
        assertThat(body).isNotEmpty();
        String payload = URL_RESULT.get()
                ? "{\"data\":[{\"url\":\"http://127.0.0.1:"
                        + SERVER.getAddress().getPort() + "/cdn/result.png\"}]}"
                : "{\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder()
                                .encodeToString(RESULT_BYTES.get() == null ? pngBytes() : RESULT_BYTES.get())
                        + "\"}]}";
        Runnable action = RESPONSE_ACTION.getAndSet(null);
        if (action != null)
            action.run();
        if (TOKEN_USAGE.get() != null)
            payload = payload.substring(0, payload.length() - 1) + ",\"usage\":" + TOKEN_USAGE.get() + "}";
        byte[] response = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    private record Fixture(Project project, ArtifactService.ArtifactView card,
            List<ArtifactService.ArtifactView> referenceImages, Task task) {
    }
}

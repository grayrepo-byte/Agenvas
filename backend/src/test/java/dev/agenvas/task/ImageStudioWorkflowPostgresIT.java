package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.StudioImagePurgeWorker;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.task.application.TaskService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Studio HTTP -> PostgreSQL -> real Worker -> loopback provider -> private bytes, with no paid calls. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ImageStudioWorkflowPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final AtomicInteger EDITS = new AtomicInteger();
    private static final AtomicBoolean REJECT_NEXT = new AtomicBoolean();
    private static final AtomicBoolean DROP_NEXT = new AtomicBoolean();
    private static final AtomicReference<JsonNode> LAST_GENERATION = new AtomicReference<>();
    private static final AtomicReference<String> LAST_EDIT = new AtomicReference<>();
    private static final HttpServer PROVIDER = fakeProvider();
    private static final String ROOT = "/api/v1/image-studio";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.asset.studio-purge-worker-enabled", () -> false);
        properties.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void closeProvider() {
        PROVIDER.stop(0);
    }

    @AfterEach
    void resetProviderFaults() {
        REJECT_NEXT.set(false);
        DROP_NEXT.set(false);
    }

    @Autowired
    IdentityService identities;
    @Autowired
    MediaCapabilityService capabilities;
    @Autowired
    MediaExecutionWorker worker;
    @Autowired
    TaskService tasks;
    @Autowired
    ArtifactService artifacts;
    @Autowired
    AssetService assets;
    @Autowired
    StudioImagePurgeWorker purgeWorker;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    WebApplicationContext context;

    @Test
    void uploadedReferenceUsesFrozenVersionAndPrivateDownloadWithoutCanvas() throws Exception {
        AdminPrincipal owner = owner();
        Fixture fixture = configured(owner);
        MockMvc mvc = mvc();
        int generationBefore = GENERATIONS.get();
        int editsBefore = EDITS.get();
        JsonNode upload = mapper.readTree(mvc.perform(multipart(ROOT + "/references")
                .file(new MockMultipartFile("file", "synthetic-reference.png", "image/png", png()))
                .with(authentication(auth(owner))).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(upload.path("projectId").asText()).isEqualTo(fixture.projectId());
        assertThat(upload.path("artifactId").asText()).isNotBlank();
        assertThat(upload.path("versionId").asText()).isNotBlank();
        assertNoCanvas(fixture.projectId());

        ObjectNode request = request(fixture, "Synthetic frozen reference", 1);
        request.withArray("referenceVersionIds").add(upload.path("versionId").asText());
        JsonNode accepted = submit(mvc, owner, request);
        assertThat(accepted.path("references").valueStream().toList()).containsExactly(upload);
        String taskId = accepted.path("tasks").path(0).path("id").asText();
        JsonNode frozen = taskInput(taskId);
        assertThat(frozen).isNotNull();
        assertThat(frozen.path("executionSurface").asText()).isEqualTo("IMAGE_STUDIO");
        assertThat(frozen.has("canvasItemId")).isFalse();
        assertThat(frozen.has("sourceCanvasItemId")).isFalse();
        assertThat(frozen.at("/mediaInput/images/0/versionId").asText())
                .isEqualTo(upload.path("versionId").asText());
        assertThat(frozen.at("/mediaInput/images/0/assetId").asText())
                .isEqualTo(upload.path("assetId").asText());
        assertThat(frozen.at("/mediaInput/parameters/aspectRatio").asText()).isEqualTo("1:1");

        // Moving the resource default after acceptance cannot change this request's immutable reference.
        UUID projectId = UUID.fromString(fixture.projectId());
        UUID referenceId = UUID.fromString(upload.path("artifactId").asText());
        UUID referenceVersionId = UUID.fromString(upload.path("versionId").asText());
        var reference = artifacts.get(owner.userId(), projectId, referenceId);
        ObjectNode laterContent = (ObjectNode) artifacts.requireVersion(owner.userId(), projectId,
                referenceId, referenceVersionId).content().deepCopy();
        var replacement = assets.archiveImage(owner.userId(), projectId, new ByteArrayInputStream(png(3)));
        laterContent.put("assetId", replacement.id().toString());
        var revised = artifacts.revise(owner.userId(), projectId, referenceId, reference.artifact().version(),
                "Synthetic later resource default", laterContent);
        assertThat(revised.resourceDefaultVersion().id()).isNotEqualTo(referenceVersionId);
        assertThat(revised.resourceDefaultVersion().content().path("assetId").asText())
                .isNotEqualTo(upload.path("assetId").asText());

        assertThat(worker.submitOnce("studio-reference-worker")).isEqualTo(1);
        JsonNode completed = detail(mvc, owner, accepted.path("id").asText());
        assertThat(completed.path("tasks").path(0).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.toString()).doesNotContain("127.0.0.1", "synthetic-secret", "canvasItemId");
        assertThat(EDITS.get() - editsBefore).isEqualTo(1);
        assertThat(GENERATIONS.get()).isEqualTo(generationBefore);
        assertThat(LAST_EDIT.get()).contains("Synthetic frozen reference", "name=\"image[]\"");
        JsonNode output = completed.at("/tasks/0/results/0");
        assertThat(output.path("assetId").asText()).isNotBlank();
        var download = mvc.perform(get(assetPath(fixture.projectId(), output.path("assetId").asText(), "content"))
                .with(authentication(auth(owner))))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(download.getContentType()).startsWith("image/png");
        assertThat(download.getContentAsByteArray()).isEqualTo(png());
        mvc.perform(get(assetPath(fixture.projectId(), output.path("assetId").asText(), "thumbnail"))
                .with(authentication(auth(owner))))
                .andExpect(status().isOk());
        JsonNode refreshed = detail(mvc(), owner, accepted.path("id").asText());
        assertThat(refreshed.path("referenceVersionIds")).isEqualTo(accepted.path("referenceVersionIds"));
        assertThat(refreshed.path("references")).isEqualTo(accepted.path("references"));
        assertThat(refreshed.path("prompt").asText()).isEqualTo("Synthetic frozen reference");
        assertThat(refreshed.path("tasks")).isEqualTo(completed.path("tasks"));
        JsonNode history = mapper.readTree(mvc().perform(get(ROOT + "/batches?limit=20")
                .with(authentication(auth(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode historicalBatch = history.path("items").valueStream()
                .filter(value -> value.path("id").equals(accepted.path("id"))).findFirst().orElseThrow();
        assertThat(historicalBatch.path("references")).isEqualTo(accepted.path("references"));
        assertNoCanvas(fixture.projectId());

        AdminPrincipal intruder = otherOwner();
        mvc.perform(get(ROOT + "/batches/" + accepted.path("id").asText())
                .with(authentication(auth(intruder))))
                .andExpect(status().isNotFound());
        mvc.perform(get(assetPath(fixture.projectId(), output.path("assetId").asText(), "content"))
                .with(authentication(auth(intruder))))
                .andExpect(status().isNotFound());
        Fixture foreign = configured(intruder);
        ObjectNode forged = request(foreign, "Synthetic forged reference", 1);
        forged.withArray("referenceVersionIds").add(upload.path("versionId").asText());
        mvc.perform(post(ROOT + "/batches").with(authentication(auth(intruder))).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(forged)))
                .andExpect(status().is4xxClientError());
        assertThat(EDITS.get() - editsBefore).isEqualTo(1);
        assertThat(GENERATIONS.get()).isEqualTo(generationBefore);
        assertNoCanvas(foreign.projectId());
        assertThat(jdbc.sql("select count(*) from task where project_id=:id")
                .param("id", UUID.fromString(foreign.projectId())).query(Integer.class).single()).isZero();
    }

    @Test
    void deletingHistoryByDefaultKeepsFilesAndTheOriginalCommandCannotGenerateAgain() throws Exception {
        var owner = owner();
        var fixture = configured(owner);
        var mvc = mvc();
        var auth = authentication(auth(owner));
        var request = request(fixture, "Synthetic retained files", 1);
        String commandKey = UUID.randomUUID().toString();
        String id = submit(mvc, owner, request, commandKey).path("id").asText();
        assertThat(worker.submitOnce("studio-retain-worker")).isEqualTo(1);
        String asset = detail(mvc, owner, id).at("/tasks/0/results/0/assetId").asText();
        String path = ROOT + "/batches/" + id;
        mvc.perform(delete(path).with(authentication(auth(otherOwner()))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).with(auth)).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(auth).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(path).with(auth).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(path).param("deleteFiles", "true").with(auth).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STUDIO_DELETE_OPTION_MISMATCH"));
        mvc.perform(get(path).with(auth)).andExpect(status().isNotFound());
        var list = mapper.readTree(mvc.perform(get(ROOT + "/batches").with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(list.path("items").valueStream().map(row -> row.path("id").asText()).toList())
                .doesNotContain(id);
        mvc.perform(get(ROOT + "/batches").param("before", id).with(auth)).andExpect(status().isOk());
        mvc.perform(post(ROOT + "/batches").with(auth).with(csrf()).header("Idempotency-Key", commandKey)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STUDIO_BATCH_DELETED"));
        purgeWorker.purgeDueImages();
        mvc.perform(get(assetPath(fixture.projectId(), asset, "content")).with(auth)).andExpect(status().isOk());
        mvc.perform(get(assetPath(fixture.projectId(), asset, "thumbnail")).with(auth)).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from image_studio_batch_task where batch_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void permanentDeletionRevokesURLsAndRemovesOnlyArchivedGeneratedFiles() throws Exception {
        var owner = owner();
        var fixture = configured(owner);
        var mvc = mvc();
        var auth = authentication(auth(owner));
        var reference = mapper.readTree(mvc.perform(multipart(ROOT + "/references")
                .file(new MockMultipartFile("file", "synthetic-purge-reference.png", "image/png", png()))
                .with(auth).with(csrf())).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsByteArray());
        var payload = request(fixture, "Synthetic permanent deletion", 2);
        payload.withArray("referenceVersionIds").add(reference.path("versionId").asText());
        String id = submit(mvc, owner, payload).path("id").asText();
        String path = ROOT + "/batches/" + id;
        assertThat(worker.submitOnce("studio-purge-worker")).isEqualTo(1);
        mvc.perform(delete(path).param("deleteFiles", "true").with(auth).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STUDIO_BATCH_UNSETTLED"));
        assertThat(worker.submitOnce("studio-purge-worker")).isEqualTo(1);
        var results = detail(mvc, owner, id).path("tasks").valueStream()
                .map(task -> task.at("/results/0")).toList();
        UUID projectId = UUID.fromString(fixture.projectId());
        var localFiles = results.stream().map(result ->
                assets.get(owner.userId(), projectId, UUID.fromString(result.path("assetId").asText()))).toList();
        assertThat(localFiles).hasSize(2).allSatisfy(file -> {
            assertThat(file.path()).exists();
            assertThat(file.path().resolveSibling(file.asset().id() + ".thumb.png")).exists();
        });
        mvc.perform(delete(path).param("deleteFiles", "true").with(auth).with(csrf()))
                .andExpect(status().isAccepted());
        for (var result : results) {
            String asset = result.path("assetId").asText();
            mvc.perform(head(assetPath(fixture.projectId(), asset, "content")).with(auth))
                    .andExpect(status().isNotFound());
            mvc.perform(get(assetPath(fixture.projectId(), asset, "thumbnail")).with(auth))
                    .andExpect(status().isNotFound());
        }
        purgeWorker.purgeDueImages();
        assertThat(localFiles).allSatisfy(file -> {
            assertThat(file.path()).doesNotExist();
            assertThat(file.path().resolveSibling(file.asset().id() + ".thumb.png")).doesNotExist();
        });
        mvc.perform(delete(path).param("deleteFiles", "true").with(auth).with(csrf()))
                .andExpect(status().isAccepted());
        mvc.perform(get(assetPath(fixture.projectId(), reference.path("assetId").asText(), "content")).with(auth))
                .andExpect(status().isOk());
    }

    @Test
    void cannotPurgeAnOutputFrozenAsAnotherBatchReferenceEvenWhenThatBatchIsHidden() throws Exception {
        var owner = owner();
        var fixture = configured(owner);
        var mvc = mvc();
        var auth = authentication(auth(owner));
        String sourceId = submit(mvc, owner, request(fixture, "Synthetic source", 1)).path("id").asText();
        assertThat(worker.submitOnce("studio-purge-reference-worker")).isEqualTo(1);
        var output = detail(mvc, owner, sourceId).at("/tasks/0/results/0");
        var dependent = request(fixture, "Synthetic generated reference", 1);
        dependent.withArray("referenceVersionIds").add(output.path("artifactVersionId").asText());
        String dependentId = submit(mvc, owner, dependent).path("id").asText();
        mvc.perform(delete(ROOT + "/batches/" + dependentId).with(auth).with(csrf()))
                .andExpect(status().isNoContent());
        String path = ROOT + "/batches/" + sourceId;
        mvc.perform(delete(path).param("deleteFiles", "true").with(auth).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STUDIO_BATCH_ASSETS_IN_USE"));
        mvc.perform(get(path).with(auth)).andExpect(status().isOk());
        mvc.perform(get(assetPath(fixture.projectId(), output.path("assetId").asText(), "content")).with(auth))
                .andExpect(status().isOk());
        assertThat(worker.submitOnce("studio-purge-reference-worker")).isEqualTo(1);
    }

    @Test
    void partialBatchRetainsSuccessAndFailureAndUnknownNeverResubmitsAfterRecovery() throws Exception {
        AdminPrincipal owner = owner();
        Fixture fixture = configured(owner);
        MockMvc mvc = mvc();
        int before = GENERATIONS.get();
        JsonNode accepted = submit(mvc, owner, request(fixture, "Synthetic partial batch", 2));
        assertThat(accepted.path("tasks").size()).isEqualTo(2);
        REJECT_NEXT.set(true);
        assertThat(worker.submitOnce("studio-partial-first")).isEqualTo(1);
        assertThat(worker.submitOnce("studio-partial-second")).isEqualTo(1);
        JsonNode partial = detail(mvc(), owner, accepted.path("id").asText());
        assertThat(partial.path("tasks").valueStream().map(value -> value.path("status").asText()).toList())
                .containsExactlyInAnyOrder("FAILED", "SUCCEEDED");
        assertThat(partial.path("tasks").valueStream().mapToInt(value -> value.path("results").size()).sum())
                .isEqualTo(1);
        assertThat(GENERATIONS.get() - before).isEqualTo(2);
        assertThat(LAST_GENERATION.get().path("size").asText()).isEqualTo("1024x1024");
        assertNoCanvas(fixture.projectId());

        ObjectNode uncertainRequest = request(fixture, "Synthetic lost response", 1);
        String uncertainKey = UUID.randomUUID().toString();
        JsonNode uncertain = submit(mvc, owner, uncertainRequest, uncertainKey);
        DROP_NEXT.set(true);
        assertThat(worker.submitOnce("studio-lost-response")).isEqualTo(1);
        JsonNode unknown = detail(mvc(), owner, uncertain.path("id").asText());
        assertThat(unknown.at("/tasks/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.at("/tasks/0/results").isEmpty()).isTrue();
        int afterUnknown = GENERATIONS.get();
        assertThat(afterUnknown - before).isEqualTo(3);
        assertThat(tasks.recoverExpiredSubmissions(10)).isZero();
        assertThat(worker.submitOnce("studio-restarted-submitter")).isZero();
        assertThat(worker.pollOnce("studio-restarted-poller")).isZero();
        JsonNode afterRecovery = detail(mvc(), owner, uncertain.path("id").asText());
        assertThat(afterRecovery.at("/tasks/0/status").asText()).isEqualTo("UNKNOWN");
        JsonNode replayedAcceptance = submit(mvc(), owner, uncertainRequest, uncertainKey);
        assertThat(replayedAcceptance.path("id")).isEqualTo(uncertain.path("id"));
        assertThat(replayedAcceptance.at("/tasks/0/status").asText()).isEqualTo("UNKNOWN");
        assertThat(GENERATIONS.get()).isEqualTo(afterUnknown);
        String taskId = uncertain.at("/tasks/0/id").asText();
        assertThat(jdbc.sql("select count(*) from provider_attempt where task_id=:id")
                .param("id", UUID.fromString(taskId)).query(Integer.class).single()).isEqualTo(1);
        assertNoCanvas(fixture.projectId());
    }

    private AdminPrincipal owner() {
        return jdbc.sql("select id from app_user where login_name='studio-workflow-admin'")
                .query(UUID.class).optional().map(id -> new AdminPrincipal(id, "studio-workflow-admin"))
                .orElseGet(() -> identities.setup("studio-workflow-admin", "synthetic-studio-password"));
    }

    private AdminPrincipal otherOwner() {
        UUID id = UUID.randomUUID();
        String name = "synthetic-" + id;
        // A retained foreign owner is sufficient for scope checks; the schema allows one active administrator.
        jdbc.sql("insert into app_user(id,login_name,password_hash,status,created_at) "
                + "values (:id,:name,'synthetic-hash','DISABLED',now())")
                .param("id", id).param("name", name).update();
        return new AdminPrincipal(id, name);
    }

    private Fixture configured(AdminPrincipal owner) throws Exception {
        var connection = capabilities.createConnection(UUID.randomUUID().toString(), "Synthetic Studio OpenAI",
                "OPENAI", "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1", "synthetic-secret");
        var capability = capabilities.publishCapability(connection.id(), "Synthetic Studio Image",
                "OPENAI_GPT_IMAGE_2", mapper.readTree("{\"quality\":\"medium\"}"));
        JsonNode studio = mapper.readTree(mvc().perform(get(ROOT + "/context")
                .with(authentication(auth(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode published = studio.path("capabilities").valueStream()
                .filter(value -> value.path("capabilityId").asText().equals(capability.id().toString()))
                .findFirst().orElseThrow();
        return new Fixture(studio.path("projectId").asText(), capability.id().toString(),
                published.path("capabilityVersion").asInt());
    }

    private ObjectNode request(Fixture fixture, String prompt, int quantity) {
        ObjectNode result = mapper.createObjectNode();
        result.put("capabilityId", fixture.capabilityId());
        result.put("expectedCapabilityVersion", fixture.capabilityVersion());
        result.put("prompt", prompt);
        result.putObject("parameters").put("aspectRatio", "AUTO").put("resolution", "1K")
                .put("quality", "medium").put("transparentBackground", false).put("generationCount", quantity);
        result.putArray("referenceVersionIds");
        return result;
    }

    private JsonNode submit(MockMvc mvc, AdminPrincipal owner, JsonNode request) throws Exception {
        return submit(mvc, owner, request, UUID.randomUUID().toString());
    }

    private JsonNode submit(MockMvc mvc, AdminPrincipal owner, JsonNode request, String commandKey) throws Exception {
        return mapper.readTree(mvc.perform(post(ROOT + "/batches").with(authentication(auth(owner))).with(csrf())
                .header("Idempotency-Key", commandKey)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode detail(MockMvc mvc, AdminPrincipal owner, String id) throws Exception {
        return mapper.readTree(mvc.perform(get(ROOT + "/batches/" + id).with(authentication(auth(owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode taskInput(String taskId) {
        return mapper.readTree(jdbc.sql("select input_json::text from task where id=:id")
                .param("id", UUID.fromString(taskId)).query(String.class).single());
    }

    private void assertNoCanvas(String projectId) {
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:id")
                .param("id", UUID.fromString(projectId)).query(Integer.class).single()).isZero();
    }

    private MockMvc mvc() {
        return webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static UsernamePasswordAuthenticationToken auth(AdminPrincipal owner) {
        return UsernamePasswordAuthenticationToken.authenticated(owner, null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static String assetPath(String projectId, String assetId, String suffix) {
        return "/api/v1/projects/" + projectId + "/assets/" + assetId + "/" + suffix;
    }

    private static HttpServer fakeProvider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/images/generations", exchange -> {
                GENERATIONS.incrementAndGet();
                LAST_GENERATION.set(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
                if (DROP_NEXT.getAndSet(false)) {
                    exchange.close();
                    return;
                }
                if (REJECT_NEXT.getAndSet(false)) {
                    respond(exchange, 400, "{\"error\":{\"type\":\"image_generation_user_error\","
                            + "\"code\":\"moderation_blocked\",\"message\":\"Synthetic rejection\"}}");
                    return;
                }
                successful(exchange);
            });
            server.createContext("/v1/images/edits", exchange -> {
                EDITS.incrementAndGet();
                LAST_EDIT.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                successful(exchange);
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void successful(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-secret");
        respond(exchange, 200, "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png()) + "\"}]}");
    }

    private static void respond(HttpExchange exchange, int code, String payload) throws IOException {
        byte[] response = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, response.length);
        try (var body = exchange.getResponseBody()) {
            body.write(response);
        }
        exchange.close();
    }

    private static byte[] png() throws IOException {
        return png(2);
    }

    private static byte[] png(int side) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        }
    }

    private record Fixture(String projectId, String capabilityId, int capabilityVersion) {
    }
}

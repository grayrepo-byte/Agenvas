package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.support.ComfyWorkflowFixture;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic ComfyUI HTTP server and real PostgreSQL; this does not prove GPU generation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.mode=configured",
        "agenvas.provider.media.scheduler-enabled=false"})
class ComfyUiWorkflowPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final String PROXY_PREFIX = "/proxy/synthetic-key";
    private static final long SYNTHETIC_PROMPT_ID_BASE = 2_100_000_000_000_000_000L;
    private static final ConcurrentHashMap<String, JsonNode> GRAPHS = new ConcurrentHashMap<>();
    private static final AtomicInteger SUBMISSIONS = new AtomicInteger();
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static final AtomicInteger UPLOADS = new AtomicInteger();
    private static final HttpServer SERVER = server();
    private static final Path STORAGE = storage();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE::toString);
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll static void close() throws IOException {
        SERVER.stop(0);
        try (var files = Files.walk(STORAGE)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired DirectMediaTaskService direct;
    @Autowired TaskService tasks;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaExecutionWorker worker;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired LlmTurnCheckpointService checkpoints;
    @Autowired LlmProtocolCodec codec;
    @Autowired ToolExecutionService toolExecutor;

    @Test void importedGraphsPublishThroughAdminApiExecutePinnedMappingsAndArchiveSelectedOutput() throws Exception {
        var owner = identities.setup("workflow-admin", "synthetic-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var user = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var connection = catalog.createConnection("Synthetic ComfyUI", "http://127.0.0.1:" + SERVER.getAddress().getPort() + PROXY_PREFIX);
        var settings = ComfyWorkflowFixture.settings(mapper, false, true);
        var workflow = settings.withObject("comfyWorkflow");
        workflow.withObject("graph").putObject("23").put("class_type", "LoadImage").putObject("inputs").put("image", "second.png");
        workflow.withObject("graph").withObject("14").withObject("inputs").putArray("secondReference").add("23").add(0);
        workflow.withArray("bindings").addObject().put("nodeId", "23").put("inputName", "image").put("source", "REFERENCE_IMAGE").put("referenceIndex", 1);
        String previewPath = "/api/v1/settings/media-connections/" + connection.id() + "/comfyui/preview";
        String importBody = mapper.createObjectNode().put("workflowJson", settings.at("/comfyWorkflow/graph").toString()).toString();
        mvc.perform(post(previewPath).with(csrf()).contentType("application/json").content(importBody)).andExpect(status().isUnauthorized());
        mvc.perform(post(previewPath).with(user).with(csrf()).contentType("application/json").content(importBody)).andExpect(status().isForbidden());
        mvc.perform(post(previewPath).with(auth).with(csrf()).contentType("application/json").content(importBody)).andExpect(status().isOk());
        assertThat(GRAPHS).isEmpty();
        assertThat(catalog.capabilities(connection.id())).isEmpty();
        String publishPath = "/api/v1/settings/media-connections/" + connection.id() + "/capabilities";
        var payload = mapper.createObjectNode().put("name", "Imported image").put("adapterId", "COMFY_IMAGE_V1").set("settings", settings);
        mvc.perform(post(publishPath).with(auth).with(csrf()).header("Idempotency-Key", "imported-create").contentType("application/json").content(payload.toString())).andExpect(status().isOk());
        mvc.perform(post(publishPath).with(auth).with(csrf()).header("Idempotency-Key", "imported-create").contentType("application/json").content(payload.toString())).andExpect(status().isOk());
        var capability = catalog.capabilities(connection.id()).getFirst();
        assertThat(catalog.capabilities(connection.id())).hasSize(1);
        assertThatThrownBy(() -> catalog.updateCapability(connection.id(), capability.id(), capability.version(),
                "Model-only edit", true, "COMFY_IMAGE_V1", mapper.createObjectNode().put("checkpoint", "synthetic.safetensors")))
                .isInstanceOf(ApiProblemException.class);
        assertThat(catalog.candidates(Task.Kind.IMAGE_GENERATION, 0).getFirst().settings().has("comfyWorkflow")).isFalse();
        payload.put("name", "Different payload");
        mvc.perform(post(publishPath).with(auth).with(csrf()).header("Idempotency-Key", "imported-create").contentType("application/json").content(payload.toString())).andExpect(status().isConflict());
        mvc.perform(post(publishPath).with(auth).with(csrf()).header("Idempotency-Key", "old-model-create").contentType("application/json")
                .content("{\"name\":\"Old model-only\",\"adapterId\":\"COMFY_IMAGE_V1\",\"settings\":{\"checkpoint\":\"synthetic.safetensors\"}}"))
                .andExpect(status().isUnprocessableEntity());
        catalog.setDefault(Task.Kind.IMAGE_GENERATION, catalog.defaultVersion(Task.Kind.IMAGE_GENERATION), capability.id());
        var project = projects.create(owner.userId(), "Workflow fixture", Project.AspectRatio.LANDSCAPE_16_9);
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Image", null);
        UUID item = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), card.artifact().id());
        var incomplete = CanvasMediaFixture.save(drafts, owner.userId(), project.id(), item, 0, "Mapped prompt", null, null, null);
        assertThatThrownBy(() -> direct.preflight(owner.userId(), project.id(), card.artifact().id(), item, incomplete.version())).isInstanceOf(ApiProblemException.class);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var content = mapper.createObjectNode().put("assetId", assetId.toString()).put("sourceType", "UPLOAD");
        var reference = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Reference", content);
        UUID secondAsset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var secondReference = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Second reference",
                mapper.createObjectNode().put("assetId", secondAsset.toString()).put("sourceType", "UPLOAD"));
        var draft = drafts.save(owner.userId(), project.id(), item, incomplete.version(), "Mapped prompt",
                mapper.createObjectNode(), null, capability.id(), null, List.of(
                        new MediaDraftService.SaveMediaInput(reference.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#7C3AED"),
                        new MediaDraftService.SaveMediaInput(secondReference.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#0EA5E9")), List.of(), null);
        var task = direct.run(owner.userId(), project.id(), card.artifact().id(), item, draft.version(), "imported-image-run");
        assertThat(task.input().at("/mediaInput/providerParameters/height").asInt()).isEqualTo(360);
        assertThat(worker.submitOnce("workflow-submit")).isEqualTo(1);
        var submitted = tasks.get(owner.userId(), project.id(), task.id());
        String promptId = submitted.providerRequestId();
        assertThat(submitted.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(promptId).isEqualTo(Long.toString(SYNTHETIC_PROMPT_ID_BASE + 1));
        assertThat(tasks.listProviderAttempts(owner.userId(), project.id(), task.id()))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.providerRequestId()).isEqualTo(promptId);
                    assertThat(attempt.requestKey().toString()).isNotEqualTo(promptId);
                });
        assertThat(jdbc.sql("select provider_request_id from call_log where task_id = :id and operation = 'SUBMIT'")
                .param("id", task.id()).query(String.class).single()).isEqualTo(promptId);
        var graph = GRAPHS.get(promptId);
        assertThat(graph.at("/11/inputs/text").asText()).isEqualTo("Mapped prompt");
        assertThat(graph.at("/13/inputs/image").asText()).isEqualTo("uploaded-1.png");
        assertThat(graph.at("/23/inputs/image").asText()).isEqualTo("uploaded-2.png");
        assertThat(graph.at("/12/inputs/batch_size").asInt()).isEqualTo(1);
        assertThat(UPLOADS).hasValue(2);
        settings.withObject("comfyWorkflow").withObject("graph").withObject("14").withObject("inputs").put("steps", 40);
        catalog.updateCapability(connection.id(), capability.id(), capability.version(), "Updated image", true, "COMFY_IMAGE_V1", settings);
        assertThat(catalog.settings(tasks.mediaBinding(task).orElseThrow()).at("/comfyWorkflow/graph/14/inputs/steps").asInt()).isEqualTo(20);
        due(task.id());
        assertThat(worker.pollOnce("workflow-poll")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), task.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(GRAPHS).hasSize(1);
        assertThat(SUBMISSIONS).hasValue(1);
        assertThat(QUERIES).hasValue(1);

        var video = catalog.publishCapability(connection.id(), "Imported video", "COMFY_VIDEO_V1", ComfyWorkflowFixture.settings(mapper, true, false));
        catalog.setDefault(Task.Kind.VIDEO_GENERATION, catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), video.id());
        var videoCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Video", null);
        UUID videoItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), videoCard.artifact().id());
        var videoDraft = drafts.save(owner.userId(), project.id(), videoItem, 0, "Video prompt",
                mapper.createObjectNode(), 8, video.id(), MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        var videoTask = direct.run(owner.userId(), project.id(), videoCard.artifact().id(), videoItem, videoDraft.version(), "imported-video-run");
        assertThat(worker.submitOnce("workflow-video-submit")).isEqualTo(1);
        String videoPrompt = tasks.get(owner.userId(), project.id(), videoTask.id()).providerRequestId();
        assertThat(videoPrompt).isEqualTo(Long.toString(SYNTHETIC_PROMPT_ID_BASE + 2));
        assertThat(GRAPHS.get(videoPrompt).at("/14/inputs/frames").asInt()).isEqualTo(193);
        assertThat(GRAPHS.get(videoPrompt).at("/14/inputs/fps").asInt()).isEqualTo(24);
        assertThat(UPLOADS).hasValue(2);
        due(videoTask.id());
        assertThat(worker.pollOnce("workflow-video-poll")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), videoTask.id()).status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(SUBMISSIONS).hasValue(2);
        assertThat(QUERIES).hasValue(2);

        // A no-prompt workflow uses named slots and explicitly declared scalar inputs,
        // while the durable task keeps its original values after the working draft changes.
        var extendedSettings = ComfyWorkflowFixture.settings(mapper, false, true);
        var extendedWorkflow = extendedSettings.withObject("comfyWorkflow");
        extendedWorkflow.withArray("bindings").remove(0);
        extendedWorkflow.withArray("bindings").remove(2); // Expose seed as a scalar instead of random semantic binding.
        extendedWorkflow.withObject("graph").putObject("23").put("class_type", "LoadImage").putObject("inputs").put("image", "second.png");
        extendedWorkflow.withObject("graph").withObject("14").withObject("inputs").putArray("secondReference").add("23").add(0);
        extendedWorkflow.withObject("graph").withObject("14").withObject("inputs").put("lora", "synthetic-lora.safetensors");
        extendedWorkflow.withArray("bindings").addObject().put("nodeId", "23").put("inputName", "image").put("source", "REFERENCE_IMAGE").put("referenceIndex", 1);
        var extendedFields = extendedWorkflow.putArray("parameters");
        extendedFields.addObject().put("key", "stepCount").put("label", "Steps").put("type", "INTEGER")
                .put("nodeId", "14").put("fieldName", "steps").put("minimum", 1).put("maximum", 50);
        extendedFields.addObject().put("key", "seedValue").put("label", "Seed").put("type", "INTEGER")
                .put("nodeId", "12").put("fieldName", "seed").put("minimum", 0);
        extendedFields.addObject().put("key", "loraModel").put("label", "LoRA").put("type", "STRING")
                .put("nodeId", "14").put("fieldName", "lora");
        var extended = catalog.publishCapability(connection.id(), "Named ComfyUI inputs", "COMFY_IMAGE_V1", extendedSettings);
        var publicInputs = catalog.publishedCandidates().stream().filter(candidate -> candidate.binding().capabilityId().equals(extended.id()))
                .findFirst().orElseThrow().settings();
        assertThat(publicInputs.has("comfyWorkflow")).isFalse();
        assertThat(publicInputs.path("comfyInputs")).hasSize(5);
        assertThat(publicInputs.path("comfyInputs").toString()).doesNotContain("synthetic prompt").doesNotContain("conditioning");
        var extendedCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Named slots", null);
        UUID extendedItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), extendedCard.artifact().id());
        var slotParameters = mapper.createObjectNode();
        slotParameters.putObject("dynamicValues").put("reference_1", reference.resourceDefaultVersion().id().toString());
        var uniqueReferences = List.of(new MediaDraftService.SaveMediaInput(reference.resourceDefaultVersion().id(),
                MediaDraft.InputRole.REFERENCE, "#7C3AED"));
        var partial = drafts.save(owner.userId(), project.id(), extendedItem, 0, "", slotParameters,
                null, extended.id(), null, uniqueReferences, List.of(), null);
        assertThatThrownBy(() -> direct.preflight(owner.userId(), project.id(), extendedCard.artifact().id(), extendedItem, partial.version()))
                .isInstanceOf(ApiProblemException.class);
        slotParameters.withObject("dynamicValues").put("reference_0", reference.resourceDefaultVersion().id().toString())
                .put("stepCount", 12).put("seedValue", 321).put("loraModel", "synthetic-other.safetensors");
        var complete = drafts.save(owner.userId(), project.id(), extendedItem, partial.version(), "", slotParameters,
                null, extended.id(), null, uniqueReferences, List.of(), null);
        var invalidParameters = slotParameters.deepCopy();
        invalidParameters.withObject("dynamicValues").put("arbitraryTarget", "replacement");
        assertThatThrownBy(() -> drafts.save(owner.userId(), project.id(), extendedItem, complete.version(), "", invalidParameters,
                null, extended.id(), null, uniqueReferences, List.of(), null)).isInstanceOf(ApiProblemException.class);
        var namedTask = direct.run(owner.userId(), project.id(), extendedCard.artifact().id(), extendedItem, complete.version(), "named-comfy-run");
        assertThat(namedTask.input().at("/mediaInput/providerParameters/dynamicValues/stepCount").asInt()).isEqualTo(12);
        assertThat(namedTask.input().at("/mediaInput/parameters/dynamicValues/seedValue").asInt()).isEqualTo(321);
        slotParameters.withObject("dynamicValues").put("stepCount", 22);
        drafts.save(owner.userId(), project.id(), extendedItem, complete.version(), "", slotParameters,
                null, extended.id(), null, uniqueReferences, List.of(), null);
        int uploadsBeforeNamed = UPLOADS.get();
        assertThat(worker.submitOnce("named-comfy-submit")).isEqualTo(1);
        String namedPrompt = tasks.get(owner.userId(), project.id(), namedTask.id()).providerRequestId();
        assertThat(namedPrompt).isEqualTo(Long.toString(SYNTHETIC_PROMPT_ID_BASE + 3));
        JsonNode namedGraph = GRAPHS.get(namedPrompt);
        assertThat(namedGraph.at("/11/inputs/text").asText()).isEqualTo("synthetic prompt");
        assertThat(namedGraph.at("/14/inputs/steps").asInt()).isEqualTo(12);
        assertThat(namedGraph.at("/12/inputs/seed").asInt()).isEqualTo(321);
        assertThat(namedGraph.at("/14/inputs/lora").asText()).isEqualTo("synthetic-other.safetensors");
        assertThat(namedGraph.at("/13/inputs/image")).isEqualTo(namedGraph.at("/23/inputs/image"));
        assertThat(UPLOADS.get() - uploadsBeforeNamed).isEqualTo(1);
        due(namedTask.id());
        assertThat(worker.pollOnce("named-comfy-poll")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), namedTask.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(drafts.get(owner.userId(), project.id(), extendedItem).parameters().at("/dynamicValues/stepCount").asInt()).isEqualTo(22);

        var agent = agents.create(owner.userId(), project.id(), "Catalog reader", "Read the published media inputs", List.of());
        var queuedRun = runs.create(owner.userId(), project.id(), agent.id(), "List media inputs", "comfy-catalog-run").run();
        var run = runs.transition(owner.userId(), project.id(), queuedRun.id(), queuedRun.version(), AgentRun.Status.RUNNING);
        int modelVersion = run.policySnapshot().path("modelConfigVersion").asInt();
        String modelSource = run.policySnapshot().path("modelConfigSource").asText();
        checkpoints.reserve(owner.userId(), project.id(), run.id(), 0, modelVersion, modelSource,
                codec.request(List.of(new UserMessage("List the media inputs")), List.of()));
        var response = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                "comfy-catalog-call", "function", "list_media_capabilities", "{}"))).build();
        checkpoints.saveResponse(owner.userId(), project.id(), run.id(), 0, modelVersion,
                codec.response(new ChatResponse(List.of(new Generation(response)))));
        long taskCountBeforeTool = jdbc.sql("select count(*) from task").query(Long.class).single();
        var trusted = new TrustedToolContext(owner.userId(), project.id(), run.id());
        JsonNode toolResult = toolExecutor.execute(trusted, 0, "comfy-catalog-call");
        JsonNode listed = java.util.stream.StreamSupport.stream(toolResult.path("capabilities").spliterator(), false)
                .filter(entry -> entry.path("capabilityId").asText().equals(extended.id().toString())).findFirst().orElseThrow();
        assertThat(listed.path("fields")).hasSize(5);
        assertThat(listed.toString()).doesNotContain("\"graph\"").doesNotContain("synthetic prompt")
                .doesNotContain("filename_prefix").doesNotContain("127.0.0.1");
        assertThat(toolExecutor.execute(trusted, 0, "comfy-catalog-call")).isEqualTo(toolResult);
        assertThat(jdbc.sql("select count(*) from task").query(Long.class).single()).isEqualTo(taskCountBeforeTool);
    }

    private void due(UUID taskId) {
        jdbc.sql("update task set next_action_at = now() - interval '1 second' where id = :id").param("id", taskId).update();
    }

    private static Path storage() {
        try { return Files.createTempDirectory("agenvas-comfy-workflow-it-"); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(PROXY_PREFIX + "/upload/image", exchange -> { int index = UPLOADS.incrementAndGet(); exchange.getRequestBody().readAllBytes(); respond(exchange, "{\"name\":\"uploaded-" + index + ".png\",\"type\":\"input\",\"subfolder\":\"\"}"); });
            server.createContext(PROXY_PREFIX + "/prompt", exchange -> {
                exchange.getResponseHeaders().add("Location", "/submit/prompt");
                exchange.sendResponseHeaders(307, -1);
                exchange.close();
            });
            server.createContext("/submit/prompt", exchange -> {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                var body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                UUID requestKey = UUID.fromString(body.path("prompt_id").asText());
                assertThat(body.path("client_id").asText()).isEqualTo(requestKey.toString());
                String id = Long.toString(SYNTHETIC_PROMPT_ID_BASE + SUBMISSIONS.incrementAndGet());
                GRAPHS.put(id, body.path("prompt"));
                respond(exchange, "{\"prompt_id\":\"" + id + "\"}");
            });
            server.createContext(PROXY_PREFIX + "/history/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring((PROXY_PREFIX + "/history/").length());
                exchange.getResponseHeaders().add("Location", "/query/history/" + id);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/query/history/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/query/history/".length());
                assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                assertThat(GRAPHS).containsKey(id);
                QUERIES.incrementAndGet();
                if ("SaveVideo".equals(GRAPHS.get(id).at("/99/class_type").asText())) {
                    respond(exchange, "{}");
                    return;
                }
                // Native proxies can report a terminal status without ComfyUI's optional completed flag.
                respond(exchange, "{\"" + id + "\":{\"status\":{\"status_str\":\"success\"},\"outputs\":{\"99\":{\"images\":[{\"filename\":\"preview.png\",\"type\":\"temp\"},{\"filename\":\"image.png\",\"type\":\"output\",\"subfolder\":\"render/day\"},{\"filename\":\"unused.png\",\"type\":\"output\"}]}}}}");
            });
            server.createContext(PROXY_PREFIX + "/view", exchange -> {
                assertThat(exchange.getRequestURI().getRawQuery()).contains("subfolder=render%2Fday");
                assertThat(exchange.getRequestURI().getRawQuery()).contains("filename=image.png");
                exchange.getResponseHeaders().add("Location", "/archived/image.png");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/archived/image.png", exchange -> {
                assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                assertThat(exchange.getRequestHeaders()).doesNotContainKeys("Authorization", "Cookie", "Referer");
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
                exchange.sendResponseHeaders(200, bytes.size());
                try (var stream = exchange.getResponseBody()) { stream.write(bytes.toByteArray()); }
            });
            server.start(); return server;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static void respond(HttpExchange exchange, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}

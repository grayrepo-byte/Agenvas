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
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
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
    private static final ConcurrentHashMap<UUID, JsonNode> GRAPHS = new ConcurrentHashMap<>();
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

    @Test void importedGraphsPublishThroughAdminApiExecutePinnedMappingsAndArchiveSelectedOutput() throws Exception {
        var owner = identities.setup("workflow-admin", "synthetic-password-123");
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        var user = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var connection = catalog.createConnection("Synthetic ComfyUI", "http://127.0.0.1:" + SERVER.getAddress().getPort());
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
        UUID promptId = UUID.fromString(submitted.providerRequestId());
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

        var video = catalog.publishCapability(connection.id(), "Imported video", "COMFY_VIDEO_V1", ComfyWorkflowFixture.settings(mapper, true, false));
        catalog.setDefault(Task.Kind.VIDEO_GENERATION, catalog.defaultVersion(Task.Kind.VIDEO_GENERATION), video.id());
        var videoCard = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Video", null);
        UUID videoItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), videoCard.artifact().id());
        var videoDraft = drafts.save(owner.userId(), project.id(), videoItem, 0, "Video prompt",
                mapper.createObjectNode(), 8, video.id(), MediaDraft.VideoInputMode.TEXT, List.of(), List.of(), null);
        var videoTask = direct.run(owner.userId(), project.id(), videoCard.artifact().id(), videoItem, videoDraft.version(), "imported-video-run");
        assertThat(worker.submitOnce("workflow-video-submit")).isEqualTo(1);
        UUID videoPrompt = UUID.fromString(tasks.get(owner.userId(), project.id(), videoTask.id()).providerRequestId());
        assertThat(GRAPHS.get(videoPrompt).at("/14/inputs/frames").asInt()).isEqualTo(193);
        assertThat(GRAPHS.get(videoPrompt).at("/14/inputs/fps").asInt()).isEqualTo(24);
        assertThat(UPLOADS).hasValue(2);
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
            server.createContext("/upload/image", exchange -> { int index = UPLOADS.incrementAndGet(); exchange.getRequestBody().readAllBytes(); respond(exchange, "{\"name\":\"uploaded-" + index + ".png\",\"type\":\"input\",\"subfolder\":\"\"}"); });
            server.createContext("/prompt", exchange -> {
                var body = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                UUID id = UUID.fromString(body.path("prompt_id").asText());
                GRAPHS.put(id, body.path("prompt"));
                respond(exchange, "{\"prompt_id\":\"" + id + "\"}");
            });
            server.createContext("/history/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/history/".length());
                respond(exchange, "{\"" + id + "\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},\"outputs\":{\"99\":{\"images\":[{\"filename\":\"image.png\",\"type\":\"output\",\"subfolder\":\"render/day\"}]}}}}");
            });
            server.createContext("/view", exchange -> {
                assertThat(exchange.getRequestURI().getRawQuery()).contains("subfolder=render%2Fday");
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

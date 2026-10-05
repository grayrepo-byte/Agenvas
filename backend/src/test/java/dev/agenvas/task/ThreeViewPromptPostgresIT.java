package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.settings.application.PromptService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and a loopback fake OpenAI API with synthetic PNGs; no real Provider call. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class ThreeViewPromptPostgresIT {
    private static final String SETTINGS_PATH = "/api/v1/settings/prompts";
    private static final Path STORAGE = temporaryStorage();
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = startServer();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static AdminPrincipal owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE::toString);
        registry.add("agenvas.credentials.master-key-base64",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired DirectMediaTaskService directMedia;
    @Autowired MediaCapabilityService capabilities;
    @Autowired MediaFunctionService functions;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired PromptService prompts;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        if (owner == null) owner = identities.setup("synthetic-three-view-admin", "synthetic-three-view-password");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        REQUESTS.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "CHARACTER, image.three-view.character, full-body character turnaround",
            "FACE, image.three-view.face, facial turnaround",
            "PROP, image.three-view.prop, prop turnaround",
            "SCENE_GRID, image.three-view.scene-grid, 2 by 2 environment reference grid"
    })
    void editableBuiltInPromptIsFrozenForReplayAndProviderExecution(String type, String key, String originalText)
            throws Exception {
        var preset = prompts.require(key, PromptService.Kind.FUNCTION);
        assertThat(preset.builtIn()).isTrue();
        assertThat(preset.content()).contains(originalText);
        String listed = mvc.perform(get(SETTINGS_PATH).with(authentication(asAdmin())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(listed).path("items"))
                .anySatisfy(row -> assertThat(row.path("key").asText()).isEqualTo(key));

        String firstContent = "Synthetic managed " + type + " instruction A";
        JsonNode saved = save(preset, firstContent);
        // The regular management endpoint supplies CAS and protects the function's stable identity.
        mvc.perform(put(SETTINGS_PATH + "/" + preset.id()).with(authentication(asAdmin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update(preset, firstContent)))
                .andExpect(status().isConflict());
        mvc.perform(delete(SETTINGS_PATH + "/" + preset.id()).with(authentication(asAdmin())).with(csrf())
                .param("expectedVersion", saved.path("version").asText())).andExpect(status().isConflict());

        var connection = capabilities.createConnection("synthetic-views-" + type.toLowerCase(Locale.ROOT),
                "Synthetic image provider", "OPENAI",
                "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1", "synthetic-image-key");
        var capability = capabilities.publishCapability(connection.id(), "Synthetic image editing",
                "OPENAI_GPT_IMAGE_2", mapper.createObjectNode());
        var function = functions.list().stream().filter(entry -> entry.operation() == MediaFunction.IMAGE_THREE_VIEW).findFirst().orElseThrow();
        functions.update(MediaFunction.IMAGE_THREE_VIEW, function.version(), capability.id());
        long functionVersion = function.version() + 1;
        var project = projects.create(owner.userId(), "Synthetic " + type, Project.AspectRatio.LANDSCAPE_16_9);
        var assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Synthetic source",
                mapper.createObjectNode().put("assetId", assetId.toString()).put("sourceType", "UPLOAD"));
        var cardId = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project.id(), image.artifact().id());
        var parameters = mapper.createObjectNode().put("threeViewType", type)
                .put("aspectRatio", type.equals("SCENE_GRID") ? "1:1" : "16:9");
        String guidance = "retain synthetic accessories";
        Task accepted = directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                cardId, image.resourceDefaultVersion().id(), 0, ImageOperation.THREE_VIEW, guidance,
                functionVersion, 1, List.of(), null, parameters, "synthetic-first-views");
        String firstPrompt = firstContent + " Subject guidance: " + guidance;
        assertThat(accepted.input().path("schemaVersion").asInt()).isEqualTo(8);
        assertThat(accepted.input().path("promptKey").asText()).isEqualTo(key);
        assertThat(accepted.input().path("promptVersion").asLong()).isEqualTo(saved.path("version").asLong());
        assertFrozenPrompt(accepted, firstPrompt);

        String secondContent = "Synthetic managed " + type + " instruction B";
        var beforeSecondEdit = prompts.require(key, PromptService.Kind.FUNCTION);
        save(beforeSecondEdit, secondContent);
        Task replay = directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                cardId, image.resourceDefaultVersion().id(), 0, ImageOperation.THREE_VIEW, guidance,
                functionVersion, 1, List.of(), null, parameters, "synthetic-first-views");
        assertThat(replay.id()).isEqualTo(accepted.id());
        assertFrozenPrompt(replay, firstPrompt);
        assertThatThrownBy(() -> directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                cardId, image.resourceDefaultVersion().id(), 0, ImageOperation.THREE_VIEW, "changed instruction",
                functionVersion, 1, List.of(), null, parameters, "synthetic-first-views"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("DIRECT_MEDIA_CONFLICT"));
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(2);

        assertThat(worker.submitOnce("synthetic-frozen-views")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), accepted.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(REQUESTS).containsExactly(firstPrompt);

        Task next = directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                cardId, image.resourceDefaultVersion().id(), 0, ImageOperation.THREE_VIEW, "",
                functionVersion, 1, List.of(), null, parameters, "synthetic-next-views");
        assertFrozenPrompt(next, secondContent);
        assertThat(next.input().path("promptVersion").asLong()).isEqualTo(beforeSecondEdit.version() + 1);
        assertThat(worker.submitOnce("synthetic-updated-views")).isEqualTo(1);
        assertThat(tasks.get(owner.userId(), project.id(), next.id()).status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getLast()).isEqualTo(secondContent);
    }

    private void assertFrozenPrompt(Task task, String expected) {
        assertThat(task.input().path("prompt").asText()).isEqualTo(expected);
        assertThat(task.input().path("mediaInput").path("prompt").asText()).isEqualTo(expected);
        assertThat(task.input().path("mediaInput").path("renderedPrompt").asText()).isEqualTo(expected);
    }

    private JsonNode save(PromptService.Prompt preset, String content) throws Exception {
        return mapper.readTree(mvc.perform(put(SETTINGS_PATH + "/" + preset.id())
                .with(authentication(asAdmin())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(update(preset, content))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String update(PromptService.Prompt preset, String content) {
        return mapper.createObjectNode().put("expectedVersion", preset.version()).put("name", preset.name())
                .put("description", preset.description()).put("content", content).toString();
    }

    private UsernamePasswordAuthenticationToken asAdmin() {
        return new UsernamePasswordAuthenticationToken(owner, "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/images/edits", exchange -> {
                String multipart = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                int field = multipart.indexOf("name=\"prompt\"");
                int headersEnd = multipart.indexOf("\r\n\r\n", field);
                int contentEnd = multipart.indexOf("\r\n--", headersEnd + "\r\n\r\n".length());
                if (field < 0 || headersEnd < 0 || contentEnd < 0) throw new IOException("Missing synthetic multipart prompt");
                REQUESTS.add(multipart.substring(headersEnd + "\r\n\r\n".length(), contentEnd));
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
                byte[] response = ("{\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("agenvas-three-view-test-", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        SERVER.stop(0);
        try (var paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}

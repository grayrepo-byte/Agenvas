package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.domain.MediaDraft.VideoInputMode;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository.Capability;
import dev.agenvas.task.application.VideoStudioService;
import dev.agenvas.testing.ImageAssetFixture;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class VideoStudioPostgresIT {
    private static final String BASE = "/api/v1/video-studio";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired MediaCapabilityService capabilities;
    @Autowired VideoStudioService studio;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    private MockMvc mvc;
    private UUID owner;
    private RequestPostProcessor auth;
    private UUID connection;

    @BeforeEach
    void setup() {
        String login = "studio-video-admin";
        var principal = jdbc.sql("select id from app_user where login_name = ?").param(login).query(UUID.class)
                .optional().map(id -> new AdminPrincipal(id, login))
                .orElseGet(() -> identities.setup(login, "synthetic-studio-password"));
        owner = principal.userId();
        auth = authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        connection = capabilities.createConnection("Video Mock " + UUID.randomUUID(), null).id();
    }

    @Test
    void exposesOnlyUsableModelsAndActualFrameCapacity() throws Exception {
        var noFrames = publish("video-text", 0);
        var firstOnly = publish("video-start", 1);
        var both = publish("video-bookends", 2);
        var image = capabilities.publishCapability(connection, "Only images", "MOCK_IMAGE");
        JsonNode catalog = payload(mvc.perform(get(BASE + "/context").with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("capabilities");
        assertThat(catalog.valueStream().map(item -> item.path("capabilityId").asText()).toList())
                .contains(noFrames.id().toString(), firstOnly.id().toString(), both.id().toString())
                .doesNotContain(image.id().toString());
        var text = capability(catalog, noFrames.id());
        var first = capability(catalog, firstOnly.id());
        var ends = capability(catalog, both.id());
        assertThat(text.path("modes").valueStream().map(JsonNode::asText).toList()).containsExactly("TEXT");
        assertThat(first.path("modes").valueStream().map(JsonNode::asText).toList())
                .containsExactly("TEXT", "START_END", "GENERAL_REFERENCE");
        assertThat(first.path("supportsEndFrame").asBoolean()).isFalse();
        assertThat(ends.path("supportsEndFrame").asBoolean()).isTrue();
        assertThat(first.path("defaultDurationSeconds").asInt()).isEqualTo(5);
        assertThat(first.path("defaultAspectRatio").asText()).isEqualTo("9:16");

        mvc.perform(get(BASE + "/context")).andExpect(status().isUnauthorized());
        var frame = upload("png");
        assertThat(frame.path("height").asInt()).isEqualTo(6);
        assertThat(total("asset", frame.path("projectId").asText())).isPositive();
        long assets = total("asset", frame.path("projectId").asText());
        var gif = new MockMultipartFile("file", "moving.gif", "image/gif", ImageAssetFixture.encoded("gif", 2, 2));
        var bad = new MockMultipartFile("file", "invalid.png", "image/png", new byte[] {0, 12, 34});
        // Tiny synthetic two-frame images, encoded locally with ffmpeg and img2webp.
        var apng = animatedFrame("png", "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAACXBIWXMAAAABAAAAAQBPJcTW"
                + "AAAACGFjVEwAAAACAAAAAPONk3AAAAAaZmNUTAAAAAAAAAACAAAAAgAAAAAAAAAAAAEAAgAA5keNuAAAABJJREFUeJxjZGD4y8DA"
                + "wMIABgALFwED35loQAAAABpmY1RMAAAAAQAAAAEAAAABAAAAAAAAAAAAAQACAADPH4u8AAAAEGZkQVQAAAACeJxjZGD4CwABBQD/"
                + "nlZckAAAAABJRU5ErkJggg==");
        var webp = animatedFrame("webp", "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAQAAQU5JTQYAAAD/////AABBTk1G"
                + "KAAAAAAAAAAAAAEAAAEAADIAAAJWUDhMDwAAAC8BQAAABxDR/v4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAEAADIAAABWUDhM"
                + "DwAAAC8BQAAABxDlj/4HIqL/AQA=");
        for (var animated : List.of(gif, apng, webp)) {
            var refusal = mvc.perform(multipart(BASE + "/frames").file(animated).with(auth).with(csrf()))
                    .andExpect(status().isUnprocessableContent()).andReturn().getResponse();
            assertThat(payload(refusal.getContentAsString()).path("code").asText()).isEqualTo("ASSET_ANIMATED_FRAME");
        }
        assertThat(mvc.perform(multipart(BASE + "/frames").file(bad).with(auth).with(csrf()))
                .andReturn().getResponse().getStatus()).isBetween(400, 499);
        mvc.perform(multipart(BASE + "/frames").param("url", "https://invalid.example/frame.png")
                .with(auth).with(csrf())).andExpect(status().isBadRequest());
        assertThat(total("asset", frame.path("projectId").asText())).isEqualTo(assets);
        var ask = json(firstOnly.id(), firstOnly.currentVersion(), "START_END");
        send("frame-required", ask, 400);
        ask.put("endFrameVersionId", frame.path("versionId").asText());
        send("end-only", ask, 400);
        ask.put("startFrameVersionId", frame.path("versionId").asText());
        send("tail-exceeds-capacity", ask, 400);
        ask.remove("endFrameVersionId");
        JsonNode received = send("frame-only-valid", ask, 201);
        assertThat(received.at("/startFrame/versionId").asText()).isEqualTo(frame.path("versionId").asText());
        assertThat(received.at("/endFrame").isNull()).isTrue();
        ask.put("startFrameVersionId", UUID.randomUUID().toString());
        send("frame-foreign", ask, 404);
        ask.put("mode", "TEXT");
        send("text-has-image", ask, 400);
        ask.remove("startFrameVersionId");
        send("text-fraction", ask.put("durationSeconds", 4.2), 400);
        send("text-too-short", ask.put("durationSeconds", 3), 400);
        send("text-too-long", ask.put("durationSeconds", 9), 400);
        send("invalid-ratio", ask.put("durationSeconds", 5).put("aspectRatio", "4:3"), 400);
        ask.remove("aspectRatio");
        send("real-node-not-allowed", ask.put("canvasItemId", UUID.randomUUID().toString()), 400);
    }

    @Test
    void freezesOneTaskOnConcurrentFirstAcceptanceAndRetainsItAcrossRepublishing() throws Exception {
        var model = publish("video-replay", 2);
        JsonNode start = upload("png");
        JsonNode end = upload("jpg");
        var cmd = json(model.id(), model.currentVersion(), "START_END").put("aspectRatio", "AUTO")
                .put("startFrameVersionId", start.path("versionId").asText())
                .put("endFrameVersionId", end.path("versionId").asText());
        String project = start.path("projectId").asText();
        long taskCount = total("task", project);
        long reservationCount = reservations(project);
        var gate = new CountDownLatch(1);
        JsonNode accepted;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = java.util.stream.IntStream.range(0, 12).mapToObj(i -> executor.submit(() -> {
                gate.await();
                return send("bookends-replay", cmd, 201);
            })).toList();
            gate.countDown();
            accepted = work.getFirst().get(20, TimeUnit.SECONDS);
            for (var reply : work)
                assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo(accepted);
        }
        assertThat(accepted.path("aspectRatio").asText()).isEqualTo("16:9");
        assertThat(accepted.at("/task/status").asText()).isEqualTo("READY");
        assertThat(accepted.at("/endFrame/versionId").asText()).isEqualTo(end.path("versionId").asText());
        assertThat(total("task", project)).isEqualTo(taskCount + 1);
        assertThat(reservations(project)).isEqualTo(reservationCount + 1);
        assertThat(total("canvas_item", project)).isZero();
        assertThat(total("agent_run", project)).isZero();
        assertThat(total("image_studio_batch", project)).isZero();
        String taskId = accepted.at("/task/id").asText();
        JsonNode frozen = mapper.readTree(jdbc.sql("select input_json::text from task where id = cast(? as uuid)")
                .param(taskId).query(String.class).single());
        assertThat(frozen.at("/mediaInput/providerParameters/aspectRatio").asText()).isEqualTo("16:9");
        assertThat(frozen.at("/mediaInput/images/0/role").asText()).isEqualTo("START_FRAME");
        assertThat(frozen.at("/mediaInput/images/1/role").asText()).isEqualTo("END_FRAME");
        assertThat(frozen.at("/mediaInput/images/1/versionId").asText()).isEqualTo(end.path("versionId").asText());
        send("bookends-replay", cmd.deepCopy().put("prompt", "Another prompt"), 409);
        capabilities.updateCapability(connection, model.id(), model.version(), "Updated video Mock", true,
                "MOCK_VIDEO", mapper.createObjectNode().put("minimumSeconds", 4).put("maximumSeconds", 7)
                        .put("defaultDurationSeconds", 6));
        send("reject-stale-model", cmd, 409);
        assertThat(send("bookends-replay", cmd, 201)).isEqualTo(accepted);
        assertThat(total("task", project)).isEqualTo(taskCount + 1);
        assertThat(reservations(project)).isEqualTo(reservationCount + 1);
    }

    @Test
    void rollbackAndTimestampTiesLeaveAStableOwnerHistory() throws Exception {
        var model = publish("video-page", 2);
        String project = studio.context(owner).projectId().toString();
        long before = total("video_studio_generation", project);
        long priorTasks = total("task", project);
        long priorUsage = reservations(project);
        UUID rolledBack = transactions.execute(tx -> {
            var created = studio.accept(owner, new VideoStudioService.Command(model.id(), model.currentVersion(),
                    "Will roll back", VideoInputMode.TEXT, 5, null, null, null, List.of(), null,
                    mapper.createObjectNode()), "rollback-generation");
            tx.setRollbackOnly();
            return created.id();
        });
        mvc.perform(get(BASE + "/generations/" + rolledBack).with(auth)).andExpect(status().isNotFound());
        assertThat(total("video_studio_generation", project)).isEqualTo(before);
        assertThat(total("task", project)).isEqualTo(priorTasks);
        assertThat(reservations(project)).isEqualTo(priorUsage);
        var cmd = json(model.id(), model.currentVersion(), "TEXT");
        var ids = new HashSet<String>();
        for (int i = 0; i < 3; i++)
            ids.add(send("page-" + i, cmd, 201).path("id").asText());
        jdbc.sql("update video_studio_generation set created_at = '2100-01-01T00:00:00Z' where command_key like 'page-%'")
                .update();
        var page1 = payload(mvc.perform(get(BASE + "/generations").param("limit", "2").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var page2 = payload(mvc.perform(get(BASE + "/generations").param("limit", "1")
                .param("before", page1.path("nextCursor").asText()).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var seen = new HashSet<String>();
        page1.path("items").forEach(item -> seen.add(item.path("id").asText()));
        page2.path("items").forEach(item -> seen.add(item.path("id").asText()));
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void referenceUploadChecksOwnershipCsrfActualKindAndPreservesHistory() throws Exception {
        var model = publish("video-references", 1);
        var file = new MockMultipartFile("file", "reference.png", "image/png", ImageAssetFixture.encoded("png", 8, 6));
        mvc.perform(multipart(BASE + "/references/IMAGE").file(file).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart(BASE + "/references/IMAGE").file(file).with(auth))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/references/VIDEO").file(file).with(auth).with(csrf()))
                .andExpect(status().is4xxClientError());
        var image = payload(mvc.perform(multipart(BASE + "/references/IMAGE").file(file).with(auth).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(image.path("kind").asText()).isEqualTo("IMAGE");
        assertThat(image.path("contentType").asText()).isEqualTo("image/png");
        var request = json(model.id(), model.currentVersion(), "GENERAL_REFERENCE");
        send("empty-general-reference", request, 400);
        var refs = request.putArray("references");
        refs.addObject().put("kind", "IMAGE").put("versionId", image.path("versionId").asText());
        var accepted = send("one-reference", request, 201);
        assertThat(accepted.at("/references/0")).isEqualTo(image);
        assertThat(accepted.path("startFrame").isNull()).isTrue();
        assertThat(send("one-reference", request, 201)).isEqualTo(accepted);
        var response = mvc.perform(get(BASE + "/generations/" + accepted.path("id").asText()).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(payload(response).at("/references/0")).isEqualTo(image);
        send("reference-mode-mismatch", request.deepCopy().put("mode", "TEXT"), 400);
        refs.addObject().put("kind", "IMAGE").put("versionId", UUID.randomUUID().toString());
        send("reference-capacity", request, 400);
        refs.remove(1);
        ((ObjectNode) refs.get(0)).put("versionId", UUID.randomUUID().toString());
        send("reference-foreign", request, 404);
    }

    private Capability publish(String name, int maxFrames) {
        var settings = mapper.createObjectNode().put("minimumSeconds", 4).put("maximumSeconds", 8)
                .put("maxReferenceImages", maxFrames).put("maxReferenceAudios", 0).put("defaultDurationSeconds", 5);
        settings.putObject("defaultParameters").put("aspectRatio", "9:16");
        return capabilities.publishCapability(connection, name, "MOCK_VIDEO", settings);
    }

    private JsonNode capability(JsonNode catalog, UUID id) {
        return catalog.valueStream().filter(item -> id.toString().equals(item.path("capabilityId").asText()))
                .findFirst().orElseThrow();
    }

    private static MockMultipartFile animatedFrame(String format, String base64) {
        return new MockMultipartFile("file", "animated." + format, "image/" + format, Base64.getDecoder().decode(base64));
    }

    private JsonNode upload(String format) throws Exception {
        var file = new MockMultipartFile("file", "frame." + format, "image/" + format,
                ImageAssetFixture.encoded(format, 8, 6));
        return payload(mvc.perform(multipart(BASE + "/frames").file(file).with(auth).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ObjectNode json(UUID capability, int revision, String mode) {
        return mapper.createObjectNode().put("capabilityId", capability.toString()).put("capabilityVersion", revision)
                .put("mode", mode).put("prompt", "A painted sky").put("durationSeconds", 5);
    }

    private JsonNode send(String key, ObjectNode request, int expected) throws Exception {
        return payload(mvc.perform(post(BASE + "/generations").with(auth).with(csrf()).header("Idempotency-Key", key)
                .contentType("application/json").content(request.toString())).andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode payload(String body) {
        return mapper.readTree(body);
    }

    private long total(String table, String project) {
        return jdbc.sql("select count(*) from " + table + " where project_id = cast(? as uuid)")
                .param(project).query(Long.class).single();
    }

    private long reservations(String project) {
        return jdbc.sql("select count(*) from usage_ledger where project_id = cast(? as uuid) and entry_type='RESERVATION'")
                .param(project).query(Long.class).single();
    }
}

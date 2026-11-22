package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft.VideoInputMode;
import dev.agenvas.asset.application.AssetRepository;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MockVideoAdapter;
import dev.agenvas.provider.infrastructure.ArkSeedanceClient;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.VideoStudioService;
import dev.agenvas.task.application.VideoStudioService.GenerationView;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL, task worker, media storage and FFmpeg; Ark traffic is isolated on loopback. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, VideoStudioExecutionPostgresIT.FakeArk.class})
class VideoStudioExecutionPostgresIT {
    private static final String ARK_PATH = "/api/v3/contents/generations/tasks";
    private static final String ARK_ID = "cgt-video-studio-fixture-1";
    private static final List<JsonNode> CREATES = new CopyOnWriteArrayList<>();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean UNCERTAIN = new AtomicBoolean();
    private static volatile byte[] providerVideo;
    private static final HttpServer ARK = startArk();
    private static UUID owner;
    @TempDir
    static Path root;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", root::toString);
        r.add("agenvas.credentials.master-key-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void stopArk() {
        ARK.stop(0);
    }

    @Autowired IdentityService identities;
    @Autowired VideoStudioService studio;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaExecutionWorker worker;
    @Autowired TaskService tasks;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner tools;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext web;
    @MockitoSpyBean AssetRepository assetRows;
    @MockitoSpyBean TaskRepository taskRows;
    @MockitoSpyBean MockVideoAdapter mockVideo;

    @BeforeEach
    void setup() {
        CREATES.clear();
        QUERIES.clear();
        UNCERTAIN.set(false);
        if (owner == null)
            owner = identities.setup("video-studio-worker", "studio-worker-password-123").userId();
    }

    @Test
    void mockPublishesOriginalTaskAfterFailedFinalTransactionWithoutRenderingAgain() throws Exception {
        UUID start = studio.upload(owner, png(Color.RED)).versionId();
        UUID end = studio.upload(owner, png(Color.BLUE)).versionId();
        var generation = accept(mockCapability(), VideoInputMode.START_END, "1:1", start, end);
        UUID taskId = generation.task().id();
        doThrow(new IllegalStateException("Injected final transaction failure"))
                .when(taskRows).finishSubmitting(any(), any(), any(), any());

        assertThatThrownBy(() -> worker.submitOnce("mock-submit"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Injected final transaction failure");
        assertThat(task(generation).status()).isEqualTo(Task.Status.SUBMITTING);
        assertThat(task(generation).input().at("/mediaInput/images/0/versionId").asText()).isEqualTo(start.toString());
        assertThat(task(generation).input().at("/mediaInput/images/1/versionId").asText()).isEqualTo(end.toString());
        UUID assetId = AssetService.taskVideoAssetId(taskId);
        Path archived = assets.get(owner, generation.projectId(), assetId).path();
        byte[] original = Files.readAllBytes(archived);
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=?").param(taskId).update();
        assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);

        Task done = task(generation);
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        var version = artifacts.requireVersion(owner, generation.projectId(),
                uuid(done.output(), "artifactId"), uuid(done.output(), "artifactVersionId"));
        assertThat(version.content().path("sourceTaskId").asText()).isEqualTo(taskId.toString());
        UUID requestKey = jdbc.sql("select request_key from provider_attempt where task_id=?")
                .param(taskId).query(UUID.class).single();
        UUID mockId = UUID.nameUUIDFromBytes((generation.projectId() + ":" + requestKey)
                .getBytes(StandardCharsets.UTF_8));
        assertThat(version.content().at("/parameters/providerRequestId").asText()).isEqualTo("mock-" + mockId);
        assertThat(uuid(version.content(), "assetId")).isEqualTo(assetId);
        assertThat(Files.readAllBytes(archived)).isEqualTo(original);
        var probe = probe(archived);
        assertThat(probe.at("/streams/0/width").asInt()).isEqualTo(512);
        assertThat(probe.at("/streams/0/height").asInt()).isEqualTo(512);
        assertThat(probe.at("/format/duration").asDouble()).isBetween(3.95, 4.05);
        assertThat(attempts(taskId)).isEqualTo(1);
        assertThat(usage(taskId)).containsExactly("RESERVATION", "SETTLEMENT");
        assertThat(worker.submitOnce("mock-no-resubmit")).isZero();
        assertThat(worker.pollOnce("mock-no-query")).isZero();
        verify(mockVideo, times(1)).submit(any());
        verify(mockVideo, never()).reconcile(any());
        noCanvas(generation.projectId());
    }

    @Test
    void mockWithoutArchivedBytesBecomesUnknownInsteadOfRunningAgain() {
        var generation = accept(mockCapability(), VideoInputMode.TEXT, "16:9", null, null);
        var lease = tasks.claimBoundMedia("mock-interrupted", 1).getFirst();
        tasks.beginSubmission(lease, "mock-interrupted", tasks.mediaBinding(lease).orElseThrow());
        jdbc.sql("update task set lease_until=now()-interval '1 second' where id=?").param(lease.id()).update();
        assertThat(tasks.recoverExpiredSubmissions(10)).isEqualTo(1);
        assertThat(task(generation).status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(worker.submitOnce("mock-no-replacement")).isZero();
        assertThat(attempts(lease.id())).isEqualTo(1);
        assertThat(usage(lease.id())).containsExactly("RESERVATION");
        verify(mockVideo, never()).submit(any());
        verify(mockVideo, never()).reconcile(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"canvasItemId", "sourceCanvasItemId", "runId", "artifactId", "studioGenerationId",
            "executionSurface", "frameRole", "frameVersion", "gifFrame", "targetKind", "taskKind", "scope"})
    void contradictoryInputIsBlockedBeforeAnAttempt(String field) throws Exception {
        boolean wrongScope = field.equals("scope");
        UUID fixtureOwner = wrongScope ? alternateOwner() : owner;
        var start = studio.upload(fixtureOwner, png(Color.RED));
        var capability = mockCapability();
        var generation = studio.accept(fixtureOwner,
                new VideoStudioService.Command(capability.capabilityId(), capability.capabilityVersion(),
                        "Animate the synthetic scene", VideoInputMode.START_END, 4, "9:16", start.versionId(), null),
                UUID.randomUUID().toString());
        UUID taskId = generation.task().id();
        var frozen = (ObjectNode) tasks.get(fixtureOwner, generation.projectId(), taskId).input().deepCopy();
        if (wrongScope) {
            // The production FK prevents changing a scope while any valid Studio command refers to it.
            // A separate canvas project is not needed to model a forged task; remove only this fixture's command.
            jdbc.sql("delete from video_studio_generation where id=?").param(generation.id()).update();
            jdbc.sql("update project set purpose='CANVAS' where id=?").param(generation.projectId()).update();
        } else {
            switch (field) {
                case "executionSurface" -> frozen.put(field, "CANVAS");
                case "frameRole" -> ((ObjectNode) frozen.at("/mediaInput/images/0")).put("role", "END_FRAME");
                case "frameVersion" -> ((ObjectNode) frozen.at("/mediaInput/images/0")).put("versionId", UUID.randomUUID().toString());
                case "gifFrame" -> {
                    var gif = new ByteArrayOutputStream();
                    ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "gif", gif);
                    var asset = assets.archiveImage(owner, generation.projectId(), new ByteArrayInputStream(gif.toByteArray()));
                    var image = artifacts.create(owner, generation.projectId(), Artifact.Kind.IMAGE, "Injected GIF",
                            mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
                    ((ObjectNode) frozen.at("/mediaInput/images/0"))
                            .put("artifactId", image.artifact().id().toString())
                            .put("assetId", asset.id().toString())
                            .put("versionId", image.resourceDefaultVersion().id().toString());
                }
                case "targetKind" -> {
                    jdbc.sql("update task_artifact_target set artifact_id=? where task_id=?")
                            .param(start.artifactId()).param(generation.task().id()).update();
                    frozen.put("artifactId", start.artifactId().toString());
                }
                case "taskKind" -> jdbc.sql("update task set kind='IMAGE_GENERATION' where id=?")
                        .param(taskId).update();
                default -> frozen.put(field, UUID.randomUUID().toString());
            }
            replaceFrozen(generation.task().id(), frozen);
        }
        try {
            assertThat(worker.submitOnce("forged-input")).isEqualTo(1);
            Task blocked = tasks.get(fixtureOwner, generation.projectId(), taskId);
            assertThat(blocked.status()).isEqualTo(Task.Status.BLOCKED);
            assertThat(blocked.errorCode())
                    .isEqualTo(field.equals("taskKind") ? "MEDIA_CAPABILITY_CHANGED" : "TASK_INPUT_STALE");
            assertThat(attempts(blocked.id())).isZero();
            assertThat(usage(blocked.id())).containsExactly("RELEASE", "RESERVATION");
            verify(mockVideo, never()).submit(any());
        } finally {
            if (wrongScope)
                jdbc.sql("update project set purpose='VIDEO_STUDIO' where id=?").param(generation.projectId()).update();
        }
    }

    private UUID alternateOwner() {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, login_name, password_hash, status, created_at) "
                + "values (?, 'studio-forged-scope-owner', 'unused-test-hash', 'DISABLED', now())").param(id).update();
        return id;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerExtrasAndLateResultsAreDistinctImmutableVersionsWithoutCanvas(boolean late) throws Exception {
        var generation = accept(mockCapability(), VideoInputMode.TEXT, "16:9", null, null);
        var lease = tasks.claimBoundMedia("extras-worker", 1).getFirst();
        tasks.beginSubmission(lease, "extras-worker", tasks.mediaBinding(lease).orElseThrow());
        var results = List.of(
                new TaskService.ArchivedProviderResult(0, Artifact.Kind.VIDEO, true, archivedResult(lease, 440)),
                new TaskService.ArchivedProviderResult(1, Artifact.Kind.VIDEO, false, archivedResult(lease, 880)));
        var wrongTarget = (ObjectNode) lease.input().deepCopy();
        wrongTarget.put("sourceCanvasItemId", UUID.randomUUID().toString());
        replaceFrozen(lease.id(), wrongTarget);
        assertThatThrownBy(() -> tasks.succeedWithArtifacts(lease, "extras-worker", results, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TASK_INPUT_STALE"));
        assertThat(versionCount(lease.id())).isZero();
        replaceFrozen(lease.id(), lease.input());
        if (late)
            jdbc.sql("update task set cancel_requested=true where id=?").param(lease.id()).update();
        tasks.succeedWithArtifacts(lease, "extras-worker", results, null);
        Task done = task(generation);
        assertThat(done.status()).isEqualTo(late ? Task.Status.CANCELED : Task.Status.SUCCEEDED);
        JsonNode output = late ? mapper.readTree(jdbc.sql("select output_json::text from task_late_result where task_id=?")
                .param(done.id()).query(String.class).single()) : done.output();
        UUID artifactId = uuid(output, "artifactId");
        assertThat(output.path("selected").asBoolean()).isFalse();
        assertThat(output.path("additionalResults")).hasSize(1);
        JsonNode extra = output.at("/additionalResults/0");
        assertThat(uuid(extra, "artifactId")).isEqualTo(artifactId);
        assertThat(extra.path("selected").asBoolean()).isFalse();
        UUID primary = uuid(output, "artifactVersionId");
        UUID extraVersion = uuid(extra, "artifactVersionId");
        assertThat(extraVersion).isNotEqualTo(primary);
        assertThat(artifacts.requireVersion(owner, lease.projectId(), artifactId, primary).content()).isEqualTo(results.getFirst().content());
        assertThat(artifacts.requireVersion(owner, lease.projectId(), artifactId, extraVersion).content()).isEqualTo(results.getLast().content());
        assertThat(artifacts.get(owner, lease.projectId(), artifactId).resourceDefaultVersion()).isNull();
        assertThat(versionCount(lease.id())).isEqualTo(2);
        assertThat(attempts(lease.id())).isEqualTo(1);
        assertThat(usage(lease.id())).containsExactly("RESERVATION", "SETTLEMENT");
        noCanvas(lease.projectId());
    }

    @Test
    void arkFreezesTextAndFrameRolesAndRetriesOnlyOriginalIdWithAudio() throws Exception {
        providerVideo = video(330);
        var connection = catalog.createConnection("studio-ark-fixture", "Studio Ark fake", "ARK", null, "fixture-key");
        var published = catalog.publishCapability(connection.id(), "Studio Seedance", "ARK_SEEDANCE_2_I2V");
        var capability = studio.context(owner).capabilities().stream()
                .filter(item -> item.capabilityId().equals(published.id())).findFirst().orElseThrow();
        UUID start = studio.upload(owner, png(Color.RED)).versionId();
        UUID end = studio.upload(owner, png(Color.BLUE)).versionId();

        var text = accept(capability, VideoInputMode.TEXT, "16:9", null, null);
        assertThat(worker.submitOnce("ark-text-submit")).isEqualTo(1);
        assertCreate(CREATES.getFirst(), "16:9", 0);
        assertWaiting(text);
        finishArk(text);
        assertThat(QUERIES).containsExactly(ARK_PATH + "/" + ARK_ID, ARK_PATH + "/" + ARK_ID);
        assertArkResult(text);
        assertPrivateContent(text);

        var first = accept(capability, VideoInputMode.START_END, "9:16", start, null);
        assertThat(worker.submitOnce("ark-first-submit")).isEqualTo(1);
        assertCreate(CREATES.get(1), "9:16", 1);
        assertFrame(CREATES.get(1), 1, "first_frame", 720, 1280, Color.RED);
        assertWaiting(first);
        doThrow(new IllegalStateException("Injected READY publication failure")).doCallRealMethod()
                .when(assetRows).insert(any());
        finishArk(first);
        assertWaiting(first);
        assertThat(usage(first.task().id())).containsExactly("RESERVATION");
        assertThat(worker.submitOnce("ark-no-replacement")).isZero();
        finishArk(first);
        assertArkResult(first);

        var both = accept(capability, VideoInputMode.START_END, "1:1", start, end);
        assertThat(worker.submitOnce("ark-both-submit")).isEqualTo(1);
        assertCreate(CREATES.get(2), "1:1", 2);
        assertFrame(CREATES.get(2), 1, "first_frame", 1024, 1024, Color.RED);
        assertFrame(CREATES.get(2), 2, "last_frame", 1024, 1024, Color.BLUE);
        assertWaiting(both);
        finishArk(both);
        assertArkResult(both);
        assertThat(QUERIES).hasSize(5).containsOnly(ARK_PATH + "/" + ARK_ID);

        UNCERTAIN.set(true);
        var unknown = accept(capability, VideoInputMode.TEXT, "16:9", null, null);
        assertThat(worker.submitOnce("ark-uncertain-submit")).isEqualTo(1);
        assertThat(task(unknown).status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(task(unknown).errorCode()).isEqualTo("ARK_CREATE_UNCERTAIN");
        assertThat(task(unknown).providerRequestId()).isNull();
        assertThat(worker.submitOnce("ark-unknown-no-replacement")).isZero();
        assertThat(worker.pollOnce("ark-unknown-no-guessing")).isZero();
        assertThat(CREATES).hasSize(4);
        assertThat(QUERIES).hasSize(5);
        assertThat(attempts(unknown.task().id())).isEqualTo(1);
        assertThat(usage(unknown.task().id())).containsExactly("RESERVATION");
        noCanvas(unknown.projectId());
    }

    private void assertPrivateContent(GenerationView generation) throws Exception {
        var mvc = webAppContextSetup(web).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(owner, "video-studio-worker"), null, List.of()));
        String detail = "/api/v1/video-studio/generations/" + generation.id();
        String raw = mvc.perform(get(detail).with(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("127.0.0.1", "video_url", "original.mp4", ARK_ID);
        JsonNode result = mapper.readTree(raw).at("/task/results/0");
        UUID assetId = uuid(result, "assetId");
        assertThat(result.path("durationMs").asInt()).isBetween(3950, 4050);
        assertThat(result.path("width").asInt()).isEqualTo(320);
        assertThat(result.path("height").asInt()).isEqualTo(180);
        String content = "/api/v1/projects/" + generation.projectId() + "/assets/" + assetId + "/content";
        mvc.perform(get(content).header("Range", "bytes=37-68")).andExpect(status().isUnauthorized());
        byte[] headBody = mvc.perform(head(content).with(auth)).andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", Integer.toString(providerVideo.length)))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(headBody).isEmpty();
        byte[] range = mvc.perform(get(content).with(auth).header("Range", "bytes=37-68"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 37-68/" + providerVideo.length))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(range).isEqualTo(Arrays.copyOfRange(providerVideo, 37, 69));
    }

    private void assertArkResult(GenerationView generation) throws Exception {
        Task done = task(generation);
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.providerRequestId()).isEqualTo(ARK_ID);
        assertThat(done.input().at("/mediaInput/providerParameters/aspectRatio").asText()).isEqualTo(generation.aspectRatio());
        assertThat(done.input().at("/mediaInput/mode").asText()).isEqualTo(generation.mode().name());
        var content = artifacts.requireVersion(owner, done.projectId(), uuid(done.output(), "artifactId"),
                uuid(done.output(), "artifactVersionId")).content();
        assertThat(content.path("sourceTaskId").asText()).isEqualTo(done.id().toString());
        assertThat(content.at("/parameters/providerRequestId").asText()).isEqualTo(ARK_ID);
        assertThat(content.toString()).doesNotContain("127.0.0.1", "video_url");
        Path archived = assets.get(owner, done.projectId(), uuid(content, "assetId")).path();
        assertThat(Files.readAllBytes(archived)).isEqualTo(providerVideo);
        assertThat(probe(archived).path("streams")).extracting(s -> s.path("codec_type").asText())
                .containsExactly("video", "audio");
        assertThat(attempts(done.id())).isEqualTo(1);
        assertThat(usage(done.id())).containsExactly("RESERVATION", "SETTLEMENT");
        noCanvas(done.projectId());
    }

    private void assertWaiting(GenerationView generation) {
        var waiting = task(generation);
        assertThat(waiting.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
        assertThat(waiting.providerRequestId()).isEqualTo(ARK_ID);
    }

    private void finishArk(GenerationView generation) {
        jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=?")
                .param(generation.task().id()).update();
        assertThat(worker.pollOnce("ark-poll-original")).isEqualTo(1);
    }

    private void assertCreate(JsonNode create, String ratio, int frames) {
        assertThat(create.path("duration").asInt()).isEqualTo(4);
        assertThat(create.path("ratio").asText()).isEqualTo(ratio);
        assertThat(create.has("generate_audio")).isFalse();
        assertThat(create.path("content")).hasSize(1 + frames);
        assertThat(create.at("/content/0/type").asText()).isEqualTo("text");
    }

    private void assertFrame(JsonNode create, int index, String role, int width, int height, Color color) throws Exception {
        JsonNode frame = create.path("content").get(index);
        assertThat(frame.path("role").asText()).isEqualTo(role);
        String url = frame.at("/image_url/url").asText();
        assertThat(url).startsWith("data:image/png;base64,");
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1))));
        assertThat(image.getWidth()).isEqualTo(width);
        assertThat(image.getHeight()).isEqualTo(height);
        assertThat(image.getRGB(width / 2, height / 2)).isEqualTo(color.getRGB());
    }

    private VideoStudioService.Capability mockCapability() {
        return studio.context(owner).capabilities().stream().filter(item -> item.adapterId().equals("MOCK_VIDEO"))
                .findFirst().orElseThrow();
    }

    private GenerationView accept(VideoStudioService.Capability capability, VideoInputMode mode,
            String ratio, UUID start, UUID end) {
        return studio.accept(owner, new VideoStudioService.Command(capability.capabilityId(), capability.capabilityVersion(),
                "Animate the synthetic scene", mode, 4, ratio, start, end), UUID.randomUUID().toString());
    }

    private Task task(GenerationView generation) {
        return tasks.get(owner, generation.projectId(), generation.task().id());
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private void replaceFrozen(UUID id, JsonNode frozen) {
        jdbc.sql("update task set input_json=cast(? as jsonb) where id=?").param(frozen.toString()).param(id).update();
    }

    private int attempts(UUID taskId) {
        return jdbc.sql("select count(*) from provider_attempt where task_id=?").param(taskId).query(Integer.class).single();
    }

    private int versionCount(UUID taskId) {
        return jdbc.sql("select count(*) from artifact_version where content_json->>'sourceTaskId'=?")
                .param(taskId.toString()).query(Integer.class).single();
    }

    private List<String> usage(UUID taskId) {
        return jdbc.sql("select entry_type from usage_ledger where task_id=? order by entry_type")
                .param(taskId).query(String.class).list();
    }

    private void noCanvas(UUID projectId) {
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=?").param(projectId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from agent_run where project_id=?").param(projectId).query(Integer.class).single()).isZero();
    }

    private ByteArrayInputStream png(Color color) throws IOException {
        var image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 40, 20);
        graphics.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private byte[] video(int frequency) throws IOException {
        Path output = Files.createTempFile(root, "synthetic-studio-video-", ".mp4");
        try {
            tools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=blue:s=320x180:r=16",
                    "-f", "lavfi", "-i", "sine=frequency=" + frequency + ":sample_rate=44100",
                    "-t", "4", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-y", output.toString()));
            return Files.readAllBytes(output);
        } finally {
            Files.delete(output);
        }
    }

    private JsonNode probe(Path archived) {
        return mapper.readTree(tools.ffprobe(List.of("-v", "error", "-show_entries",
                "stream=codec_type,width,height:format=duration", "-of", "json", archived.toString())));
    }

    private ObjectNode archivedResult(Task lease, int frequency) throws IOException {
        byte[] bytes = video(frequency);
        var archived = assets.archiveTaskVideo(owner, lease.projectId(), UUID.randomUUID(), () -> new ByteArrayInputStream(bytes));
        ObjectNode content = mapper.createObjectNode().put("assetId", archived.id().toString())
                .put("sourceTaskId", lease.id().toString()).put("workflowVersion", "synthetic")
                .put("prompt", "Synthetic additional/late result");
        content.putObject("parameters");
        return content;
    }

    private static HttpServer startArk() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(ARK_PATH, exchange -> {
                if (exchange.getRequestMethod().equals("POST")) {
                    CREATES.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
                    if (UNCERTAIN.get())
                        reply(exchange, 503, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                    else
                        reply(exchange, 200, "application/json", ("{\"id\":\"" + ARK_ID + "\"}").getBytes(StandardCharsets.UTF_8));
                } else {
                    QUERIES.add(exchange.getRequestURI().getPath());
                    String url = "http://127.0.0.1:" + server.getAddress().getPort()
                            + (QUERIES.size() == 1 ? "/expired.mp4" : "/original.mp4");
                    String body = "{\"id\":\"" + ARK_ID + "\",\"model\":\"doubao-seedance-2-0-260128\","
                            + "\"status\":\"succeeded\",\"content\":{\"video_url\":\"" + url + "\"}}";
                    reply(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
                }
            });
            server.createContext("/expired.mp4", exchange -> {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
            });
            server.createContext("/original.mp4", exchange -> reply(exchange, 200, "video/mp4", providerVideo));
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void reply(HttpExchange exchange, int code, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @TestConfiguration
    static class FakeArk {
        @Bean
        @Primary
        ArkSeedanceClient fakeArkClient(ObjectMapper mapper) {
            return new ArkSeedanceClient(mapper, URI.create("http://127.0.0.1:" + ARK.getAddress().getPort()));
        }
    }
}

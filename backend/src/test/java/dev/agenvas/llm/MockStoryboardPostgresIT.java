package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.ShotRedoService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalService;
import dev.agenvas.export.application.MediaExportWorker;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import dev.agenvas.plan.application.ShotKeyframeSelectionService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MockImageWorker;
import dev.agenvas.provider.application.MockVideoWorker;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/** Real database proof that account-free Mock planning uses the durable Agent and media path. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=mock-storyboard-integration-secret",
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false",
        "agenvas.provider.mock.video-scheduler-enabled=false"})
class MockStoryboardPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private AgentTurnWorker turns;
    @Autowired private ExecutionPlanService plans;
    @Autowired private ShotKeyframeSelectionService keyframes;
    @Autowired private MockImageWorker images;
    @Autowired private MockVideoWorker videos;
    @Autowired private TaskService tasks;
    @Autowired private ArtifactService artifacts;
    @Autowired private ShotRedoService redo;
    @Autowired private AssetService assets;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private CanvasService canvas;
    @Autowired private ExportProposalService exportProposals;
    @Autowired private MediaExportWorker exportWorker;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;
    @Autowired private ObjectProvider<ChatModel> externalModels;

    @Test
    void threeShotsAndThreeRealDemoPngsRequireHumanImageApproval() throws Exception {
        assertThat(externalModels.getIfAvailable()).isNull();
        AdminPrincipal owner = identities.setup("mock-storyboard-integration-secret",
                "mock-storyboard-admin", "mock-storyboard-password-123");
        Project project = projects.create(owner.userId(), "Mock coffee advertisement",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Creator",
                "Create a three-shot storyboard", List.of());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceAgent(
                UUID.randomUUID(), agent.id(), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(340), BigDecimal.valueOf(320), 0, null, false)));
        AgentRunService.RunPreflight preflight = runs.preflight(owner.userId(), project.id(),
                agent.id());
        assertThat(preflight.modelAvailable()).isTrue();
        assertThat(preflight.modelId()).isEqualTo("mock-storyboard-v1");
        assertThat(preflight.providerAdapter()).contains("非 AI");
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "制作一个 15 秒、三个镜头的咖啡广告，现代极简风。", "mock-coffee-run",
                preflight.agentVersion()).run();

        for (int step = 0; step < 3; step++) {
            assertThat(turns.runOnce("mock-storyboard-turn")).isEqualTo(1);
        }
        assertThat(jdbc.sql("""
                        select count(*) from usage_ledger
                        where run_id = :runId and entry_type = 'SETTLEMENT'
                          and cost_source = 'MOCK_UNPRICED'
                          and quantity_json->>'inputTokens' is null
                          and quantity_json->>'outputTokens' is null
                        """)
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(3);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(count(project.id(), "TEXT")).isEqualTo(1);
        assertThat(count(project.id(), "SCENE")).isEqualTo(1);
        assertThat(count(project.id(), "SHOT")).isEqualTo(3);
        assertThat(count(project.id(), "IMAGE")).isZero();
        ExecutionPlan plan = plans.listByRun(owner.userId(), project.id(), run.id()).getFirst();
        assertThat(plan.stage()).isEqualTo(ExecutionPlan.Stage.IMAGE);
        assertThat(plan.steps()).hasSize(3);
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .noneMatch(task -> task.kind() == Task.Kind.IMAGE_GENERATION);

        plans.approve(owner.userId(), project.id(), plan.id(), plan.planHash());
        for (int image = 0; image < 3; image++) {
            assertThat(images.runOnce("mock-storyboard-image")).isEqualTo(1);
        }
        assertThat(count(project.id(), "IMAGE")).isEqualTo(3);
        List<Task> imageTasks = tasks.listByRun(owner.userId(), project.id(), run.id())
                .stream().filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION).toList();
        assertThat(imageTasks).hasSize(3).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED);
            UUID artifactId = UUID.fromString(task.output().path("artifactId").asText());
            ArtifactService.ArtifactView image = artifacts.get(owner.userId(), project.id(),
                    artifactId);
            assertThat(image.currentVersion().content().path("parameters")
                    .path("mock").booleanValue()).isTrue();
            UUID assetId = UUID.fromString(image.currentVersion().content()
                    .path("assetId").asText());
            assertThat(assets.get(owner.userId(), project.id(), assetId).asset().contentType())
                    .isEqualTo("image/png");
            assertThat(canvas.list(owner.userId(), project.id())).anyMatch(entry ->
                    entry.item().subjectId().equals(artifactId)
                            && agent.outputGroupId().equals(entry.item().groupId()));
        });
        assertThat(turns.runOnce("mock-storyboard-turn")).isZero();
        for (Task imageTask : imageTasks) {
            UUID shotId = UUID.fromString(imageTask.input().path("shotArtifactId").asText());
            UUID shotVersionId = UUID.fromString(imageTask.input().path("shotVersionId").asText());
            UUID imageId = UUID.fromString(imageTask.output().path("artifactId").asText());
            UUID imageVersionId = UUID.fromString(imageTask.output()
                    .path("artifactVersionId").asText());
            keyframes.select(owner.userId(), project.id(), run.id(), shotId, shotVersionId,
                    imageId, imageVersionId, null);
        }
        assertThat(turns.runOnce("mock-storyboard-turn")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        ExecutionPlan videoPlan = plans.listByRun(owner.userId(), project.id(), run.id())
                .stream().filter(candidate -> candidate.stage() == ExecutionPlan.Stage.VIDEO)
                .findFirst().orElseThrow();
        assertThat(videoPlan.steps()).hasSize(3).allSatisfy(step ->
                assertThat(step.imageVersionId()).isNotNull());
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .noneMatch(task -> task.kind() == Task.Kind.VIDEO_GENERATION);
        plans.approve(owner.userId(), project.id(), videoPlan.id(), videoPlan.planHash());
        for (int video = 0; video < 3; video++) {
            assertThat(videos.runOnce("mock-storyboard-video")).isEqualTo(1);
        }
        List<Task> videoTasks = tasks.listByRun(owner.userId(), project.id(), run.id())
                .stream().filter(task -> task.kind() == Task.Kind.VIDEO_GENERATION).toList();
        assertThat(videoTasks).hasSize(3).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(Task.Status.SUCCEEDED);
            UUID artifactId = UUID.fromString(task.output().path("artifactId").asText());
            ArtifactService.ArtifactView video = artifacts.get(owner.userId(), project.id(),
                    artifactId);
            assertThat(video.currentVersion().content().path("keyframeVersionId").asText())
                    .isEqualTo(task.input().path("imageVersionId").asText());
            assertThat(video.currentVersion().inputReferences()).anySatisfy(reference ->
                    assertThat(reference.versionId().toString())
                            .isEqualTo(task.input().path("imageVersionId").asText()));
            UUID assetId = UUID.fromString(video.currentVersion().content()
                    .path("assetId").asText());
            AssetService.AssetFile media = assets.get(owner.userId(), project.id(), assetId);
            assertThat(media.asset().contentType()).isEqualTo("video/mp4");
            assertThat(media.asset().thumbnailKey()).isNotBlank();
            assertThat(media.asset().byteSize()).isGreaterThan(1_000);
            try {
                byte[] header = Files.readAllBytes(media.path());
                assertThat(new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII))
                        .isEqualTo("ftyp");
                assertThat(ImageIO.read(assets.getThumbnail(owner.userId(), project.id(),
                        assetId).path().toFile())).isNotNull();
                String probe = mediaTools.ffprobe(List.of("-v", "error", "-select_streams",
                        "v:0", "-show_entries", "stream=codec_name,width,height",
                        "-of", "json", media.path().toString()));
                assertThat(probe).contains("h264", "640", "360");
            } catch (java.io.IOException exception) {
                throw new AssertionError("Archived video cannot be read", exception);
            }
        });
        assertThat(turns.runOnce("mock-storyboard-turn")).isEqualTo(1);
        assertThat(exportProposals.list(owner.userId(), project.id())).hasSize(1);
        ExportProposal exportProposal = exportProposals.list(owner.userId(), project.id()).getFirst();
        assertThat(exportProposal.status()).isEqualTo(ExportProposal.Status.PENDING);
        assertThat(jdbc.sql("select count(*) from task where project_id = :projectId "
                        + "and kind = 'MEDIA_EXPORT'")
                .param("projectId", project.id()).query(Long.class).single()).isZero();
        assertThat(turns.runOnce("mock-storyboard-turn")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id = :runId")
                .param("runId", run.id()).query(Long.class).single()).isEqualTo(6);

        // Only authenticated approval converts the ordered Agent proposal into export work.
        assertThat(exportProposal.input().path("segments")).hasSize(3);
        assertThat(java.util.stream.StreamSupport.stream(exportProposal.input()
                        .path("segments").spliterator(), false)
                .map(segment -> artifacts.get(owner.userId(), project.id(),
                        UUID.fromString(segment.path("shotArtifactId").asText()))
                        .currentVersion().content().path("order").asInt())
                .toList()).containsExactly(1, 2, 3);
        Task exportTask = exportProposals.approve(owner.userId(), project.id(),
                exportProposal.id(), exportProposal.proposalHash()).task();
        assertThat(exportTask.input().path("segments")).hasSize(3);
        assertThat(exportWorker.runOnce("mock-storyboard-export")).isEqualTo(1);
        Task finishedExport = tasks.get(owner.userId(), project.id(), exportTask.id());
        assertThat(finishedExport.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID exportedAssetId = UUID.fromString(finishedExport.output().path("assetId").asText());
        AssetService.AssetFile exported = assets.get(owner.userId(), project.id(), exportedAssetId);
        assertThat(exported.asset().contentType()).isEqualTo("video/mp4");
        assertThat(Files.size(exported.path())).isGreaterThan(1_000);
        String exportedVideo = mediaTools.ffprobe(List.of("-v", "error", "-select_streams",
                "v:0", "-show_entries", "stream=codec_name,width,height:format=duration",
                "-of", "json", exported.path().toString()));
        var exportProbe = mapper.readTree(exportedVideo);
        assertThat(exportProbe.path("streams").path(0).path("codec_name").asText())
                .isEqualTo("h264");
        assertThat(exportProbe.path("streams").path(0).path("width").asInt())
                .isEqualTo(1280);
        assertThat(exportProbe.path("streams").path(0).path("height").asInt())
                .isEqualTo(720);
        assertThat(exportProbe.path("format").path("duration").asDouble())
                .isBetween(2.8, 3.2);
        String exportedAudio = mediaTools.ffprobe(List.of("-v", "error", "-select_streams",
                "a", "-show_entries", "stream=codec_type", "-of", "json",
                exported.path().toString()));
        assertThat(mapper.readTree(exportedAudio).path("streams").size()).isZero();
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String downloadPath = "/api/v1/projects/" + project.id() + "/assets/"
                + exportedAssetId + "/content";
        assertThat(mvc.perform(get(downloadPath).header(HttpHeaders.RANGE, "bytes=4-7")
                .with(authentication(new UsernamePasswordAuthenticationToken(owner,
                        null, List.of()))))
                .andExpect(status().isPartialContent()).andReturn()
                .getResponse().getContentAsString()).isEqualTo("ftyp");
        mvc.perform(get(downloadPath)).andExpect(status().isUnauthorized());

        // A new scoped Run must plan and generate only the manually revised second shot.
        Task oldSecondImage = imageTasks.stream().filter(task -> {
            UUID shotId = UUID.fromString(task.input().path("shotArtifactId").asText());
            return artifacts.get(owner.userId(), project.id(), shotId).currentVersion()
                    .content().path("order").asInt() == 2;
        }).findFirst().orElseThrow();
        UUID secondShotId = UUID.fromString(oldSecondImage.input().path("shotArtifactId").asText());
        List<Task> untouchedOriginalVideos = videoTasks.stream().filter(task ->
                !secondShotId.toString().equals(task.input().path("shotArtifactId").asText()))
                .toList();
        assertThat(untouchedOriginalVideos).hasSize(2);
        ArtifactService.ArtifactView oldSecondShot = artifacts.get(owner.userId(),
                project.id(), secondShotId);
        ShotRedoService.Result revision = redo.revise(owner.userId(), project.id(),
                secondShotId, new ShotRedoService.Request(oldSecondShot.currentVersion().id(),
                        oldSecondShot.artifact().version(), "只修改第二镜头", "特写", "缓慢倒入咖啡",
                        null, new ShotRedoService.SceneEdit(null, null, "黄昏", null, null)));
        assertThat(revision.scene()).isNotNull();
        assertThatThrownBy(() -> runs.create(owner.userId(), project.id(), agent.id(),
                "越过绑定重做第二镜头", "mock-unbound-redo", agent.version(), secondShotId))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        AgentInstance scopedAgent = agents.update(owner.userId(), project.id(), agent.id(),
                agent.version(), agent.name(), agent.instruction(), List.of(
                        new AgentInstanceService.BindingInput(secondShotId,
                                revision.shot().currentVersion().id())));
        AgentRun redoRun = runs.create(owner.userId(), project.id(), agent.id(),
                "只重做第二镜头", "mock-second-shot-redo", scopedAgent.version(),
                secondShotId).run();
        assertThat(redoRun.contextSnapshot().path("bindings").size()).isEqualTo(1);
        assertThat(turns.runOnce("mock-redo-turn")).isEqualTo(1);
        ExecutionPlan redoImagePlan = plans.listByRun(owner.userId(), project.id(),
                redoRun.id()).getFirst();
        assertThat(redoImagePlan.steps()).hasSize(1);
        assertThat(redoImagePlan.steps().getFirst().shotArtifactId()).isEqualTo(secondShotId);
        plans.approve(owner.userId(), project.id(), redoImagePlan.id(),
                redoImagePlan.planHash());
        assertThat(images.runOnce("mock-redo-image")).isEqualTo(1);
        Task redoImage = tasks.listByRun(owner.userId(), project.id(), redoRun.id()).stream()
                .filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION)
                .findFirst().orElseThrow();
        assertThat(redoImage.status()).isEqualTo(Task.Status.SUCCEEDED);
        keyframes.select(owner.userId(), project.id(), redoRun.id(), secondShotId,
                revision.shot().currentVersion().id(),
                UUID.fromString(redoImage.output().path("artifactId").asText()),
                UUID.fromString(redoImage.output().path("artifactVersionId").asText()),
                keyframes.get(owner.userId(), project.id(), run.id(), secondShotId).version());
        assertThat(turns.runOnce("mock-redo-turn")).isEqualTo(1);
        ExecutionPlan redoVideoPlan = plans.listByRun(owner.userId(), project.id(),
                redoRun.id()).stream().filter(candidate -> candidate.stage()
                        == ExecutionPlan.Stage.VIDEO).findFirst().orElseThrow();
        assertThat(redoVideoPlan.steps()).hasSize(1);
        assertThat(redoVideoPlan.steps().getFirst().shotArtifactId()).isEqualTo(secondShotId);
        plans.approve(owner.userId(), project.id(), redoVideoPlan.id(),
                redoVideoPlan.planHash());
        assertThat(videos.runOnce("mock-redo-video")).isEqualTo(1);
        assertThat(turns.runOnce("mock-redo-turn")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), redoRun.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(tasks.listByRun(owner.userId(), project.id(), redoRun.id())
                .stream().filter(task -> task.kind() == Task.Kind.VIDEO_GENERATION))
                .hasSize(1);
        assertThat(count(project.id(), "SHOT")).isEqualTo(3);
        assertThat(count(project.id(), "IMAGE")).isEqualTo(4);
        assertThat(count(project.id(), "VIDEO")).isEqualTo(4);
        for (Task untouched : untouchedOriginalVideos) {
            UUID untouchedShotId = UUID.fromString(untouched.input()
                    .path("shotArtifactId").asText());
            assertThat(artifacts.get(owner.userId(), project.id(), untouchedShotId)
                    .currentVersion().id().toString())
                    .isEqualTo(untouched.input().path("shotVersionId").asText());
            UUID untouchedVideoId = UUID.fromString(untouched.output()
                    .path("artifactId").asText());
            assertThat(artifacts.get(owner.userId(), project.id(), untouchedVideoId)
                    .currentVersion().id().toString())
                    .isEqualTo(untouched.output().path("artifactVersionId").asText());
        }

        Project rejectedProject = projects.create(owner.userId(), "Rejected mock storyboard",
                Project.AspectRatio.SQUARE_1_1);
        AgentInstance rejectedAgent = agents.create(owner.userId(), rejectedProject.id(),
                "Creator", "Create a storyboard", List.of());
        AgentRun rejectedRun = runs.create(owner.userId(), rejectedProject.id(),
                rejectedAgent.id(), "演示被拒绝的图片计划", "mock-rejected-run",
                rejectedAgent.version()).run();
        for (int step = 0; step < 3; step++) {
            assertThat(turns.runOnce("mock-storyboard-rejected-turn")).isEqualTo(1);
        }
        ExecutionPlan rejectedPlan = plans.listByRun(owner.userId(), rejectedProject.id(),
                rejectedRun.id()).getFirst();
        plans.reject(owner.userId(), rejectedProject.id(), rejectedPlan.id());
        assertThat(tasks.listByRun(owner.userId(), rejectedProject.id(), rejectedRun.id()))
                .noneMatch(task -> task.kind() == Task.Kind.IMAGE_GENERATION);
        assertThat(turns.runOnce("mock-storyboard-rejected-turn")).isEqualTo(1);
        assertThat(runs.get(owner.userId(), rejectedProject.id(), rejectedRun.id()).status())
                .isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(count(rejectedProject.id(), "IMAGE")).isZero();
    }

    private long count(UUID projectId, String kind) {
        return jdbc.sql("select count(*) from artifact where project_id = :projectId "
                        + "and kind = :kind")
                .param("projectId", projectId).param("kind", kind).query(Long.class).single();
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-mock-storyboard-it-");
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}

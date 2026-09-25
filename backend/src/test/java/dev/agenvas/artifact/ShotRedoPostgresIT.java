package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.ShotRedoService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real database proof that a local redo forks only the target shot's semantic inputs. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=shot-redo-test-secret-long")
class ShotRedoPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaToolRunner mediaTools;
    @Autowired private ShotRedoService redo;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext webContext;

    @Test
    void forksSharedSceneOnlyForSecondShotAndClearsItsOldMediaSelection() throws Exception {
        AdminPrincipal owner = identities.setup("shot-redo-test-secret-long", "redo-admin",
                "redo-password-123");
        Project project = projects.create(owner.userId(), "Redo project",
                Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView scene = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.SCENE, "Shared scene", scene("Day"));
        UUID imageAsset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        ArtifactService.ArtifactView image = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.IMAGE, "Old image", image(imageAsset));
        UUID videoAsset = videoAsset(owner.userId(), project.id());
        ArtifactService.ArtifactView siblingVideo = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.VIDEO, "Sibling video", video(videoAsset));
        ArtifactService.ArtifactView secondVideo = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.VIDEO, "Second video", video(videoAsset));
        ArtifactService.ArtifactView first = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.SHOT, "First", shot(1, scene.currentVersion().id(), null,
                        siblingVideo.currentVersion().id()));
        ArtifactService.ArtifactView second = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.SHOT, "Second", shot(2, scene.currentVersion().id(),
                        image.currentVersion().id(), secondVideo.currentVersion().id()));
        ArtifactService.ArtifactView third = artifacts.create(owner.userId(), project.id(),
                Artifact.Kind.SHOT, "Third", shot(3, scene.currentVersion().id(), null,
                        siblingVideo.currentVersion().id()));

        ShotRedoService.Request change = new ShotRedoService.Request(
                second.currentVersion().id(), second.artifact().version(),
                "New second shot", "Close-up", "Pour slowly", 5,
                new ShotRedoService.SceneEdit(null, null, "Night", null, null));
        ShotRedoService.Result result = redo.revise(owner.userId(), project.id(),
                second.artifact().id(), change);
        assertThat(result.scene()).isNotNull();
        assertThat(result.scene().currentVersion().content().path("timeOfDay").asText())
                .isEqualTo("Night");
        assertThat(result.shot().currentVersion().content().path("sceneVersionId").asText())
                .isEqualTo(result.scene().currentVersion().id().toString());
        assertThat(result.shot().currentVersion().content().path("selectedImageVersionId")
                .isMissingNode()).isTrue();
        assertThat(result.shot().currentVersion().content().path("selectedVideoVersionId")
                .isMissingNode()).isTrue();
        assertThat(result.shot().currentVersion().content().path("description").asText())
                .isEqualTo("New second shot");
        for (ArtifactService.ArtifactView sibling : List.of(first, third)) {
            ArtifactService.ArtifactView unchanged = artifacts.get(owner.userId(), project.id(),
                    sibling.artifact().id());
            assertThat(unchanged.currentVersion().id()).isEqualTo(sibling.currentVersion().id());
            assertThat(unchanged.currentVersion().content().path("sceneVersionId").asText())
                    .isEqualTo(scene.currentVersion().id().toString());
            assertThat(unchanged.currentVersion().content().path("selectedVideoVersionId")
                    .asText()).isEqualTo(siblingVideo.currentVersion().id().toString());
            assertThat(unchanged.currentVersion().inputReferences()).anyMatch(reference ->
                    reference.role().equals("selectedVideo") && reference.versionId()
                            .equals(siblingVideo.currentVersion().id()));
        }
        assertThat(artifacts.requireVersion(owner.userId(), project.id(),
                scene.artifact().id(), scene.currentVersion().id()).content()
                .path("timeOfDay").asText()).isEqualTo("Day");
        assertThat(artifacts.requireVersion(owner.userId(), project.id(),
                second.artifact().id(), second.currentVersion().id()).content()
                .path("selectedImageVersionId").asText())
                .isEqualTo(image.currentVersion().id().toString());
        assertThat(artifacts.requireVersion(owner.userId(), project.id(),
                second.artifact().id(), second.currentVersion().id()).content()
                .path("selectedVideoVersionId").asText())
                .isEqualTo(secondVideo.currentVersion().id().toString());

        assertThatThrownBy(() -> redo.revise(owner.userId(), project.id(),
                second.artifact().id(), change))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("SHOT_REDO_CONFLICT"));
        assertThatThrownBy(() -> redo.revise(UUID.randomUUID(), project.id(),
                second.artifact().id(), change))
                .isInstanceOf(ApiProblemException.class);

        // The public boundary requires a trusted owner, CSRF, and the exact latest version.
        MockMvc mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        String path = "/api/v1/projects/" + project.id() + "/shots/"
                + second.artifact().id() + "/revisions";
        ObjectNode body = mapper.createObjectNode();
        body.put("expectedShotVersionId", result.shot().currentVersion().id().toString());
        body.put("expectedShotArtifactVersion", result.shot().artifact().version());
        body.put("description", "Another take");
        body.put("camera", "Wide");
        body.put("action", "Pour again");
        mvc.perform(post(path).with(authentication(asUser(owner)))
                .contentType("application/json").content(body.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(authentication(asUser(owner))).with(csrf())
                .contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shot.currentVersion.content.description")
                        .value("Another take"));
        mvc.perform(post(path).with(authentication(asUser(owner))).with(csrf())
                .contentType("application/json").content(body.toString()))
                .andExpect(status().isConflict());
        mvc.perform(post(path).with(authentication(asUser(
                        new AdminPrincipal(UUID.randomUUID(), "not-owner")))).with(csrf())
                .contentType("application/json").content(body.toString()))
                .andExpect(status().isNotFound());

        // Race a previously submitted result against the next human shot revision. The project
        // event lock must determine which committed first; a result committed later is historical.
        ArtifactService.ArtifactView beforeRace = artifacts.get(owner.userId(), project.id(),
                second.artifact().id());
        var agent = agents.create(owner.userId(), project.id(), "Redo agent", "Redo",
                List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "Redo second", "redo-race-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        ObjectNode input = mapper.createObjectNode();
        input.put("shotArtifactId", second.artifact().id().toString());
        input.put("shotVersionId", beforeRace.currentVersion().id().toString());
        Task task = tasks.createMediaTaskForNewOutput(owner.userId(), project.id(), run.id(),
                null, "old-second-image", Task.Kind.IMAGE_GENERATION, input, null, 1,
                List.of(), "old-second-image-output");
        Task lease = tasks.claimImagesDue("redo-race-worker", 1).getFirst();
        tasks.beginSubmission(lease, "redo-race-worker");
        ObjectNode lateContent = image(imageAsset);
        lateContent.put("sourceTaskId", task.id().toString());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ShotRedoService.Result racedRevision;
        ArtifactService.TaskVersionResult racedMedia;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revisionFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return redo.revise(owner.userId(), project.id(), second.artifact().id(),
                        new ShotRedoService.Request(beforeRace.currentVersion().id(),
                                beforeRace.artifact().version(), "Concurrent second take",
                                "Close-up", "Pour", 5, null));
            });
            var mediaFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return tasks.succeedWithArtifact(lease, "redo-race-worker", lateContent);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            racedRevision = revisionFuture.get(20, TimeUnit.SECONDS);
            racedMedia = mediaFuture.get(20, TimeUnit.SECONDS);
        }
        ArtifactService.ArtifactView afterRace = artifacts.get(owner.userId(), project.id(),
                second.artifact().id());
        assertThat(afterRace.currentVersion().id())
                .isEqualTo(racedRevision.shot().currentVersion().id());
        assertThat(afterRace.currentVersion().content().path("description").asText())
                .isEqualTo("Concurrent second take");
        assertThat(afterRace.currentVersion().content().has("selectedImageVersionId")).isFalse();
        assertThat(afterRace.currentVersion().content().has("selectedVideoVersionId")).isFalse();
        long revisionSeq = jdbc.sql("""
                        select seq from project_event
                        where aggregate_id = :shotId and type = 'artifact.version.created'
                          and aggregate_version = :version
                        """)
                .param("shotId", second.artifact().id())
                .param("version", racedRevision.shot().artifact().version())
                .query(Long.class).single();
        long completionSeq = jdbc.sql("""
                        select seq from project_event
                        where aggregate_id = :taskId and type = 'task.status.changed'
                          and payload_json ->> 'status' = 'SUCCEEDED'
                        """)
                .param("taskId", task.id()).query(Long.class).single();
        assertThat(racedMedia.selected()).isEqualTo(completionSeq < revisionSeq);
    }

    private UsernamePasswordAuthenticationToken asUser(AdminPrincipal owner) {
        return new UsernamePasswordAuthenticationToken(owner, null, List.of());
    }

    private ObjectNode scene(String timeOfDay) {
        ObjectNode content = mapper.createObjectNode();
        content.put("name", "Cafe");
        content.put("location", "Shanghai");
        content.put("timeOfDay", timeOfDay);
        content.put("lighting", "Soft light");
        content.put("style", "Minimal");
        content.putArray("referenceVersionIds");
        return content;
    }

    private ObjectNode shot(int order, UUID sceneVersionId, UUID imageVersionId,
            UUID videoVersionId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("order", order);
        content.put("durationSeconds", 5);
        content.put("description", "Coffee shot");
        content.put("camera", "Dolly in");
        content.put("action", "Pour coffee");
        content.putArray("characterVersionIds");
        content.put("sceneVersionId", sceneVersionId.toString());
        if (imageVersionId != null) {
            content.put("selectedImageVersionId", imageVersionId.toString());
        }
        if (videoVersionId != null) {
            content.put("selectedVideoVersionId", videoVersionId.toString());
        }
        return content;
    }

    /** Archives one locally rendered MP4 so selected-video references obey real asset validation. */
    private UUID videoAsset(UUID ownerId, UUID projectId) throws Exception {
        Path clip = Files.createTempFile("redo-selected-video-", ".mp4");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin",
                    "-f", "lavfi", "-i", "color=c=blue:s=640x360:r=24", "-t", "1",
                    "-an", "-c:v", "libx264", "-preset", "veryfast", "-y",
                    clip.toString()));
            try (var stream = Files.newInputStream(clip)) {
                return assets.archiveVideo(ownerId, projectId, stream).id();
            }
        } finally {
            Files.deleteIfExists(clip);
        }
    }

    private ObjectNode video(UUID assetId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", "Old selected video");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "test-video-v1");
        content.putObject("parameters").put("mock", true);
        content.put("sourceTaskId", UUID.randomUUID().toString());
        return content;
    }

    private ObjectNode image(UUID assetId) {
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", assetId.toString());
        content.put("prompt", "Old image");
        content.put("providerConfigVersion", 1);
        content.put("workflowVersion", "mock-image-v1");
        content.putObject("parameters").put("mock", true);
        content.put("sourceTaskId", UUID.randomUUID().toString());
        return content;
    }
}

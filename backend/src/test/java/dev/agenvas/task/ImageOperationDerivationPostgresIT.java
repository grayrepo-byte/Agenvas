package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL proof that an image operation owns a derived card, never the source card. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false"})
class ImageOperationDerivationPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AssetService assets;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private CanvasConnectionService connections;
    @Autowired private DirectMediaTaskService directMedia;
    @Autowired private TaskService tasks;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private MediaCapabilityService capabilities;
    @Autowired private ObjectMapper mapper;

    @Test
    void createsConnectedResultBranchAndSelectsOnlyThatBranch() {
        AdminPrincipal owner = identities.setup("derivation-admin",
                "derivation-password-123");
        Project project = projects.create(owner.userId(), "Image derivation",
                Project.AspectRatio.LANDSCAPE_16_9);
        UUID sourceAssetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        ObjectNode content = mapper.createObjectNode();
        content.put("assetId", sourceAssetId.toString());
        content.put("sourceType", "UPLOAD");
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Source image", content);
        UUID sourceVersionId = image.resourceDefaultVersion().id();
        UUID sourceCardId = dev.agenvas.support.CanvasMediaFixture.place(canvas,
                owner.userId(), project.id(), image.artifact().id());
        canvas.apply(owner.userId(), project.id(),
                List.of(new CanvasService.UpdateTitle(sourceCardId, 0, "我的原图")));
        var reference = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Reference", content);
        var parameters = mapper.createObjectNode();
        parameters.put("aspectRatio", "16:9");
        parameters.put("resolution", "2K");
        parameters.put("quality", "high");
        parameters.put("generationCount", 4);
        drafts.save(owner.userId(), project.id(), sourceCardId, 0,
                "Previous prompt \uFFFC", parameters, null,
                capabilities.defaultFor(Task.Kind.IMAGE_GENERATION).capabilityId(), null,
                List.of(new MediaDraftService.SaveMediaInput(reference.resourceDefaultVersion().id(),
                        MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                List.of(new MediaDraft.PromptMention(reference.resourceDefaultVersion().id(),
                        MediaDraft.InputRole.REFERENCE)), UUID.fromString("00000000-0000-4000-8000-000000000301"));
        // Compare persisted snapshots so the unchanged source includes PostgreSQL timestamp precision.
        MediaDraft sourceDraft = drafts.get(owner.userId(), project.id(), sourceCardId);

        ObjectNode crop = mapper.createObjectNode();
        crop.put("x", 0);
        crop.put("y", 0);
        crop.put("width", 0.5);
        crop.put("height", 1);
        Task accepted = directMedia.runImageOperation(owner.userId(), project.id(),
                image.artifact().id(), sourceCardId, sourceVersionId, 1,
                ImageOperation.CROP, "", 0, 1, List.of(), null, crop, "crop-derived-node");
        UUID targetCardId = UUID.fromString(accepted.input().path("canvasItemId").asText());
        assertThat(canvas.list(owner.userId(), project.id()).stream()
                .filter(entry -> entry.item().id().equals(targetCardId))
                .findFirst().orElseThrow().item().title()).isEqualTo("我的原图 · 裁剪");
        assertFreshDraft(owner, project, targetCardId, MediaDraft.DisplayMode.DRAFT);
        assertThat(drafts.get(owner.userId(), project.id(), sourceCardId)).isEqualTo(sourceDraft);
        assertThat(accepted.input().path("resultDraftVersion").asLong()).isZero();

        assertThat(targetCardId).isNotEqualTo(sourceCardId);
        assertThat(accepted.input().path("sourceCanvasItemId").asText())
                .isEqualTo(sourceCardId.toString());
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(2)
                .allSatisfy(entry -> assertThat(entry.item().selectedVersionId())
                        .isEqualTo(sourceVersionId));
        assertThat(connections.list(owner.userId(), project.id())).singleElement()
                .satisfies(connection -> {
                    assertThat(connection.sourceCanvasItemId()).isEqualTo(sourceCardId);
                    assertThat(connection.targetCanvasItemId()).isEqualTo(targetCardId);
                    assertThat(connection.sourceArtifactVersionId()).isEqualTo(sourceVersionId);
                    assertThat(connection.relationType())
                            .isEqualTo(CanvasConnection.RelationType.MEDIA_DERIVATION);
                });

        Task replay = directMedia.runImageOperation(owner.userId(), project.id(),
                image.artifact().id(), sourceCardId, sourceVersionId, 1,
                ImageOperation.CROP, "", 0, 1, List.of(), null, crop, "crop-derived-node");
        assertThat(replay.id()).isEqualTo(accepted.id());
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(2);
        assertThat(connections.list(owner.userId(), project.id())).hasSize(1);

        canvas.apply(owner.userId(), project.id(),
                List.of(new CanvasService.UpdateTitle(targetCardId, 0, "自定义裁剪结果")));

        assertThat(worker.submitOnce("local-image-derivation-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("selected").asBoolean()).isTrue();
        UUID resultVersionId = UUID.fromString(
                completed.output().path("artifactVersionId").asText());
        var cards = canvas.list(owner.userId(), project.id());
        assertThat(cards.stream().filter(entry -> entry.item().id().equals(targetCardId))
                .findFirst().orElseThrow().item().title()).isEqualTo("自定义裁剪结果");
        assertThat(cards.stream().filter(entry -> entry.item().id().equals(sourceCardId))
                .findFirst().orElseThrow().item().title()).isEqualTo("我的原图");
        assertThat(artifacts.get(owner.userId(), project.id(), image.artifact().id())
                .artifact().title()).isEqualTo("Source image");
        assertThat(cards.stream().filter(entry -> entry.item().id().equals(sourceCardId))
                .findFirst().orElseThrow().item().selectedVersionId()).isEqualTo(sourceVersionId);
        assertThat(cards.stream().filter(entry -> entry.item().id().equals(targetCardId))
                .findFirst().orElseThrow().item().selectedVersionId()).isEqualTo(resultVersionId);
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), sourceCardId))
                .extracting(version -> version.id()).containsExactly(sourceVersionId);
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), targetCardId))
                .extracting(version -> version.id()).containsExactly(resultVersionId);
        assertFreshDraft(owner, project, targetCardId, MediaDraft.DisplayMode.RESULT);
        assertThat(drafts.get(owner.userId(), project.id(), sourceCardId)).isEqualTo(sourceDraft);

        CanvasConnection lineage = connections.list(owner.userId(), project.id()).getFirst();
        connections.disconnect(owner.userId(), project.id(), lineage.id(), null, null);
        assertThat(connections.list(owner.userId(), project.id())).isEmpty();
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(2);

        long targetDraftVersion = drafts.get(owner.userId(), project.id(), targetCardId).version();
        connections.connect(owner.userId(), project.id(), sourceCardId, targetCardId,
                sourceVersionId, CanvasConnection.RelationType.MEDIA_INPUT,
                targetDraftVersion);
        assertThat(connections.list(owner.userId(), project.id())).singleElement()
                .extracting(CanvasConnection::relationType)
                .isEqualTo(CanvasConnection.RelationType.MEDIA_INPUT);

        // Resize follows the same immutable branch, version pinning and replay path as crop.
        for (var resize : List.of(mapper.createObjectNode().put("resizeMode", "PERCENTAGE").put("percentage", 50),
                mapper.createObjectNode().put("resizeMode", "LONGEST_EDGE").put("longestEdge", 5))) {
            String key = "resize-" + resize.path("resizeMode").asText();
            var resized = directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                    sourceCardId, sourceVersionId, 1, ImageOperation.RESIZE, "", 0, 1, List.of(), null, resize, key);
            assertThat(directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                    sourceCardId, sourceVersionId, 1, ImageOperation.RESIZE, "", 0, 1, List.of(), null, resize, key).id())
                    .isEqualTo(resized.id());
            UUID resizedCard = UUID.fromString(resized.input().path("canvasItemId").asText());
            assertFreshDraft(owner, project, resizedCard, MediaDraft.DisplayMode.DRAFT);
            assertThat(worker.submitOnce("resize-worker")).isEqualTo(1);
            var finished = tasks.get(owner.userId(), project.id(), resized.id());
            assertThat(finished.status()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(finished.output().path("selected").asBoolean()).isTrue();
            var result = canvas.listMediaVersions(owner.userId(), project.id(), resizedCard).getFirst();
            var asset = assets.metadata(owner.userId(), project.id(), UUID.fromString(result.content().path("assetId").asText()));
            int edge = resize.has("percentage") ? 1 : 5;
            assertThat(asset.width()).isEqualTo(edge);
            assertThat(asset.height()).isEqualTo(edge);
            assertThat(canvas.list(owner.userId(), project.id()).stream().filter(entry -> entry.item().id().equals(sourceCardId))
                    .findFirst().orElseThrow().item().selectedVersionId()).isEqualTo(sourceVersionId);
            assertThat(drafts.get(owner.userId(), project.id(), sourceCardId)).isEqualTo(sourceDraft);
            assertThat(connections.list(owner.userId(), project.id()).stream()
                    .filter(connection -> connection.targetCanvasItemId().equals(resizedCard))).hasSize(1);
        }
        int nodeCount = canvas.list(owner.userId(), project.id()).size();
        assertThatThrownBy(() -> directMedia.runImageOperation(owner.userId(), project.id(), image.artifact().id(),
                sourceCardId, sourceVersionId, 1, ImageOperation.RESIZE, "", 0, 1, List.of(), null,
                mapper.createObjectNode().put("resizeMode", "LONGEST_EDGE").put("longestEdge", 40000), "oversized-resize"))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(nodeCount);
    }

    private void assertFreshDraft(AdminPrincipal owner, Project project, UUID cardId,
            MediaDraft.DisplayMode displayMode) {
        MediaDraft draft = drafts.get(owner.userId(), project.id(), cardId);
        assertThat(draft.prompt()).isEmpty();
        assertThat(draft.parameters().isEmpty()).isTrue();
        assertThat(draft.capabilityId()).isNull();
        assertThat(draft.styleId()).isNull();
        assertThat(draft.durationSeconds()).isNull();
        assertThat(draft.videoInputMode()).isNull();
        assertThat(draft.mediaInputs()).isEmpty();
        assertThat(draft.mentions()).isEmpty();
        assertThat(draft.displayMode()).isEqualTo(displayMode);
        assertThat(draft.version()).isZero();
    }
}

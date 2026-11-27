package dev.agenvas.canvas;

import static dev.agenvas.testing.QueryCounts.countQueries;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.application.ProjectSnapshotService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.SoftAssertions;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL evidence for atomic layout commands and presentation/content separation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class CanvasPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdentityService identityService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.web.context.WebApplicationContext webContext;

    @Autowired
    private DSLContext db;

    @Autowired
    private AssetService assets;

    @Autowired
    private ProjectSnapshotService snapshots;

    @Autowired
    private PlatformTransactionManager transactions;

    private AdminPrincipal owner;

    @BeforeEach
    void setupOwner() {
        owner = db.select(dev.agenvas.db.Tables.APP_USER.ID).from(dev.agenvas.db.Tables.APP_USER)
                .where(dev.agenvas.db.Tables.APP_USER.LOGIN_NAME.eq("canvas-admin"))
                .fetchOptional(id -> new AdminPrincipal(id.value1(), "canvas-admin"))
                .orElseGet(() -> identityService.setup("canvas-admin", "canvas-password-123"));
    }

    @Test
    void fractionalPlacementsAndMovesReplayAtTheStoredPrecision() {
        UUID user = owner.userId();
        UUID project = projectService.create(user, "Fractional layout", Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID itemId = UUID.randomUUID();
        var artifact = createText(user, project, "Card");
        var placement = place(itemId, artifact.artifact().id(), "-20.1235", "40.12341");
        canvasService.apply(user, project, List.of(placement));
        var placed = canvasService.apply(user, project, List.of(placement)).getFirst().item();
        assertThat(placed.x()).isEqualByComparingTo("-20.124");
        assertThat(placed.y()).isEqualByComparingTo("40.123");
        assertThat(placed.version()).isZero();

        var move = new CanvasService.UpdateLayout(itemId, 0, decimal("100.9876"), decimal("120.2"),
                decimal("280.1235"), decimal("180.00049"), 3, null);
        canvasService.apply(user, project, List.of(move));
        var moved = canvasService.apply(user, project, List.of(move)).getFirst().item();
        assertThat(moved.width()).isEqualByComparingTo("280.124");
        assertThat(moved.height()).isEqualByComparingTo("180");
        assertThat(moved.version()).isOne();
        var different = new CanvasService.UpdateLayout(itemId, 0, decimal("100.9874"), move.y(),
                move.width(), move.height(), move.zIndex(), null);
        assertThatThrownByCode("CANVAS_VERSION_CONFLICT", () -> canvasService.apply(user, project, List.of(different)));
    }

    @Test
    void layoutPersistsFailedBatchRollsBackAndRemovingCardKeepsArtifact() throws Exception {
        Project project = projectService.create(
                owner.userId(), "Canvas project", Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView firstArtifact = createText(owner.userId(), project.id(), "One");
        ArtifactService.ArtifactView secondArtifact = createText(owner.userId(), project.id(), "Two");
        UUID firstItemId = UUID.randomUUID();
        UUID secondItemId = UUID.randomUUID();
        UUID duplicateItemId = UUID.randomUUID();

        CanvasService.PlaceArtifact firstPlacement = place(firstItemId, firstArtifact.artifact().id(), "20", "40");
        CanvasService.PlaceArtifact secondPlacement = place(secondItemId, secondArtifact.artifact().id(), "360", "40");
        CanvasService.PlaceArtifact duplicatePlacement = place(duplicateItemId, firstArtifact.artifact().id(), "700",
                "40");
        canvasService.apply(owner.userId(), project.id(),
                List.of(firstPlacement, secondPlacement, duplicatePlacement));
        canvasService.apply(owner.userId(), project.id(), List.of(firstPlacement));
        assertThat(canvasService.list(owner.userId(), project.id())).hasSize(3);

        canvasService.apply(owner.userId(), project.id(),
                List.of(new CanvasService.UpdateTitle(firstItemId, 0, "  First card  ")));
        canvasService.apply(owner.userId(), project.id(),
                List.of(new CanvasService.UpdateTitle(firstItemId, 0, "First card")));
        List<CanvasService.CanvasEntry> renamed = canvasService.list(owner.userId(), project.id());
        assertThat(renamed.stream().filter(entry -> entry.item().id().equals(firstItemId))
                .findFirst().orElseThrow().item().title()).isEqualTo("First card");
        assertThat(renamed.stream().filter(entry -> entry.item().id().equals(duplicateItemId))
                .findFirst().orElseThrow().item().title()).isEqualTo("One");
        assertThat(artifactService.get(owner.userId(), project.id(),
                firstArtifact.artifact().id()).artifact().title()).isEqualTo("One");
        assertThatThrownByCode("CANVAS_VERSION_CONFLICT", () -> canvasService.apply(
                owner.userId(), project.id(),
                List.of(new CanvasService.UpdateTitle(firstItemId, 0, "Stale title"))));
        assertThatThrownByCode("VALIDATION_ERROR", () -> canvasService.apply(
                owner.userId(), project.id(),
                List.of(new CanvasService.UpdateTitle(firstItemId, 1, "   "))));

        canvasService.apply(
                owner.userId(),
                project.id(),
                List.of(new CanvasService.UpdateLayout(
                        firstItemId,
                        1,
                        decimal("100"),
                        decimal("120"),
                        decimal("280"),
                        decimal("180"),
                        3,
                        null)));
        CanvasService.CanvasEntry saved = canvasService.list(owner.userId(), project.id()).stream()
                .filter(entry -> entry.item().id().equals(firstItemId))
                .findFirst()
                .orElseThrow();
        assertThat(saved.item().x()).isEqualByComparingTo("100");
        assertThat(saved.item().version()).isEqualTo(2);
        assertThat(saved.item().title()).isEqualTo("First card");
        assertThat(saved.artifact().resourceDefaultVersion().content().get("text").stringValue())
                .isEqualTo("One");

        assertThatThrownByCode(
                "CANVAS_VERSION_CONFLICT",
                () -> canvasService.apply(
                        owner.userId(),
                        project.id(),
                        List.of(
                                new CanvasService.UpdateLayout(
                                        firstItemId,
                                        2,
                                        decimal("200"),
                                        decimal("220"),
                                        decimal("280"),
                                        decimal("180"),
                                        4,
                                        null),
                                new CanvasService.UpdateLayout(
                                        secondItemId,
                                        99,
                                        decimal("500"),
                                        decimal("220"),
                                        decimal("280"),
                                        decimal("180"),
                                        4,
                                        null))));
        CanvasService.CanvasEntry afterRollback = canvasService.list(owner.userId(), project.id()).stream()
                .filter(entry -> entry.item().id().equals(firstItemId))
                .findFirst()
                .orElseThrow();
        assertThat(afterRollback.item().x()).isEqualByComparingTo("100");
        assertThat(afterRollback.item().version()).isEqualTo(2);

        canvasService.apply(
                owner.userId(),
                project.id(),
                List.of(new CanvasService.SetLocked(secondItemId, 0, true)));
        assertThatThrownByCode(
                "CANVAS_ITEM_LOCKED",
                () -> canvasService.apply(
                        owner.userId(),
                        project.id(),
                        List.of(new CanvasService.UpdateLayout(
                                secondItemId,
                                1,
                                decimal("500"),
                                decimal("40"),
                                decimal("280"),
                                decimal("180"),
                                1,
                                null))));

        canvasService.apply(
                owner.userId(),
                project.id(),
                List.of(new CanvasService.Remove(firstItemId, 2)));
        assertThat(canvasService.list(owner.userId(), project.id()))
                .extracting(entry -> entry.item().id())
                .containsExactlyInAnyOrder(secondItemId, duplicateItemId);
        assertThat(artifactService
                .get(owner.userId(), project.id(), firstArtifact.artifact().id())
                .artifact()
                .kind())
                .isEqualTo(Artifact.Kind.TEXT);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, null,
                                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "ROLE_ADMIN"))));
        String canvasPath = "/api/v1/projects/" + project.id() + "/canvas";
        var retiredCommand = objectMapper.createObjectNode();
        retiredCommand.putArray("commands").addObject().put("type", "PLACE_AGENT")
                .put("itemId", UUID.randomUUID().toString()).put("agentId", UUID.randomUUID().toString())
                .put("x", 0).put("y", 0).put("width", 300).put("height", 300).put("zIndex", 0).put("locked", false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(canvasPath + "/commands")
                .with(auth)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(retiredCommand.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var retiredConnection = objectMapper.createObjectNode().put("sourceCanvasItemId", secondItemId.toString())
                .put("targetCanvasItemId", duplicateItemId.toString())
                .put("sourceVersionId", UUID.randomUUID().toString())
                .put("relationType", "AGENT_IMAGE_INPUT").put("expectedTargetAgentVersion", 0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(canvasPath + "/connections")
                .with(auth)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(retiredConnection.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        String visible = mvc
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(canvasPath + "/items")
                        .with(auth))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(visible).doesNotContain("\"agent\"", "\"AGENT\"");
        assertThat(canvasService.list(owner.userId(), project.id())).hasSize(2);
    }

    @Test
    void artifactAndCanvasListsHaveBoundedQueryGrowth() {
        UUID projectId = projectService.create(owner.userId(), "Query growth", Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), projectId);
        var image = objectMapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString());
        var first = artifactService.create(owner.userId(), projectId, Artifact.Kind.IMAGE, "First", image);
        canvasService.apply(owner.userId(), projectId,
                List.of(place(UUID.randomUUID(), first.artifact().id(), "0", "0")));
        int smallCanvas = countQueries(db, () -> assertThat(canvasService.list(owner.userId(), projectId)).hasSize(1));
        int smallResources = countQueries(db,
                () -> assertThat(artifactService.listProject(owner.userId(), projectId)).hasSize(1));
        var placements = new ArrayList<CanvasService.PlaceArtifact>();
        for (int i = 1; i < 16; i++) {
            var added = artifactService.create(owner.userId(), projectId, Artifact.Kind.IMAGE, "Image " + i, image);
            placements.add(place(UUID.randomUUID(), added.artifact().id(), "0", "0"));
        }
        canvasService.apply(owner.userId(), projectId, placements);
        int largeCanvas = countQueries(db, () -> assertThat(canvasService.list(owner.userId(), projectId)).hasSize(16));
        int largeResources = countQueries(db,
                () -> assertThat(artifactService.listProject(owner.userId(), projectId)).hasSize(16));
        LoggerFactory.getLogger(getClass()).info(
                "Projection query counts for 1 and 16 rows: canvas {}/{}, resources {}/{}",
                smallCanvas, largeCanvas, smallResources, largeResources);
        SoftAssertions.assertSoftly(checks -> {
            checks.assertThat(largeCanvas).as("canvas, single node used %s statements", smallCanvas)
                    .isLessThanOrEqualTo(smallCanvas + 2);
            checks.assertThat(largeResources).as("resources, single identity used %s statements", smallResources)
                    .isLessThanOrEqualTo(smallResources + 2);
        });
    }

    @Test
    void resourceListRestoresOrderedHistoricalReferencesOnTheCorrectVersion() {
        UUID projectId = projectService
                .create(owner.userId(), "Historical references", Project.AspectRatio.LANDSCAPE_16_9).id();
        var first = createText(owner.userId(), projectId, "A").resourceDefaultVersion();
        var second = createText(owner.userId(), projectId, "B").resourceDefaultVersion();
        var source = createText(owner.userId(), projectId, "Source").resourceDefaultVersion();
        // Older published content retains normalized input rows even when its current editor no longer writes them.
        var refs = dev.agenvas.db.Tables.ARTIFACT_VERSION_REFERENCE;
        db.insertInto(refs, refs.PROJECT_ID, refs.SOURCE_VERSION_ID, refs.TARGET_VERSION_ID,
                refs.REFERENCE_ROLE, refs.REFERENCE_ORDER)
                .values(projectId, source.id(), first.id(), "input", 1)
                .values(projectId, source.id(), second.id(), "input", 0)
                .execute();
        var resources = artifactService.listProject(owner.userId(), projectId);
        assertThat(resources).extracting(value -> value.artifact().id())
                .containsExactly(first.artifactId(), second.artifactId(), source.artifactId());
        assertThat(resources.getFirst().resourceDefaultVersion().inputReferences()).isEmpty();
        assertThat(resources.getLast().resourceDefaultVersion().inputReferences())
                .extracting(ref -> ref.versionId()).containsExactly(second.id(), first.id());
    }

    @Test
    void batchedSelectionsRejectAnotherIdentityAndAnotherProject() {
        UUID projectId = projectService.create(owner.userId(), "Scope", Project.AspectRatio.LANDSCAPE_16_9).id();
        UUID otherProject = projectService.create(owner.userId(), "Other", Project.AspectRatio.LANDSCAPE_16_9).id();
        var image = artifactService.create(owner.userId(), projectId, Artifact.Kind.IMAGE, "Empty", null);
        var sameProjectText = createText(owner.userId(), projectId, "Sibling");
        var otherProjectText = createText(owner.userId(), otherProject, "Other");
        for (var unrelated : List.of(sameProjectText, otherProjectText)) {
            var selection = new ArtifactService.Selection(image.artifact().id(),
                    unrelated.resourceDefaultVersion().id());
            assertThatThrownByCode("RESOURCE_NOT_FOUND",
                    () -> artifactService.readSelections(owner.userId(), projectId, Set.of(selection)));
        }
        var foreignIdentity = new ArtifactService.Selection(otherProjectText.artifact().id(), null);
        assertThatThrownByCode("RESOURCE_NOT_FOUND",
                () -> artifactService.readSelections(owner.userId(), projectId, Set.of(foreignIdentity)));
        assertThatThrownByCode("RESOURCE_NOT_FOUND", () -> artifactService.listProject(UUID.randomUUID(), projectId));
    }

    @Test
    void concurrentRevisionChangesSnapshotWatermarkAndCanvasContentTogether() {
        UUID projectId = projectService.create(owner.userId(), "Snapshot", Project.AspectRatio.LANDSCAPE_16_9).id();
        var first = createText(owner.userId(), projectId, "First");
        var identity = first.artifact();
        canvasService.apply(owner.userId(), projectId, List.of(place(UUID.randomUUID(), identity.id(), "0", "0")));
        var reader = new TransactionTemplate(transactions);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var beforeCommit = reader.execute(status -> {
            var before = snapshots.snapshot(owner.userId(), projectId);
            CompletableFuture.runAsync(() -> artifactService.revise(owner.userId(), projectId,
                    identity.id(), identity.version(), null, objectMapper.createObjectNode()
                            .put("format", "PLAIN_TEXT").put("text", "Second")))
                    .orTimeout(10, TimeUnit.SECONDS).join();
            var during = snapshots.snapshot(owner.userId(), projectId);
            assertThat(during.snapshotSeq()).isEqualTo(before.snapshotSeq());
            assertThat(during.canvas().getFirst().artifact().resourceDefaultVersion().id())
                    .isEqualTo(first.resourceDefaultVersion().id());
            return during;
        });
        var after = snapshots.snapshot(owner.userId(), projectId);
        assertThat(after.snapshotSeq()).isEqualTo(beforeCommit.snapshotSeq() + 1);
        assertThat(after.canvas().getFirst().artifact().resourceDefaultVersion().content().path("text").asText())
                .isEqualTo("Second");
    }

    private ArtifactService.ArtifactView createText(UUID ownerId, UUID projectId, String text) {
        return artifactService.create(
                ownerId,
                projectId,
                Artifact.Kind.TEXT,
                text,
                objectMapper.readTree(
                        "{\"format\":\"PLAIN_TEXT\",\"text\":\"" + text + "\"}"));
    }

    private CanvasService.PlaceArtifact place(
            UUID itemId, UUID artifactId, String x, String y) {
        return new CanvasService.PlaceArtifact(
                itemId,
                artifactId,
                decimal(x),
                decimal(y),
                decimal("280"),
                decimal("180"),
                1,
                null,
                false);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private void assertThatThrownByCode(String expectedCode, Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected ApiProblemException with code " + expectedCode);
        } catch (ApiProblemException problem) {
            assertThat(problem.code()).isEqualTo(expectedCode);
        }
    }
}

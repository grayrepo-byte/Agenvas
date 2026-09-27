package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
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

/** PostgreSQL evidence for atomic layout commands and presentation/content separation. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=canvas-integration-bootstrap-secret")
class CanvasPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.11-alpine");

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

    @Test
    void layoutPersistsFailedBatchRollsBackAndRemovingCardKeepsArtifact() {
        AdminPrincipal owner = identityService.setup(
                "canvas-integration-bootstrap-secret", "canvas-admin", "canvas-password-123");
        Project project = projectService.create(
                owner.userId(), "Canvas project", Project.AspectRatio.LANDSCAPE_16_9);
        ArtifactService.ArtifactView firstArtifact = createText(owner.userId(), project.id(), "One");
        ArtifactService.ArtifactView secondArtifact = createText(owner.userId(), project.id(), "Two");
        UUID firstItemId = UUID.randomUUID();
        UUID secondItemId = UUID.randomUUID();
        UUID duplicateItemId = UUID.randomUUID();

        CanvasService.PlaceArtifact firstPlacement =
                place(firstItemId, firstArtifact.artifact().id(), "20", "40");
        CanvasService.PlaceArtifact secondPlacement =
                place(secondItemId, secondArtifact.artifact().id(), "360", "40");
        CanvasService.PlaceArtifact duplicatePlacement =
                place(duplicateItemId, firstArtifact.artifact().id(), "700", "40");
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
        assertThat(saved.artifact().currentVersion().content().get("text").stringValue())
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

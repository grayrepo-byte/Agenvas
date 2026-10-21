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
        classes = AgenvasApplication.class)
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

    @Autowired private org.springframework.web.context.WebApplicationContext webContext;

    @Test
    void layoutPersistsFailedBatchRollsBackAndRemovingCardKeepsArtifact() throws Exception {
        AdminPrincipal owner = identityService.setup("canvas-admin", "canvas-password-123");
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
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        String canvasPath = "/api/v1/projects/" + project.id() + "/canvas";
        var retiredCommand = objectMapper.createObjectNode();
        retiredCommand.putArray("commands").addObject().put("type", "PLACE_AGENT")
                .put("itemId", UUID.randomUUID().toString()).put("agentId", UUID.randomUUID().toString())
                .put("x", 0).put("y", 0).put("width", 300).put("height", 300).put("zIndex", 0).put("locked", false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(canvasPath + "/commands")
                        .with(auth).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(retiredCommand.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var retiredConnection = objectMapper.createObjectNode().put("sourceCanvasItemId", secondItemId.toString())
                .put("targetCanvasItemId", duplicateItemId.toString()).put("sourceVersionId", UUID.randomUUID().toString())
                .put("relationType", "AGENT_IMAGE_INPUT").put("expectedTargetAgentVersion", 0);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(canvasPath + "/connections")
                        .with(auth).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(retiredConnection.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        String visible = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(canvasPath + "/items").with(auth))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(visible).doesNotContain("\"agent\"", "\"AGENT\"");
        assertThat(canvasService.list(owner.userId(), project.id())).hasSize(2);
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

package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL evidence for immutable content, typed references, and concurrent revision CAS. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=artifact-bootstrap-secret")
class ArtifactPostgresIT {

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
    private AssetService assetService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void versionsAreImmutableReferencesAreScopedAndSharedSceneRevisionIsLocal() throws Exception {
        assertThat(jdbcClient.sql("select version from flyway_schema_history order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("36");
        AdminPrincipal owner = identityService.setup(
                "artifact-bootstrap-secret", "artifact-admin", "artifact-password-123");
        Project project = projectService.create(
                owner.userId(), "Artifact project", Project.AspectRatio.LANDSCAPE_16_9);
        Project otherProject = projectService.create(
                owner.userId(), "Other project", Project.AspectRatio.SQUARE_1_1);

        UUID validAssetId = ImageAssetFixture.archive(assetService, owner.userId(), project.id());
        UUID foreignAssetId = ImageAssetFixture.archive(assetService, owner.userId(),
                otherProject.id());
        assertThatThrownByCode("ASSET_NOT_FOUND", () -> artifactService.create(owner.userId(),
                project.id(), Artifact.Kind.IMAGE, "Missing image", media(UUID.randomUUID())));
        assertThatThrownByCode("ASSET_NOT_FOUND", () -> artifactService.create(owner.userId(),
                project.id(), Artifact.Kind.IMAGE, "Foreign image", media(foreignAssetId)));
        assertThatThrownByCode("ARTIFACT_ASSET_KIND_INVALID", () -> artifactService.create(
                owner.userId(), project.id(), Artifact.Kind.VIDEO, "Wrong media", media(validAssetId)));
        ArtifactService.ArtifactView validImage = artifactService.create(owner.userId(),
                project.id(), Artifact.Kind.IMAGE, "Uploaded image", upload(validAssetId));
        assertThat(validImage.currentVersion().content().path("assetId").asText())
                .isEqualTo(validAssetId.toString());
        assertThat(validImage.currentVersion().content().has("sourceTaskId")).isFalse();
        assertThatThrownByCode("ASSET_NOT_FOUND", () -> artifactService.create(owner.userId(),
                project.id(), Artifact.Kind.IMAGE, "Foreign upload", upload(foreignAssetId)));
        assertThatThrownByCode("ARTIFACT_ORIGIN_INVALID", () -> artifactService.createFromAgent(
                owner.userId(), project.id(), UUID.randomUUID(), Artifact.Kind.IMAGE,
                "Forged upload", upload(validAssetId)));

        ArtifactService.ArtifactView text = artifactService.create(
                owner.userId(),
                project.id(),
                Artifact.Kind.TEXT,
                "Brief",
                json("{\"format\":\"MARKDOWN\",\"text\":\"Initial\"}"));
        ArtifactService.ArtifactView revisedText = artifactService.revise(
                owner.userId(),
                project.id(),
                text.artifact().id(),
                0,
                null,
                json("{\"format\":\"MARKDOWN\",\"text\":\"Revised\"}"));
        assertThat(revisedText.artifact().version()).isEqualTo(1);
        assertThat(artifactService.listVersions(
                        owner.userId(), project.id(), text.artifact().id()))
                .extracting(version -> version.content().get("text").stringValue())
                .containsExactly("Revised", "Initial");
        assertThatThrownByCode(
                "ARTIFACT_VERSION_CONFLICT",
                () -> artifactService.revise(
                        owner.userId(),
                        project.id(),
                        text.artifact().id(),
                        0,
                        null,
                        json("{\"format\":\"MARKDOWN\",\"text\":\"Stale\"}")));
        assertDatabaseRejectsVersionMutation(text.currentVersion().id());

        ArtifactService.ArtifactView scene = artifactService.create(
                owner.userId(), project.id(), Artifact.Kind.SCENE, "Cafe", scene("Day"));
        ArtifactService.ArtifactView otherScene = artifactService.create(
                owner.userId(), otherProject.id(), Artifact.Kind.SCENE, "Other", scene("Night"));
        assertThatThrownByCode(
                "ARTIFACT_REFERENCE_INVALID",
                () -> artifactService.create(
                        owner.userId(),
                        project.id(),
                        Artifact.Kind.SHOT,
                        "Cross-project shot",
                        shot(1, otherScene.currentVersion().id())));
        assertThatThrownByCode(
                "ARTIFACT_REFERENCE_INVALID",
                () -> artifactService.create(
                        owner.userId(),
                        project.id(),
                        Artifact.Kind.SHOT,
                        "Wrong kind shot",
                        shot(1, text.currentVersion().id())));

        ArtifactService.ArtifactView firstShot = artifactService.create(
                owner.userId(),
                project.id(),
                Artifact.Kind.SHOT,
                "Shot one",
                shot(1, scene.currentVersion().id()));
        ArtifactService.ArtifactView secondShot = artifactService.create(
                owner.userId(),
                project.id(),
                Artifact.Kind.SHOT,
                "Shot two",
                shot(2, scene.currentVersion().id()));
        ArtifactService.ArtifactView revisedScene = artifactService.revise(
                owner.userId(),
                project.id(),
                scene.artifact().id(),
                0,
                null,
                scene("Night"));
        artifactService.revise(
                owner.userId(),
                project.id(),
                secondShot.artifact().id(),
                0,
                null,
                shot(2, revisedScene.currentVersion().id()));
        assertThat(artifactService
                        .get(owner.userId(), project.id(), firstShot.artifact().id())
                        .currentVersion()
                        .content()
                        .get("sceneVersionId")
                        .stringValue())
                .isEqualTo(scene.currentVersion().id().toString());
        assertThat(artifactService
                        .get(owner.userId(), project.id(), secondShot.artifact().id())
                        .currentVersion()
                        .content()
                        .get("sceneVersionId")
                        .stringValue())
                .isEqualTo(revisedScene.currentVersion().id().toString());

        UUID foreignOwner = UUID.randomUUID();
        assertThatThrownByCode(
                "RESOURCE_NOT_FOUND",
                () -> artifactService.get(foreignOwner, project.id(), text.artifact().id()));

        assertConcurrentRevisionCreatesOneVersion(owner.userId(), project.id());
    }

    private void assertConcurrentRevisionCreatesOneVersion(UUID ownerId, UUID projectId)
            throws Exception {
        ArtifactService.ArtifactView artifact = artifactService.create(
                ownerId,
                projectId,
                Artifact.Kind.TEXT,
                "Concurrent",
                json("{\"format\":\"PLAIN_TEXT\",\"text\":\"v1\"}"));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 8; index++) {
                int attempt = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        artifactService.revise(
                                ownerId,
                                projectId,
                                artifact.artifact().id(),
                                0,
                                null,
                                json("{\"format\":\"PLAIN_TEXT\",\"text\":\"v2-"
                                        + attempt
                                        + "\"}"));
                        return "CREATED";
                    } catch (ApiProblemException problem) {
                        return problem.code();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get());
            }
            assertThat(outcomes).filteredOn("CREATED"::equals).hasSize(1);
            assertThat(outcomes).filteredOn("ARTIFACT_VERSION_CONFLICT"::equals).hasSize(7);
        }
        assertThat(artifactService.listVersions(ownerId, projectId, artifact.artifact().id()))
                .extracting(ArtifactVersion::versionNo)
                .containsExactly(2, 1);
    }

    private void assertDatabaseRejectsVersionMutation(UUID versionId) {
        try {
            jdbcClient.sql("update artifact_version set schema_version = 2 where id = :id")
                    .param("id", versionId)
                    .update();
            throw new AssertionError("Expected immutable version update to fail");
        } catch (DataAccessException expected) {
            assertThat(expected.getMostSpecificCause().getMessage())
                    .contains("artifact_version rows are immutable");
        }
    }

    private JsonNode scene(String timeOfDay) {
        return json("""
                {
                  "name":"Cafe",
                  "location":"Shanghai",
                  "timeOfDay":"%s",
                  "lighting":"Soft light",
                  "style":"Minimal",
                  "referenceVersionIds":[]
                }
                """.formatted(timeOfDay));
    }

    private JsonNode shot(int order, UUID sceneVersionId) {
        return json("""
                {
                  "order":%d,
                  "durationSeconds":5,
                  "description":"Coffee shot",
                  "camera":"Dolly in",
                  "action":"Pour coffee",
                  "characterVersionIds":[],
                  "sceneVersionId":"%s"
                }
                """.formatted(order, sceneVersionId));
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    private JsonNode media(UUID assetId) {
        return json("""
                {"assetId":"%s","prompt":"Uploaded image","providerConfigVersion":1,
                 "workflowVersion":"manual-upload-v1","parameters":{},"sourceTaskId":"%s"}
                """.formatted(assetId, UUID.randomUUID()));
    }

    private JsonNode upload(UUID assetId) {
        return json("""
                {"sourceType":"UPLOAD","assetId":"%s"}
                """.formatted(assetId));
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

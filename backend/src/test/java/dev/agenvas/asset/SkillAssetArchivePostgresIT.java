package dev.agenvas.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.application.SkillAssetArchiveService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.application.LibraryService.PinnedSkillAsset;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Verified files and real PostgreSQL exercise only owned application/HTTP boundaries. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.library.worker-enabled=false", "agenvas.skill.worker-enabled=false", "agenvas.tasks.scheduler-enabled=false"})
class SkillAssetArchivePostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = temporaryRoot();
    private static AdminPrincipal owner;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AssetService assets;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired LibraryService library;
    @Autowired SkillAssetArchiveService skillAssets;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;

    @BeforeEach void setup() {
        if (owner == null)
            owner = identities.setup("skill-archive-admin", "skill-password-123");
    }

    @Test void fixedSkillBytesSurvivePermanentSourceDeletionAndInstallWithoutCanvasSideEffects() throws Exception {
        var fixture = source("Surviving reference");
        var source = library.skillAssetSource(owner.userId(), fixture.entryId(), 0);
        UUID pinId = UUID.randomUUID();
        var pinned = library.pinForSkill(owner.userId(), fixture.entryId(), 0, pinId);
        // Publication operations persist this internal snapshot; restart must not need the entry.
        var recovered = mapper.readValue(mapper.writeValueAsString(pinned), PinnedSkillAsset.class);
        var trashed = library.trash(owner.userId(), fixture.entryId(), 0, false);
        library.delete(owner.userId(), fixture.entryId(), trashed.version());
        assertThatThrownBy(() -> library.get(owner.userId(), fixture.entryId()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));

        UUID skillFileId = UUID.randomUUID();
        var retained = skillAssets.archivePinned(owner.userId(), skillFileId, recovered);
        var replay = skillAssets.archivePinned(owner.userId(), skillFileId, recovered);
        assertThat(replay).isEqualTo(retained);
        assertThat(retained.sha256()).isEqualTo(source.contentHash());
        skillAssets.discardPin(owner.userId(), pinned.pinKey());
        assertThat(Files.readAllBytes(skillAssets.file(owner.userId(), retained, false).path()))
                .containsExactly(fixture.bytes());

        Project target = projects.create(owner.userId(), "Skill target", Project.AspectRatio.LANDSCAPE_16_9);
        UUID projectAssetId = UUID.randomUUID();
        Asset prepared = skillAssets.prepareProjectImport(owner.userId(), target.id(), projectAssetId, retained);
        var installed = skillAssets.registerProjectImport(owner.userId(), prepared, source.title());
        assertThat(installed.resourceDefaultVersion().content().path("sourceType").asText()).isEqualTo("SKILL_IMPORT");
        assertThat(installed.resourceDefaultVersion().content().path("assetId").asText()).isEqualTo(projectAssetId.toString());
        assertThat(Files.readAllBytes(assets.get(owner.userId(), target.id(), projectAssetId).path()))
                .containsExactly(fixture.bytes());
        assertThat(canvas.list(owner.userId(), target.id())).isEmpty();
        assertThat(artifacts.listProject(owner.userId(), target.id())).hasSize(1);
        assertThatThrownBy(() -> skillAssets.prepareProjectImport(UUID.randomUUID(), target.id(), UUID.randomUUID(), retained))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void sourceSelectionRequiresOwnershipAndActiveExactVersionAndHttpCannotForgeSkillOrigin() throws Exception {
        var fixture = source("Owned reference");
        assertThatThrownBy(() -> library.pinForSkill(UUID.randomUUID(), fixture.entryId(), 0, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThatThrownBy(() -> library.pinForSkill(owner.userId(), fixture.entryId(), 1, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("VERSION_CONFLICT"));
        var trashed = library.trash(owner.userId(), fixture.entryId(), 0, false);
        assertThatThrownBy(() -> library.pinForSkill(owner.userId(), fixture.entryId(), trashed.version(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("LIBRARY_ENTRY_TRASHED"));

        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String response = mvc.perform(post("/api/v1/projects/" + fixture.projectId() + "/artifacts")
                .with(auth).with(csrf()).header("Idempotency-Key", "forged-skill-origin")
                .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "kind", Artifact.Kind.IMAGE, "title", "Forged source", "content", Map.of(
                                "sourceType", "SKILL_IMPORT", "assetId", fixture.assetId())))))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).path("code").asText()).isEqualTo("ARTIFACT_ORIGIN_INVALID");
        assertThat(artifacts.listProject(owner.userId(), fixture.projectId())).isEmpty();
    }

    @Test void plannedCleanupHandlesPartialFilesAndNeverDeletesRegisteredProjectReferences() throws Exception {
        var fixture = source("Cleanup reference");
        var pin = library.pinForSkill(owner.userId(), fixture.entryId(), 0, UUID.randomUUID());
        var retained = skillAssets.archivePinned(owner.userId(), UUID.randomUUID(), pin);
        skillAssets.discardPin(owner.userId(), pin.pinKey());
        Project target = projects.create(owner.userId(), "Cleanup target", Project.AspectRatio.LANDSCAPE_16_9);
        var partial = skillAssets.prepareProjectImport(owner.userId(), target.id(), UUID.randomUUID(), retained);
        // Synthetic crash fixture: the thumbnail remains but the installed original is gone.
        Files.delete(STORAGE_ROOT.resolve(partial.objectKey()));
        assertThat(Files.exists(STORAGE_ROOT.resolve(partial.thumbnailKey()))).isTrue();
        assertThat(skillAssets.cleanupPlannedSkillImport(owner.userId(), target.id(), partial.id())).isTrue();
        assertThat(Files.exists(STORAGE_ROOT.resolve(partial.thumbnailKey()))).isFalse();
        assertThat(skillAssets.cleanupPlannedSkillImport(owner.userId(), target.id(), partial.id())).isTrue();

        var ready = skillAssets.prepareProjectImport(owner.userId(), target.id(), UUID.randomUUID(), retained);
        skillAssets.registerProjectImport(owner.userId(), ready, "Retained reference");
        assertThat(skillAssets.cleanupPreparedProjectImport(owner.userId(), ready)).isTrue();
        assertThat(skillAssets.cleanupPlannedSkillImport(owner.userId(), target.id(), ready.id())).isTrue();
        assertThat(Files.readAllBytes(assets.get(owner.userId(), target.id(), ready.id()).path())).containsExactly(fixture.bytes());
        assertThatThrownBy(() -> skillAssets.cleanupPlannedSkillImport(UUID.randomUUID(), target.id(), ready.id()))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void expiredPreparationCannotRecreateFilesAfterPlannedCleanup() throws Exception {
        var fixture = source("Fenced reference");
        var pin = library.pinForSkill(owner.userId(), fixture.entryId(), 0, UUID.randomUUID());
        var retained = skillAssets.archivePinned(owner.userId(), UUID.randomUUID(), pin);
        skillAssets.discardPin(owner.userId(), pin.pinKey());
        Project target = projects.create(owner.userId(), "Fenced target", Project.AspectRatio.LANDSCAPE_16_9);
        UUID id = UUID.randomUUID();
        assertThat(skillAssets.cleanupPlannedSkillImport(owner.userId(), target.id(), id)).isTrue();
        assertThatThrownBy(() -> skillAssets.prepareProjectImport(owner.userId(), target.id(), id, retained, () -> false))
                .isInstanceOf(IllegalStateException.class).hasMessage("Import preparation lease changed");
        assertThat(assets.listProjectAssets(owner.userId(), target.id())).isEmpty();
        try (var files = Files.walk(STORAGE_ROOT)) {
            assertThat(files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(id.toString()));
        }
    }

    private record Source(UUID projectId, UUID assetId, UUID entryId, byte[] bytes) {}

    private Source source(String title) throws Exception {
        Project project = projects.create(owner.userId(), title, Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        byte[] bytes = Files.readAllBytes(assets.get(owner.userId(), project.id(), assetId).path());
        var command = library.upload(owner.userId(), title, LibraryEntry.Category.OTHER, Asset.MediaKind.IMAGE,
                "skill-fixture-" + UUID.randomUUID(), new MockMultipartFile("file", "reference.png", "image/png", bytes));
        assertThat(library.processNext()).isTrue();
        var finished = library.command(owner.userId(), command.id());
        assertThat(finished.status()).isEqualTo(LibraryCommand.Status.SUCCEEDED);
        return new Source(project.id(), assetId, UUID.fromString(finished.result().path("entryId").asText()), bytes);
    }

    private static Path temporaryRoot() {
        try {
            Path root = Files.createTempDirectory("agenvas-skill-archive-test-");
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            return root;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    @AfterAll static void cleanupThisTestsFiles() throws java.io.IOException {
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}

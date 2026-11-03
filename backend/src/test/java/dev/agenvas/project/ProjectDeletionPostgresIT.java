package dev.agenvas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.application.ResourceCatalogService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL locks, authenticated HTTP and synthetic media; no external model requests. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class)
class ProjectDeletionPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE_ROOT = storageRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired ResourceCatalogService resources;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired DirectMediaTaskService direct;
    @Autowired TaskService tasks;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    private AdminPrincipal owner;
    private MockMvc mvc;

    @BeforeEach void setup() {
        owner = jdbc.sql("select id from app_user where login_name = 'deletion-admin'")
                .query(UUID.class).optional().map(id -> new AdminPrincipal(id, "deletion-admin"))
                .orElseGet(() -> identities.setup("deletion-admin", "synthetic-password-123"));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void enforcesDeletionContractAndHidesAllProjectRoutesWithoutRemovingHistory() throws Exception {
        Project project = projects.create(owner.userId(), "Delete archived", Project.AspectRatio.SQUARE_1_1);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var image = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Saved image",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", assetId.toString()));
        String base = "/api/v1/projects/" + project.id();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        mvc.perform(delete(base).param("expectedVersion", "0").with(auth).with(csrf())).andExpect(status().isConflict());
        project = projects.archive(owner.userId(), project.id(), project.version());
        String version = Long.toString(project.version());
        mvc.perform(delete(base).param("expectedVersion", version).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete(base).param("expectedVersion", version).with(auth)).andExpect(status().isForbidden());
        var other = new AdminPrincipal(UUID.randomUUID(), "other-owner");
        mvc.perform(delete(base).param("expectedVersion", version).with(csrf())
                .with(authentication(new UsernamePasswordAuthenticationToken(other, null, List.of()))))
                .andExpect(status().isNotFound());
        mvc.perform(delete(base).with(auth).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete(base).param("expectedVersion", "-1").with(auth).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete(base).param("expectedVersion", "0").with(auth).with(csrf())).andExpect(status().isConflict());
        jdbc.sql("update project set active_run_id=:run where id=:id").param("run", UUID.randomUUID()).param("id", project.id()).update();
        mvc.perform(delete(base).param("expectedVersion", version).with(auth).with(csrf())).andExpect(status().isConflict());
        jdbc.sql("update project set active_run_id=null where id=:id").param("id", project.id()).update();
        mvc.perform(delete(base).param("expectedVersion", version).with(auth).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(base).param("expectedVersion", version).with(auth).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(base).param("expectedVersion", "0").with(auth).with(csrf())).andExpect(status().isConflict());

        for (String suffix : List.of("", "/snapshot", "/canvas/items", "/resources", "/events",
                "/artifacts/" + image.artifact().id(), "/assets/" + assetId + "/content")) {
            mvc.perform(get(base + suffix).with(auth)).andExpect(status().isNotFound());
        }
        UUID deletedId = project.id();
        assertThat(projects.list(owner.userId(), true, null, 100).items()).noneMatch(row -> row.id().equals(deletedId));
        assertThat(resources.list(owner.userId(), null, "", null, 100).items()).noneMatch(row -> row.projectId().equals(deletedId));
        assertThat(jdbc.sql("select status from project where id=:id").param("id", deletedId).query(String.class).single()).isEqualTo("DELETED");
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:id").param("id", deletedId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from asset where project_id=:id").param("id", deletedId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test void concurrentDeletionReplaysOnceWithoutDoubleIncrementingTheVersion() throws Exception {
        Project active = projects.create(owner.userId(), "Concurrent delete", Project.AspectRatio.SQUARE_1_1);
        Project archived = projects.archive(owner.userId(), active.id(), active.version());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); projects.delete(owner.userId(), archived.id(), archived.version()); return true; });
            var second = executor.submit(() -> { start.await(); projects.delete(owner.userId(), archived.id(), archived.version()); return true; });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(projects.getRetainedProject(owner.userId(), archived.id()).version()).isEqualTo(archived.version() + 1);
    }

    @Test void recordsAcceptedMediaResultsAfterDeletionWithoutRestoringTheProject() throws Exception {
        Project project = projects.create(owner.userId(), "Late result", Project.AspectRatio.SQUARE_1_1);
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Late image", null);
        UUID item = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.artifact().id());
        var draft = CanvasMediaFixture.save(drafts, owner.userId(), project.id(), item, 0, "Synthetic image", null, null, null);
        var task = direct.run(owner.userId(), project.id(), artifact.artifact().id(), item, draft.version(), "delete-late-result");
        var lease = tasks.claimDue("delete-submitter", 1).getFirst();
        assertThat(lease.id()).isEqualTo(task.id());
        tasks.beginSubmission(lease, "delete-submitter");
        tasks.waitForProvider(lease, "delete-submitter", "synthetic-request", Instant.now().plusSeconds(60));
        var archived = projects.archive(owner.userId(), project.id(), project.version());
        projects.delete(owner.userId(), project.id(), archived.version());
        var asset = assets.archiveTaskImage(owner.userId(), project.id(), task.id(), () -> new java.io.ByteArrayInputStream(png()));
        jdbc.sql("update task set next_action_at=now()-interval '1 second' where id=:id").param("id", task.id()).update();
        var poll = tasks.claimProviderPolls("delete-poller", 1).getFirst();
        var content = mapper.createObjectNode().put("assetId", asset.id().toString()).put("prompt", "Synthetic image")
                .put("workflowVersion", "synthetic-v1").put("sourceTaskId", task.id().toString());
        content.putObject("parameters");
        var result = tasks.succeedWithArtifacts(poll, "delete-poller", List.of(
                new TaskService.ArchivedProviderResult(0, Artifact.Kind.IMAGE, true, content),
                new TaskService.ArchivedProviderResult(1, Artifact.Kind.IMAGE, false, content.deepCopy())), null);
        assertThat(result.selected()).isFalse();
        assertThat(projects.getRetainedProject(owner.userId(), project.id()).status()).isEqualTo(Project.Status.DELETED);
        assertThat(jdbc.sql("select status from task where id=:id").param("id", task.id()).query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select count(*) from artifact_version where id=:id").param("id", result.versionId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from artifact_version where project_id=:id").param("id", project.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:id").param("id", project.id()).query(Integer.class).single()).isEqualTo(1);
        mvc.perform(get("/api/v1/projects/" + project.id() + "/tasks/" + task.id())
                .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isNotFound());
    }

    private static byte[] png() {
        try (var output = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static Path storageRoot() {
        try { return Files.createTempDirectory("agenvas-project-deletion-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}

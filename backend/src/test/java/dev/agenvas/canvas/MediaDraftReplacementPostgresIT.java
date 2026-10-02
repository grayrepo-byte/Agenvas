package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.MediaDraftRestoreService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Replacements preserve atomicity and share project-before-draft lock ordering with ordinary saves. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=synthetic-template-replacement-secret")
class MediaDraftReplacementPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path assetsRoot;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> assetsRoot.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired MediaDraftRestoreService replacements;
    @Autowired javax.sql.DataSource dataSource;
    private static AdminPrincipal sharedOwner;

    @Test void replacesConnectedInputsAtomicallyWithoutGenerating() throws Exception {
        var owner = owner();
        var project = projects.create(owner.userId(), "Template replacement test", Project.AspectRatio.LANDSCAPE_16_9);
        UUID originalAsset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var source = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Original image",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", originalAsset)));
        var target = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Target", null);
        UUID sourceItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), source.artifact().id());
        UUID targetItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), target.artifact().id());
        connections.connect(owner.userId(), project.id(), sourceItem, targetItem, source.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.MEDIA_INPUT, 0);
        var before = drafts.get(owner.userId(), project.id(), targetItem);
        int eventCount = events(project.id());
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v1/projects/" + project.id() + "/canvas-items/" + targetItem + "/media-draft/replace-inputs";
        String invalid = mapper.writeValueAsString(Map.of("expectedVersion", before.version(), "prompt", "New prompt",
                "mediaInputs", List.of(Map.of("versionId", UUID.randomUUID(), "role", "REFERENCE", "color", "#7C3AED"))));
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest());
        assertThat(drafts.get(owner.userId(), project.id(), targetItem)).isEqualTo(before);
        assertThat(events(project.id())).isEqualTo(eventCount);
        assertThat(lineCount(project.id())).isEqualTo(1);
        String stale = mapper.writeValueAsString(Map.of("expectedVersion", before.version() - 1,
                "prompt", "Stale", "mediaInputs", List.of()));
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(stale))
                .andExpect(status().isConflict());
        assertThat(lineCount(project.id())).isEqualTo(1);
        UUID replacementAsset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var replacement = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Replacement",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", replacementAsset)));
        String valid = mapper.writeValueAsString(Map.of("expectedVersion", before.version(), "prompt", "New prompt",
                "mediaInputs", List.of(Map.of("versionId", replacement.resourceDefaultVersion().id(),
                        "role", "REFERENCE", "color", "#7C3AED"))));
        mvc.perform(post(path).with(auth).contentType("application/json").content(valid))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(valid))
                .andExpect(status().isOk());
        var saved = drafts.get(owner.userId(), project.id(), targetItem);
        assertThat(saved.prompt()).isEqualTo("New prompt");
        assertThat(saved.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(replacement.resourceDefaultVersion().id());
            assertThat(input.sources()).singleElement().satisfies(reason -> assertThat(reason.connectionId()).isNull());
        });
        assertThat(lineCount(project.id())).isZero();
        assertThat(jdbc.sql("select count(*) from task where project_id = :project")
                .param("project", project.id()).query(Integer.class).single()).isZero();
    }
    @Test void concurrentReplacementAndOrdinarySaveSerializeBeforeTakingTheDraftLock() throws Exception {
        var owner = owner();
        var project = projects.create(owner.userId(), "Concurrent replacement test", Project.AspectRatio.LANDSCAPE_16_9);
        UUID image = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var source = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Concurrent original",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", image)));
        var target = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Concurrent target", null);
        UUID sourceItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), source.artifact().id());
        UUID targetItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), target.artifact().id());
        connections.connect(owner.userId(), project.id(), sourceItem, targetItem, source.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.MEDIA_INPUT, 0);
        var before = drafts.get(owner.userId(), project.id(), targetItem);
        int beforeEvents = events(project.id());

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                var projectGate = dataSource.getConnection()) {
            projectGate.setAutoCommit(false);
            int gatePid;
            try (var statement = projectGate.createStatement(); var row = statement.executeQuery("select pg_backend_pid()")) {
                row.next(); gatePid = row.getInt(1);
            }
            try (var lock = projectGate.prepareStatement("select id from project where id = ? for update")) {
                lock.setObject(1, project.id()); lock.executeQuery().close();
            }
            try {
                var replace = pool.submit(() -> result(() -> replacements.replaceInputs(owner.userId(), project.id(), targetItem,
                        before.version(), "Replacement wins", null, null, null, null, List.of(), List.of())));
                awaitProjectWaiters(gatePid, 1);
                // The replacement is paused at the project gate. It must not have acquired
                // the draft row first: the old order makes this NOWAIT probe fail reliably.
                try (var probe = dataSource.getConnection()) {
                    probe.setAutoCommit(false);
                    try (var lock = probe.prepareStatement("select canvas_item_id from media_draft where project_id = ? and canvas_item_id = ? for update nowait")) {
                        lock.setObject(1, project.id()); lock.setObject(2, targetItem);
                        lock.executeQuery().close();
                    } finally { probe.rollback(); }
                }
                var ordinary = pool.submit(() -> result(() -> drafts.save(owner.userId(), project.id(), targetItem,
                        before.version(), "Ordinary save wins", null, null, null, null, List.of(), List.of())));
                awaitProjectWaiters(gatePid, 2);
                projectGate.rollback();
                var outcomes = List.of(replace.get(10, java.util.concurrent.TimeUnit.SECONDS),
                        ordinary.get(10, java.util.concurrent.TimeUnit.SECONDS));
                assertThat(outcomes.stream().filter("VERSION_CONFLICT"::equals).count()).isEqualTo(1);
                String winningPrompt = outcomes.stream().filter(value -> !value.equals("VERSION_CONFLICT")).findFirst().orElseThrow();
                var saved = drafts.get(owner.userId(), project.id(), targetItem);
                assertThat(saved.prompt()).isEqualTo(winningPrompt);
                if (winningPrompt.equals("Replacement wins")) {
                    assertThat(saved.mediaInputs()).isEmpty();
                    assertThat(lineCount(project.id())).isZero();
                    assertThat(saved.version()).isEqualTo(before.version() + 2);
                } else {
                    assertThat(saved.mediaInputs()).singleElement().satisfies(input ->
                            assertThat(input.versionId()).isEqualTo(source.resourceDefaultVersion().id()));
                    assertThat(lineCount(project.id())).isEqualTo(1);
                    assertThat(saved.version()).isEqualTo(before.version() + 1);
                }
                assertThat(events(project.id())).isEqualTo(beforeEvents + 1);
            } finally { projectGate.rollback(); }
        }
    }

    private void awaitProjectWaiters(int gatePid, int minimum) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql("""
                        with recursive blocked(pid) as (
                            select pid from pg_stat_activity where :gate = any(pg_blocking_pids(pid))
                            union
                            select activity.pid from pg_stat_activity activity join blocked parent
                                on parent.pid = any(pg_blocking_pids(activity.pid))
                        ) select count(*) from blocked
                        """)
                        .param("gate", gatePid).query(Integer.class).single()).isGreaterThanOrEqualTo(minimum));
    }
    private String result(java.util.function.Supplier<dev.agenvas.artifact.domain.MediaDraft> mutation) {
        try { return mutation.get().prompt(); }
        catch (ApiProblemException failure) {
            if (!failure.code().equals("VERSION_CONFLICT")) throw failure;
            return failure.code();
        }
    }
    private AdminPrincipal owner() {
        // Either independent test may initialize the deployment's single synthetic owner.
        if (sharedOwner == null) sharedOwner = identities.setup("synthetic-template-replacement-secret", "template-test-admin",
                "synthetic-password-123");
        return sharedOwner;
    }

    private int lineCount(UUID project) {
        return jdbc.sql("select count(*) from canvas_connection where project_id = :project")
                .param("project", project).query(Integer.class).single();
    }
    private int events(UUID project) {
        return jdbc.sql("select count(*) from project_event where project_id = :project")
                .param("project", project).query(Integer.class).single();
    }
}

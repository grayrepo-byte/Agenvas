package dev.agenvas.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
@SpringBootTest(classes = AgenvasApplication.class)
class MediaDraftReplacementPostgresIT {
    private static final UUID PHOTOGRAPHIC_STYLE_ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path assetsRoot;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> assetsRoot.toString());
        registry.add("agenvas.credentials.master-key-base64", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
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
    @Autowired dev.agenvas.provider.application.MediaCapabilityService capabilities;
    @Autowired dev.agenvas.asset.infrastructure.MediaToolRunner mediaTools;
    @Autowired javax.sql.DataSource dataSource;
    private static AdminPrincipal sharedOwner;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Artifact.Kind.class, names = {"AUDIO", "VIDEO"})
    void mixedWorkflowSwitchRetainsTheMatchingMediaLineAndDisconnectClearsItsSlot(Artifact.Kind kind) throws Exception {
        var owner = owner();
        var project = projects.create(owner.userId(), "Mixed media switch " + kind, Project.AspectRatio.SQUARE_1_1);
        var connection = capabilities.createConnection(UUID.randomUUID().toString(), "Synthetic mixed workflow", "RUNNINGHUB",
                "https://www.runninghub.ai", "fake-runninghub-key");
        var definition = mapper.readTree("""
                {"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123456",
                "usePersonalQueue":false,"addMetadata":false,"fields":[
                {"key":"sound","label":"Sound","type":"AUDIO","required":false,"advanced":false,"nodeId":"1","fieldName":"audio"},
                {"key":"clip","label":"Clip","type":"VIDEO","required":false,"advanced":false,"nodeId":"2","fieldName":"video"}],
                "outputs":[{"kind":"%s","primary":true,"maxCount":1}]}
                """.formatted(kind));
        var initial = capabilities.publishCapability(connection.id(), "Initial " + kind, "RUNNINGHUB_" + kind,
                mapper.createObjectNode().set("runningHub", definition));
        var nextDefinition = definition.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) nextDefinition).putArray("fields").add(mapper.readTree("""
                {"key":"source","label":"Source","type":"%s","required":true,"advanced":false,"nodeId":"3","fieldName":"media"}
                """.formatted(kind)));
        var next = capabilities.publishCapability(connection.id(), "Next " + kind, "RUNNINGHUB_" + kind,
                mapper.createObjectNode().set("runningHub", nextDefinition));
        var target = artifacts.create(owner.userId(), project.id(), kind, "Target", null);
        UUID targetItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), target.artifact().id());
        var draft = drafts.save(owner.userId(), project.id(), targetItem, 0, "Initial", mapper.createObjectNode(),
                null, initial.id(), kind == Artifact.Kind.VIDEO ? dev.agenvas.artifact.domain.MediaDraft.VideoInputMode.TEXT : null,
                List.of(), List.of(), null);
        var versions = new java.util.EnumMap<Artifact.Kind, UUID>(Artifact.Kind.class);
        for (Artifact.Kind sourceKind : List.of(Artifact.Kind.AUDIO, Artifact.Kind.VIDEO)) {
            UUID asset = sourceKind == Artifact.Kind.AUDIO
                    ? dev.agenvas.testing.AudioAssetFixture.archive(assets, mediaTools, owner.userId(), project.id())
                    : syntheticVideoAsset(owner.userId(), project.id());
            var source = artifacts.create(owner.userId(), project.id(), sourceKind, "Synthetic " + sourceKind,
                    mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", asset)));
            UUID sourceItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), source.artifact().id());
            versions.put(sourceKind, source.resourceDefaultVersion().id());
            draft = connections.connect(owner.userId(), project.id(), sourceItem, targetItem, versions.get(sourceKind),
                    CanvasConnection.RelationType.MEDIA_INPUT, draft.version()).draft();
        }
        var role = kind == Artifact.Kind.AUDIO ? dev.agenvas.artifact.domain.MediaDraft.InputRole.AUDIO_REFERENCE
                : dev.agenvas.artifact.domain.MediaDraft.InputRole.VIDEO_REFERENCE;
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v1/projects/" + project.id() + "/canvas-items/" + targetItem + "/media-draft";
        var body = mapper.createObjectNode().put("expectedVersion", draft.version()).put("prompt", "Use ￼")
                .put("capabilityId", next.id().toString());
        if (kind == Artifact.Kind.VIDEO) body.put("videoInputMode", "GENERAL_REFERENCE");
        body.putObject("parameters").putObject("dynamicValues").put("source", versions.get(kind).toString());
        body.putArray("mediaInputs").add(mapper.valueToTree(Map.of("versionId", versions.get(kind), "role", role, "color", "#7C3AED")));
        body.putArray("mentions").add(mapper.valueToTree(Map.of("versionId", versions.get(kind), "role", role)));
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        var saved = drafts.get(owner.userId(), project.id(), targetItem);
        assertThat(saved.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(versions.get(kind));
            assertThat(input.role()).isEqualTo(role);
            assertThat(input.sources()).singleElement().satisfies(source ->
                    assertThat(source.type()).isEqualTo(dev.agenvas.artifact.domain.MediaDraft.SourceType.CONNECTION));
        });
        assertThat(saved.mentions()).hasSize(1);
        var lines = connections.list(owner.userId(), project.id());
        assertThat(lines).singleElement().satisfies(line -> assertThat(line.sourceArtifactVersionId()).isEqualTo(versions.get(kind)));
        var disconnected = connections.disconnect(owner.userId(), project.id(), lines.getFirst().id(), saved.version(), null).draft();
        assertThat(disconnected.mediaInputs()).isEmpty();
        assertThat(disconnected.parameters().path("dynamicValues").has("source")).isFalse();
        assertThat(disconnected.mentions()).isEmpty();
        assertThat(disconnected.prompt()).isEqualTo("Use ");
        assertThat(lineCount(project.id())).isZero();
    }

    private UUID syntheticVideoAsset(UUID ownerId, UUID projectId) throws Exception {
        var path = java.nio.file.Files.createTempFile("agenvas-switch-video-", ".mp4");
        try {
            mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "color=c=blue:s=320x240:r=24", "-t", "1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", path.toString()));
            try (var input = java.nio.file.Files.newInputStream(path)) {
                return assets.archiveVideo(ownerId, projectId, input).id();
            }
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @Test void capabilitySwitchKeepsMatchedLinesAndAtomicallyRemovesUnmatchedLines() throws Exception {
        var owner = owner();
        var project = projects.create(owner.userId(), "Capability switch", Project.AspectRatio.SQUARE_1_1);
        var target = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Target", null);
        UUID targetItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), target.artifact().id());
        List<UUID> versions = new java.util.ArrayList<>();
        for (int index = 0; index < 2; index++) {
            var source = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Reference " + index,
                    mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId",
                            ImageAssetFixture.archive(assets, owner.userId(), project.id()))));
            UUID sourceItem = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), source.artifact().id());
            versions.add(source.resourceDefaultVersion().id());
            connections.connect(owner.userId(), project.id(), sourceItem, targetItem, versions.get(index),
                    CanvasConnection.RelationType.MEDIA_INPUT, index);
        }
        var connection = capabilities.createConnection(UUID.randomUUID().toString(), "Synthetic RunningHub", "RUNNINGHUB",
                "https://www.runninghub.ai", "fake-runninghub-key");
        var capability = capabilities.publishCapability(connection.id(), "Synthetic editor", "RUNNINGHUB_IMAGE",
                mapper.readTree("""
                {"runningHub":{"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123456",
                "usePersonalQueue":false,"addMetadata":false,"fields":[
                {"key":"hero","label":"Hero","type":"IMAGE","required":true,"advanced":false,"nodeId":"1","fieldName":"image"}],
                "outputs":[{"kind":"IMAGE","primary":true,"maxCount":1}]}}
                """));
        var before = drafts.get(owner.userId(), project.id(), targetItem);
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String path = "/api/v1/projects/" + project.id() + "/canvas-items/" + targetItem + "/media-draft";
        var body = mapper.valueToTree(Map.of("expectedVersion", before.version(), "prompt", "Edit ￼",
                "capabilityId", capability.id(), "parameters", Map.of("dynamicValues", Map.of("hero", versions.getFirst())),
                "mediaInputs", List.of(Map.of("versionId", versions.getFirst(), "role", "REFERENCE", "color", "#7C3AED")),
                "mentions", List.of(Map.of("versionId", versions.getFirst(), "role", "REFERENCE"))));
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        var saved = drafts.get(owner.userId(), project.id(), targetItem);
        assertThat(saved.mediaInputs()).singleElement().satisfies(input -> {
            assertThat(input.versionId()).isEqualTo(versions.getFirst());
            assertThat(input.sources()).singleElement().satisfies(source ->
                    assertThat(source.type()).isEqualTo(dev.agenvas.artifact.domain.MediaDraft.SourceType.CONNECTION));
        });
        assertThat(connections.list(owner.userId(), project.id())).singleElement().satisfies(line ->
                assertThat(line.sourceArtifactVersionId()).isEqualTo(versions.getFirst()));
        assertThat(saved.parameters().path("dynamicValues").path("hero").asText()).isEqualTo(versions.getFirst().toString());
        // A failed switch must roll back topology cleanup as well as the draft and event.
        int beforeEvents = events(project.id());
        var invalid = mapper.createObjectNode().put("expectedVersion", saved.version()).put("prompt", "Invalid")
                .put("capabilityId", UUID.randomUUID().toString());
        invalid.putArray("mediaInputs");
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(invalid)))
                .andExpect(status().isNotFound());
        assertThat(drafts.get(owner.userId(), project.id(), targetItem)).isEqualTo(saved);
        assertThat(lineCount(project.id())).isEqualTo(1);
        assertThat(events(project.id())).isEqualTo(beforeEvents);
        mvc.perform(put(path).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
        assertThat(lineCount(project.id())).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from task where project_id = :project")
                .param("project", project.id()).query(Integer.class).single()).isZero();
    }

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
        var connectedDraft = drafts.get(owner.userId(), project.id(), targetItem);
        drafts.save(owner.userId(), project.id(), targetItem,
                connectedDraft.version(), "Existing prompt", connectedDraft.parameters(), null,
                connectedDraft.capabilityId(), connectedDraft.videoInputMode(), List.of(), List.of(),
                PHOTOGRAPHIC_STYLE_ID);
        // Compare persisted snapshots: PostgreSQL rounds the save response's nanosecond timestamps.
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
                "styleId", before.styleId(),
                "mediaInputs", List.of(Map.of("versionId", replacement.resourceDefaultVersion().id(),
                        "role", "REFERENCE", "color", "#7C3AED"))));
        mvc.perform(post(path).with(auth).contentType("application/json").content(valid))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(auth).with(csrf()).contentType("application/json").content(valid))
                .andExpect(status().isOk());
        var saved = drafts.get(owner.userId(), project.id(), targetItem);
        assertThat(saved.prompt()).isEqualTo("New prompt");
        assertThat(saved.styleId()).isEqualTo(PHOTOGRAPHIC_STYLE_ID);
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
                        before.version(), "Replacement wins", null, null, null, null, List.of(), List.of(), null)));
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
                        before.version(), "Ordinary save wins", null, null, null, null, List.of(), List.of(), null)));
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
        if (sharedOwner == null) sharedOwner = identities.setup("template-test-admin",
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

package dev.agenvas.canvas;

import static dev.agenvas.canvas.application.CanvasCharacterNodeModels.*;
import static dev.agenvas.character.application.CharacterModels.*;

import static org.assertj.core.api.Assertions.*;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasCharacterNodeService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = AgenvasApplication.class,
        properties = {
            "agenvas.llm.scheduler-enabled=false",
            "agenvas.provider.media.scheduler-enabled=false",
            "agenvas.skill.scheduler-enabled=false"
        })
class CanvasCharacterNodePostgresIT {
    @TempDir static Path storage;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agenvas.storage.root", () -> storage.toString());
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired CharacterService characters;
    @Autowired CanvasCharacterNodeService nodes;
    @Autowired CanvasService canvas;

    @Test
    void fixedProjectRevisionHasIndependentCasAndDoesNotCreateAnArtifact() {
        UUID owner = owner();
        UUID project =
                projects.create(owner, "Character nodes", Project.AspectRatio.SQUARE_1_1).id();
        UUID character = UUID.randomUUID(), look = UUID.randomUUID(), item = UUID.randomUUID();
        CharacterContent first =
                new CharacterContent(
                        "Aster",
                        "Original identity",
                        List.of(new CharacterLook(look, "Coat", "Blue coat", "", "", List.of())),
                        null);
        characters.save(owner, project, character, new SaveCharacter(UUID.randomUUID(), 0, first));
        Selection selection = new Selection(character, 1, look, List.of(), true, false);
        CreateNode request =
                new CreateNode(
                        selection,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("300"),
                        new BigDecimal("300"),
                        0,
                        null,
                        false);
        var created = nodes.create(owner, project, item, request);
        assertThat(created.item().subjectType()).isEqualTo(CanvasItem.SubjectType.CHARACTER);
        assertThat(created.artifact()).isNull();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        1,
                        new CharacterContent("Aster", "New identity", first.looks(), null)));
        var persisted =
                canvas.list(owner, project).stream()
                        .filter(e -> e.item().id().equals(item))
                        .findFirst()
                        .orElseThrow();
        assertThat(persisted.characterNode().newerVersionAvailable()).isTrue();
        assertThat(persisted.characterNode().snapshot().description())
                .isEqualTo("Original identity");
        assertThat(nodes.create(owner, project, item, request).characterNode().version()).isZero();
        nodes.update(
                owner,
                project,
                item,
                new UpdateSelection(
                        0, 0, new Selection(character, 2, look, List.of(), true, false)));
        assertThatThrownBy(
                        () ->
                                nodes.update(
                                        owner, project, item, new UpdateSelection(0, 0, selection)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired dev.agenvas.artifact.application.ArtifactService artifacts;
    @Autowired dev.agenvas.asset.application.AssetService assets;
    @Autowired dev.agenvas.artifact.application.MediaDraftService drafts;
    @Autowired dev.agenvas.canvas.application.CanvasItemQueryService itemQueries;
    @Autowired dev.agenvas.export.application.ProjectExportManifestService exports;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    private UUID owner() {
        return jdbc.sql("select id from app_user where login_name='character-node-admin'")
                .query(UUID.class)
                .optional()
                .orElseGet(
                        () ->
                                identities
                                        .setup(
                                                "character-node-admin",
                                                "character-node-password-123")
                                        .userId());
    }

    private UUID image(UUID owner, UUID project, String title) {
        UUID asset = dev.agenvas.testing.ImageAssetFixture.archive(assets, owner, project);
        return artifacts
                .create(
                        owner,
                        project,
                        dev.agenvas.artifact.domain.Artifact.Kind.IMAGE,
                        title,
                        mapper.createObjectNode()
                                .put("sourceType", "UPLOAD")
                                .put("assetId", asset.toString()))
                .artifact()
                .id();
    }

    private CreateNode create(Selection selection) {
        return new CreateNode(
                selection,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("300"),
                new BigDecimal("300"),
                0,
                null,
                false);
    }

    @Test
    void explicitNodeApplicationPreservesManualCharactersAndInputsUntilConfirmedRemoval() {
        UUID owner = owner(),
                project =
                        projects.create(
                                        owner,
                                        "Explicit character inputs",
                                        Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID refArtifact = image(owner, project, "Identity reference"),
                manualArtifact = image(owner, project, "Manual reference");
        UUID reference = artifacts.get(owner, project, refArtifact).resourceDefaultVersion().id();
        UUID manual = artifacts.get(owner, project, manualArtifact).resourceDefaultVersion().id();
        UUID target =
                dev.agenvas.support.CanvasMediaFixture.place(
                        canvas, owner, project, image(owner, project, "Shot"));
        var initial =
                drafts.save(
                        owner,
                        project,
                        target,
                        0,
                        "Keep user text",
                        mapper.createObjectNode(),
                        null,
                        null,
                        null,
                        List.of(
                                new dev.agenvas.artifact.application.MediaDraftService
                                        .SaveMediaInput(
                                        manual,
                                        dev.agenvas.artifact.domain.MediaDraft.InputRole.REFERENCE,
                                        "#112233")),
                        List.of(),
                        null);
        UUID independent = UUID.randomUUID(), independentLook = UUID.randomUUID();
        characters.save(
                owner,
                project,
                independent,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent(
                                "Manual character",
                                "Keep this identity",
                                List.of(
                                        new CharacterLook(
                                                independentLook, "Plain", "", "", "", List.of())),
                                null)));
        var manualSelections =
                List.of(
                        new CharacterBindingSelection(
                                independent, 1, independentLook, true, false));
        var manualPreview =
                characters.preview(
                        owner,
                        project,
                        target,
                        new PreviewCharacterBindings(
                                0, initial.version(), manualSelections, ClearMode.REMOVE_OWNED));
        var before =
                characters.apply(
                        owner,
                        project,
                        target,
                        new ApplyCharacterBindings(
                                UUID.randomUUID(),
                                0,
                                initial.version(),
                                manualSelections,
                                ClearMode.REMOVE_OWNED,
                                manualPreview.previewHash()));
        UUID character = UUID.randomUUID(),
                look = UUID.randomUUID(),
                source = UUID.randomUUID(),
                edgeId = UUID.randomUUID();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent(
                                "Node character",
                                "Fixed identity",
                                List.of(
                                        new CharacterLook(
                                                look,
                                                "Coat",
                                                "Blue coat",
                                                "",
                                                "",
                                                List.of(
                                                        new CharacterView(
                                                                reference, "Identity", "Face")))),
                                null)));
        Selection selected = new Selection(character, 1, look, List.of(reference), true, false);
        nodes.create(owner, project, source, create(selected));
        assertThatThrownBy(() -> itemQueries.requireArtifactItem(owner, project, source))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        nodes.connect(owner, project, new Connect(edgeId, source, target, 0, 0, 0));
        assertThat(drafts.get(owner, project, target).version())
                .isEqualTo(before.draft().version());
        var preview =
                nodes.preview(
                        owner,
                        project,
                        target,
                        new Preview(
                                before.binding().version(),
                                before.draft().version(),
                                ClearMode.REMOVE_OWNED));
        assertThat(preview.characterPreview().snapshots())
                .extracting(CharacterSnapshot::characterId)
                .containsExactly(independent, character);
        var apply =
                new Apply(
                        UUID.randomUUID(),
                        before.binding().version(),
                        before.draft().version(),
                        ClearMode.REMOVE_OWNED,
                        preview.previewHash());
        var applied = nodes.apply(owner, project, target, apply);
        assertThat(applied.draft().prompt())
                .contains("Keep user text", "Manual character", "Node character");
        assertThat(applied.draft().mediaInputs())
                .extracting(dev.agenvas.artifact.domain.MediaDraft.MediaInput::versionId)
                .containsExactly(manual, reference);
        nodes.disconnect(owner, project, edgeId, new Disconnect(0));
        assertThat(drafts.get(owner, project, target).version())
                .isEqualTo(applied.draft().version());
        assertThat(nodes.apply(owner, project, target, apply).draft().version())
                .isEqualTo(applied.draft().version());
        var remove =
                nodes.preview(
                        owner,
                        project,
                        target,
                        new Preview(
                                applied.binding().version(),
                                applied.draft().version(),
                                ClearMode.REMOVE_OWNED));
        assertThat(remove.characterPreview().snapshots())
                .extracting(CharacterSnapshot::characterId)
                .containsExactly(independent);
        var cleared =
                nodes.apply(
                        owner,
                        project,
                        target,
                        new Apply(
                                UUID.randomUUID(),
                                applied.binding().version(),
                                applied.draft().version(),
                                ClearMode.REMOVE_OWNED,
                                remove.previewHash()));
        assertThat(cleared.draft().prompt())
                .contains("Manual character", "Keep user text")
                .doesNotContain("Node character");
        assertThat(cleared.draft().mediaInputs())
                .extracting(dev.agenvas.artifact.domain.MediaDraft.MediaInput::versionId)
                .containsExactly(manual);
        UUID copy = UUID.randomUUID();
        var duplicate =
                canvas.duplicate(
                        owner,
                        project,
                        source,
                        copy,
                        0,
                        0,
                        new BigDecimal("400"),
                        BigDecimal.ZERO,
                        new BigDecimal("300"),
                        new BigDecimal("300"),
                        1);
        assertThat(duplicate.draft()).isNull();
        assertThat(duplicate.item().characterNode().selection()).isEqualTo(selected);
        assertThat(duplicate.item().characterNode().connections()).isEmpty();
        assertThat(exports.build(owner, project).schemaVersion()).isEqualTo(12);
        assertThat(exports.build(owner, project).canvasItems())
                .filteredOn(i -> i.id().equals(copy))
                .allSatisfy(
                        i -> {
                            assertThat(i.characterNode()).isNotNull();
                            assertThat(i.mediaDraft()).isNull();
                        });
        canvas.apply(owner, project, List.of(new CanvasService.SetLocked(source, 0, true)));
        assertThatThrownBy(
                        () ->
                                nodes.update(
                                        owner,
                                        project,
                                        source,
                                        new UpdateSelection(1, 0, selected)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThatThrownBy(
                        () ->
                                canvas.apply(
                                        owner,
                                        project,
                                        List.of(new CanvasService.Remove(source, 1))))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        canvas.apply(owner, project, List.of(new CanvasService.SetLocked(source, 1, false)));
        canvas.apply(owner, project, List.of(new CanvasService.Remove(source, 2)));
        assertThat(characters.getRevision(owner, project, character, 1).content().name())
                .isEqualTo("Node character");
        assertThat(
                        jdbc.sql("select count(*) from task where project_id=:project")
                                .param("project", project)
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Autowired dev.agenvas.canvas.application.CanvasEditService editor;

    @Test
    void staleTopologyAndSelectionPreviewsAreRejectedAndCopiesCanBeUndoneAndRedone() {
        UUID owner = owner(),
                project =
                        projects.create(
                                        owner,
                                        "Stale character preview",
                                        Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID character = UUID.randomUUID(), look = UUID.randomUUID(), source = UUID.randomUUID();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent(
                                "Fixed",
                                "Identity",
                                List.of(new CharacterLook(look, "Main", "Coat", "", "", List.of())),
                                null)));
        Selection selected = new Selection(character, 1, look, List.of(), true, false);
        nodes.create(owner, project, source, create(selected));
        UUID target =
                dev.agenvas.support.CanvasMediaFixture.place(
                        canvas, owner, project, image(owner, project, "Target"));
        var initial = drafts.get(owner, project, target);
        UUID edge = UUID.randomUUID();
        nodes.connect(owner, project, new Connect(edge, source, target, 0, 0, 0));
        var preview =
                nodes.preview(
                        owner,
                        project,
                        target,
                        new Preview(0, initial.version(), ClearMode.REMOVE_OWNED));
        nodes.disconnect(owner, project, edge, new Disconnect(0));
        nodes.connect(owner, project, new Connect(edge, source, target, 0, 0, 0));
        assertThatThrownBy(
                        () ->
                                nodes.apply(
                                        owner,
                                        project,
                                        target,
                                        new Apply(
                                                UUID.randomUUID(),
                                                0,
                                                initial.version(),
                                                ClearMode.REMOVE_OWNED,
                                                preview.previewHash())))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThat(drafts.get(owner, project, target).version()).isEqualTo(initial.version());
        nodes.disconnect(owner, project, edge, new Disconnect(0));
        UUID duplicate = UUID.randomUUID();
        var edit =
                editor.edit(
                        owner,
                        project,
                        new dev.agenvas.canvas.application.CanvasEditModels.Edit(
                                UUID.randomUUID(),
                                dev.agenvas.canvas.application.CanvasEditModels.Operation.DUPLICATE,
                                List.of(),
                                List.of(),
                                List.of(
                                        new dev.agenvas.canvas.application.CanvasEditModels.Copy(
                                                source,
                                                duplicate,
                                                0,
                                                0L,
                                                null,
                                                new BigDecimal("400"),
                                                BigDecimal.ZERO,
                                                new BigDecimal("300"),
                                                new BigDecimal("300"),
                                                0,
                                                null))));
        var undone =
                editor.replay(
                        owner,
                        project,
                        edit.receipt().id(),
                        new dev.agenvas.canvas.application.CanvasEditModels.Replay(
                                UUID.randomUUID(),
                                0,
                                dev.agenvas.canvas.application.CanvasEditModels.Direction.UNDO));
        assertThat(canvas.list(owner, project))
                .extracting(e -> e.item().id())
                .doesNotContain(duplicate);
        editor.replay(
                owner,
                project,
                edit.receipt().id(),
                new dev.agenvas.canvas.application.CanvasEditModels.Replay(
                        UUID.randomUUID(),
                        undone.receipt().revision(),
                        dev.agenvas.canvas.application.CanvasEditModels.Direction.REDO));
        var copied =
                canvas.list(owner, project).stream()
                        .filter(e -> e.item().id().equals(duplicate))
                        .findFirst()
                        .orElseThrow();
        assertThat(copied.characterNode().selection()).isEqualTo(selected);
        assertThat(copied.characterNode().version()).isGreaterThan(0);
        nodes.connect(
                owner,
                project,
                new Connect(
                        UUID.randomUUID(),
                        duplicate,
                        target,
                        copied.item().version(),
                        copied.characterNode().version(),
                        0));
        assertThatThrownBy(
                        () ->
                                editor.replay(
                                        owner,
                                        project,
                                        edit.receipt().id(),
                                        new dev.agenvas.canvas.application.CanvasEditModels.Replay(
                                                UUID.randomUUID(),
                                                2,
                                                dev.agenvas.canvas.application.CanvasEditModels
                                                        .Direction.UNDO)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }

    @Test
    void concurrentSelectionCasAllowsOnlyOneWriterAndForeignProjectsCannotConnect()
            throws Exception {
        UUID owner = owner(),
                project =
                        projects.create(owner, "Character CAS", Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID other =
                projects.create(owner, "Other character scope", Project.AspectRatio.SQUARE_1_1)
                        .id();
        UUID character = UUID.randomUUID(),
                firstLook = UUID.randomUUID(),
                secondLook = UUID.randomUUID(),
                thirdLook = UUID.randomUUID(),
                source = UUID.randomUUID();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent(
                                "Concurrent",
                                "Identity",
                                List.of(
                                        new CharacterLook(
                                                firstLook, "First", "", "", "", List.of()),
                                        new CharacterLook(
                                                secondLook, "Second", "", "", "", List.of()),
                                        new CharacterLook(
                                                thirdLook, "Third", "", "", "", List.of())),
                                null)));
        nodes.create(
                owner,
                project,
                source,
                create(new Selection(character, 1, firstLook, List.of(), true, false)));
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts =
                    List.of(secondLook, thirdLook).stream()
                            .map(
                                    look ->
                                            executor.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            nodes.update(
                                                                    owner,
                                                                    project,
                                                                    source,
                                                                    new UpdateSelection(
                                                                            0,
                                                                            0,
                                                                            new Selection(
                                                                                    character, 1,
                                                                                    look, List.of(),
                                                                                    true, false)));
                                                            return "SAVED";
                                                        } catch (
                                                                dev.agenvas.shared.error
                                                                                .ApiProblemException
                                                                        conflict) {
                                                            return conflict.code();
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            assertThat(List.of(attempts.get(0).get(), attempts.get(1).get()))
                    .containsExactlyInAnyOrder("SAVED", "CHARACTER_NODE_VERSION_CONFLICT");
        }
        var node =
                canvas.list(owner, project).stream()
                        .filter(e -> e.item().id().equals(source))
                        .findFirst()
                        .orElseThrow()
                        .characterNode();
        assertThat(node.version()).isEqualTo(1);
        UUID foreignTarget =
                dev.agenvas.support.CanvasMediaFixture.place(
                        canvas, owner, other, image(owner, other, "Foreign"));
        assertThatThrownBy(
                        () ->
                                nodes.connect(
                                        owner,
                                        project,
                                        new Connect(
                                                UUID.randomUUID(), source, foreignTarget, 0, 1, 0)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        assertThat(nodes.connections(owner, project)).isEmpty();
    }

    @Autowired org.springframework.web.context.WebApplicationContext webContext;

    @Test
    void revisionHttpReadKeepsOldUnselectedViewsAndRejectsForeignOwnersAndNativeSubjects()
            throws Exception {
        UUID owner = owner(),
                project =
                        projects.create(owner, "Revision HTTP", Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID character = UUID.randomUUID(), look = UUID.randomUUID();
        UUID portrait =
                artifacts
                        .get(owner, project, image(owner, project, "Portrait"))
                        .resourceDefaultVersion()
                        .id();
        UUID side =
                artifacts
                        .get(owner, project, image(owner, project, "Side"))
                        .resourceDefaultVersion()
                        .id();
        var original =
                new CharacterContent(
                        "Old name",
                        "Fixed identity",
                        List.of(
                                new CharacterLook(
                                        look,
                                        "First",
                                        "Coat",
                                        "",
                                        "",
                                        List.of(
                                                new CharacterView(portrait, "Identity", ""),
                                                new CharacterView(side, "Side", "")))),
                        null);
        characters.save(
                owner, project, character, new SaveCharacter(UUID.randomUUID(), 0, original));
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        1,
                        new CharacterContent("New name", "Changed", original.looks(), null)));
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                webContext)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        var principal =
                new dev.agenvas.identity.application.AdminPrincipal(owner, "character-node-admin");
        var auth =
                org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal, null, List.of()));
        String path = "/api/v1/projects/" + project + "/characters/" + character + "/revisions/1";
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        path)
                                .with(auth))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.content.name")
                                .value("Old name"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.content.looks[0].views.length()")
                                .value(2));
        var foreign =
                new dev.agenvas.identity.application.AdminPrincipal(
                        UUID.randomUUID(), "foreign-owner");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        path)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors
                                                .authentication(
                                                        new org.springframework.security
                                                                .authentication
                                                                .UsernamePasswordAuthenticationToken(
                                                                foreign, null, List.of()))))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        var request =
                mapper.valueToTree(
                        create(new Selection(character, 1, look, List.of(portrait), true, false)));
        ((tools.jackson.databind.node.ObjectNode) request.path("selection"))
                .put("nativeSubjectId", "unregistered-subject");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/v1/projects/"
                                                + project
                                                + "/canvas/character-nodes/"
                                                + UUID.randomUUID())
                                .with(auth)
                                .with(
                                        org.springframework.security.test.web.servlet.request
                                                .SecurityMockMvcRequestPostProcessors.csrf())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(request)))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isBadRequest());
    }

    @Test
    void textOnlyRolesApplyAndTopologyReplaysNeverResurrectOrDeleteNewConnections() {
        UUID owner = owner(),
                project =
                        projects.create(
                                        owner,
                                        "Text character topology",
                                        Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID character = UUID.randomUUID(), source = UUID.randomUUID();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent("Narrator", "A text-only identity", List.of(), null)));
        nodes.create(
                owner,
                project,
                source,
                create(new Selection(character, 1, null, List.of(), true, false)));
        UUID target =
                dev.agenvas.support.CanvasMediaFixture.place(
                        canvas, owner, project, image(owner, project, "Target"));
        UUID edgeId = UUID.randomUUID();
        var connect = new Connect(edgeId, source, target, 0, 0, 0);
        nodes.connect(owner, project, connect);
        var before = drafts.get(owner, project, target);
        var preview =
                nodes.preview(
                        owner,
                        project,
                        target,
                        new Preview(0, before.version(), ClearMode.REMOVE_OWNED));
        var applied =
                nodes.apply(
                        owner,
                        project,
                        target,
                        new Apply(
                                UUID.randomUUID(),
                                0,
                                before.version(),
                                ClearMode.REMOVE_OWNED,
                                preview.previewHash()));
        assertThat(applied.draft().prompt()).contains("A text-only identity");
        canvas.apply(
                owner,
                project,
                List.of(new CanvasService.UpdateTitle(source, 0, "Moved after response loss")));
        assertThat(nodes.connect(owner, project, connect).id()).isEqualTo(edgeId);
        nodes.disconnect(owner, project, edgeId, new Disconnect(0));
        nodes.connect(owner, project, connect);
        assertThat(nodes.connections(owner, project)).isEmpty();
        UUID next = UUID.randomUUID();
        nodes.connect(owner, project, new Connect(next, source, target, 1, 0, 0));
        nodes.disconnect(owner, project, edgeId, new Disconnect(0));
        assertThat(nodes.connections(owner, project))
                .extracting(CharacterConnection::id)
                .containsExactly(next);
        UUID unknown = UUID.randomUUID();
        nodes.disconnect(owner, project, unknown, new Disconnect(0));
        assertThatThrownBy(
                        () ->
                                nodes.connect(
                                        owner,
                                        project,
                                        new Connect(unknown, source, target, 1, 0, 0)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }

    @Autowired dev.agenvas.task.application.DirectMediaTaskService media;
    @Autowired dev.agenvas.task.application.TaskService tasks;

    @Test
    void acceptedMockTaskKeepsAppliedCharacterWhenNodeSelectionChanges() {
        UUID owner = owner(),
                project =
                        projects.create(owner, "Frozen node task", Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID character = UUID.randomUUID(), source = UUID.randomUUID();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent("Original", "First identity", List.of(), null)));
        nodes.create(
                owner,
                project,
                source,
                create(new Selection(character, 1, null, List.of(), true, false)));
        UUID artifact = image(owner, project, "Target");
        UUID target =
                dev.agenvas.support.CanvasMediaFixture.place(canvas, owner, project, artifact);
        nodes.connect(owner, project, new Connect(UUID.randomUUID(), source, target, 0, 0, 0));
        var before = drafts.get(owner, project, target);
        var preview =
                nodes.preview(
                        owner,
                        project,
                        target,
                        new Preview(0, before.version(), ClearMode.REMOVE_OWNED));
        var applied =
                nodes.apply(
                        owner,
                        project,
                        target,
                        new Apply(
                                UUID.randomUUID(),
                                0,
                                before.version(),
                                ClearMode.REMOVE_OWNED,
                                preview.previewHash()));
        var accepted =
                media.run(
                        owner,
                        project,
                        artifact,
                        target,
                        applied.draft().version(),
                        "node-frozen-mock-task");
        var frozen =
                mapper.readTree(mapper.writeValueAsString(accepted.input().path("mediaInput")));
        assertThat(frozen.at("/characterBindings/0/characterVersion").asLong()).isEqualTo(1);
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        1,
                        new CharacterContent("Updated", "Second identity", List.of(), null)));
        nodes.update(
                owner,
                project,
                source,
                new UpdateSelection(
                        0, 0, new Selection(character, 2, null, List.of(), true, false)));
        assertThat(drafts.get(owner, project, target).version())
                .isEqualTo(applied.draft().version());
        assertThat(drafts.get(owner, project, target).prompt())
                .contains("First identity")
                .doesNotContain("Second identity");
        assertThat(
                        mapper.readTree(
                                mapper.writeValueAsString(
                                        tasks.get(owner, project, accepted.id())
                                                .input()
                                                .path("mediaInput"))))
                .isEqualTo(frozen);
    }

    @Test
    void readOnlyReceiptsSurviveLocksTargetDeletionAndArchiveWithoutLeakingAcrossScopes()
            throws Exception {
        UUID owner = owner(),
                project =
                        projects.create(
                                        owner,
                                        "Archived receipt recovery",
                                        Project.AspectRatio.SQUARE_1_1)
                                .id();
        UUID character = UUID.randomUUID(), source = UUID.randomUUID();
        characters.save(
                owner,
                project,
                character,
                new SaveCharacter(
                        UUID.randomUUID(),
                        0,
                        new CharacterContent(
                                "Receipt character", "Fixed identity", List.of(), null)));
        nodes.create(
                owner,
                project,
                source,
                create(new Selection(character, 1, null, List.of(), true, false)));
        UUID target =
                dev.agenvas.support.CanvasMediaFixture.place(
                        canvas, owner, project, image(owner, project, "Receipt target"));
        UUID connection = UUID.randomUUID();
        nodes.connect(owner, project, new Connect(connection, source, target, 0, 0, 0));
        var before = drafts.get(owner, project, target);
        var preview =
                nodes.preview(
                        owner,
                        project,
                        target,
                        new Preview(0, before.version(), ClearMode.REMOVE_OWNED));
        UUID command = UUID.randomUUID();
        nodes.apply(
                owner,
                project,
                target,
                new Apply(
                        command,
                        0,
                        before.version(),
                        ClearMode.REMOVE_OWNED,
                        preview.previewHash()));
        canvas.apply(owner, project, List.of(new CanvasService.SetLocked(source, 0, true)));
        assertThat(nodes.connectionReceipt(owner, project, connection).id()).isEqualTo(connection);
        assertThat(
                        nodes.applicationReceipt(owner, project, target, command)
                                .binding()
                                .canvasItemId())
                .isEqualTo(target);
        canvas.apply(owner, project, List.of(new CanvasService.Remove(target, 0)));
        projects.archive(owner, project, projects.get(owner, project).version());
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(
                                webContext)
                        .apply(
                                org.springframework.security.test.web.servlet.setup
                                        .SecurityMockMvcConfigurers.springSecurity())
                        .build();
        var principal =
                new dev.agenvas.identity.application.AdminPrincipal(owner, "character-node-admin");
        var auth =
                org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal, null, List.of()));
        String base = "/api/v1/projects/" + project;
        String connectionPath = base + "/canvas/character-connections/" + connection + "/receipt";
        String applyPath =
                base + "/canvas-items/" + target + "/character-node-bindings/receipts/" + command;
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        connectionPath)
                                .with(auth))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.id")
                                .value(connection.toString()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.requestHash")
                                .doesNotExist());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        applyPath)
                                .with(auth))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.binding.canvasItemId")
                                .value(target.toString()))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.requestHash")
                                .doesNotExist());
        var foreign =
                new dev.agenvas.identity.application.AdminPrincipal(
                        UUID.randomUUID(), "foreign-owner");
        var foreignAuth =
                org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.authentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(foreign, null, List.of()));
        for (String path : List.of(connectionPath, applyPath)) {
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            path)
                                    .with(foreignAuth))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isNotFound());
        }
        for (String path :
                List.of(
                        base + "/canvas/character-connections/" + UUID.randomUUID() + "/receipt",
                        base
                                + "/canvas-items/"
                                + UUID.randomUUID()
                                + "/character-node-bindings/receipts/"
                                + command,
                        base
                                + "/canvas-items/"
                                + target
                                + "/character-node-bindings/receipts/"
                                + UUID.randomUUID())) {
            mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            path)
                                    .with(auth))
                    .andExpect(
                            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .status()
                                    .isNotFound());
        }
    }
}

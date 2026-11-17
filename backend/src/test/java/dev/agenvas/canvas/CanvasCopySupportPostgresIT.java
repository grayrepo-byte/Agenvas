package dev.agenvas.canvas;

import static dev.agenvas.character.application.CharacterModels.*;
import static dev.agenvas.canvas.application.CanvasEditModels.*;
import static dev.agenvas.testing.AgentFixture.configureDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasCopySupport;
import dev.agenvas.canvas.application.CanvasEditService;
import dev.agenvas.canvas.application.CanvasEditRepository;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.canvas.application.CanvasItemRepository;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentMediaApprovalRepository;
import dev.agenvas.llm.domain.AgentMediaApproval;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.task.application.TaskRepository;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL copy/redo, immutable source snapshots, removal ownership and downstream-use guards. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CanvasCopySupportPostgresIT {
    @TempDir
    static Path storage;
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
    }
    @Autowired
    IdentityService identities;
    @Autowired
    ProjectService projects;
    @Autowired
    ArtifactService artifacts;
    @Autowired
    AssetService assets;
    @Autowired
    CanvasService canvas;
    @Autowired
    CanvasItemRepository items;
    @Autowired
    MediaDraftService drafts;
    @Autowired
    CharacterService characters;
    @Autowired
    CanvasCopySupport copies;
    @Autowired
    CanvasEditService editor;
    @Autowired
    CanvasEditRepository edits;
    @Autowired
    CanvasConnectionService connections;
    @Autowired
    dev.agenvas.canvas.application.CanvasCharacterNodeService characterNodes;
    private final Map<UUID, Receipt> receipts = new HashMap<>();
    @Autowired
    ProjectEventService events;
    @Autowired
    TaskRepository tasks;
    @Autowired
    AgentMediaApprovalRepository approvals;
    @Autowired
    AgentInstanceService agents;
    @Autowired
    AgentRunService runs;
    @Autowired
    StoryboardService storyboards;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    org.springframework.web.context.WebApplicationContext context;
    private UUID owner;
    private Project project;

    @BeforeEach
    void setup() {
        owner = jdbc.sql("select id from app_user where login_name='copy-support-admin'").query(UUID.class).optional()
                .orElseGet(() -> identities.setup("copy-support-admin", "synthetic-copy-password-123").userId());
        project = projects.create(owner, "Copy support", Project.AspectRatio.SQUARE_1_1);
    }

    @Test
    void mediaRedoKeepsFixedCharacterContentAndPreciseRemovalOwnershipAndFencesOldAutosave() {
        UUID manual = image("Manual reference"), owned = image("Role-owned reference");
        CanvasItem source = emptyMedia();
        var initial = drafts.save(owner, project.id(), source.id(), 0, "Saved shot", mapper.createObjectNode(), null,
                null, null,
                List.of(new MediaDraftService.SaveMediaInput(manual, MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                List.of(), null);
        UUID characterId = UUID.randomUUID(), lookId = UUID.randomUUID();
        CharacterContent content = new CharacterContent("Aster", "Fixed character", List.of(new CharacterLook(lookId,
                "Travel coat", "Blue coat", "Calm", "", List.of(new CharacterView(owned, "Front", ""),
                        new CharacterView(manual, "Already manually present", "")))),
                null);
        var profile = characters.save(owner, project.id(), characterId,
                new SaveCharacter(UUID.randomUUID(), 0, content));
        var selections = List.of(new CharacterBindingSelection(characterId, profile.version(), lookId, true, false));
        var preview = characters.preview(owner, project.id(), source.id(),
                new PreviewCharacterBindings(0, initial.version(), selections, ClearMode.REMOVE_OWNED));
        var applied = characters.apply(owner, project.id(), source.id(),
                new ApplyCharacterBindings(UUID.randomUUID(), 0,
                        initial.version(), selections, ClearMode.REMOVE_OWNED, preview.previewHash()));
        long before = events.replayWindow(owner, project.id()).latestSequence();
        Copied copy = clone(source);
        assertThat(events.replayWindow(owner, project.id()).latestSequence()).isEqualTo(before + 1);
        MediaDraft first = drafts.get(owner, project.id(), copy.target().id());
        assertThat(first.prompt()).contains("Blue coat");
        assertThat(first.mediaInputs())
                .allSatisfy(input -> assertThat(input.sources()).singleElement().satisfies(sourceId -> {
                    assertThat(sourceId.type()).isEqualTo(MediaDraft.SourceType.MANUAL);
                    assertThat(sourceId.connectionId()).isNull();
                }));
        assertThat(first.mediaInputs()).flatExtracting(MediaDraft.MediaInput::sources)
                .doesNotContainAnyElementsOf(
                        applied.draft().mediaInputs().stream().flatMap(input -> input.sources().stream()).toList());
        JsonNode guard = copies.capture(owner, project.id(), copy.target());
        delete(copy.target());
        drafts.save(owner, project.id(), source.id(), applied.draft().version(), "Source changed later",
                mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null);
        characters.save(owner, project.id(), characterId, new SaveCharacter(UUID.randomUUID(), profile.version(),
                new CharacterContent("Revised Aster", "Changed later", content.looks(), null)));
        CanvasItem restored = restore(copy, guard);
        MediaDraft second = drafts.get(owner, project.id(), restored.id());
        assertThat(second.prompt()).isEqualTo(first.prompt());
        assertThat(second.mediaInputs()).isEqualTo(first.mediaInputs());
        assertThat(second.version()).isGreaterThan(first.version());
        assertThatThrownBy(() -> drafts.save(owner, project.id(), restored.id(), first.version(), "Old autosave",
                mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null)).isInstanceOf(ApiProblemException.class);
        var binding = characters.list(owner, project.id()).bindings().stream()
                .filter(value -> value.canvasItemId().equals(restored.id())).findFirst().orElseThrow();
        assertThat(binding.snapshots().getFirst().name()).isEqualTo("Aster");
        assertThat(binding.version()).isGreaterThan(guard.at("/characters/binding/version").asLong());
        var clearPreview = characters.preview(owner, project.id(), restored.id(),
                new PreviewCharacterBindings(binding.version(), second.version(), List.of(), ClearMode.REMOVE_OWNED));
        var cleared = characters.apply(owner, project.id(), restored.id(),
                new ApplyCharacterBindings(UUID.randomUUID(), binding.version(),
                        second.version(), List.of(), ClearMode.REMOVE_OWNED, clearPreview.previewHash()));
        assertThat(cleared.draft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(manual);
        assertThat(cleared.draft().prompt()).isEqualTo("Saved shot");
    }

    @Test
    void textCopiesHaveIndependentBodiesIncludingEmptyTextAndRedoRejectsEditingDeletedCopyArtifact() {
        for (String text : List.of("Original writing", "", "\n    Indented writing  \n\tcontinued  \n\n")) {
            var sourceArtifact = artifacts.create(owner, project.id(), Artifact.Kind.TEXT, "Text", text(text));
            CanvasItem source = place(sourceArtifact.artifact().id());
            Copied copied = clone(source);
            assertThat(copied.target().subjectId()).isNotEqualTo(source.subjectId());
            assertThat(artifacts.get(owner, project.id(), copied.target().subjectId()).resourceDefaultVersion()
                    .content().path("text").asText()).isEqualTo(text);
            JsonNode guard = copies.capture(owner, project.id(), copied.target());
            delete(copied.target());
            artifacts.revise(owner, project.id(), source.subjectId(), sourceArtifact.artifact().version(), null,
                    text("Source changed"));
            CanvasItem restored = restore(copied, guard);
            var restoredText = artifacts.get(owner, project.id(), restored.subjectId());
            assertThat(restoredText.resourceDefaultVersion().content().path("text").asText()).isEqualTo(text);
            assertThat(restoredText.artifact().version()).isGreaterThan(guard.at("/text/artifact/version").asLong());
            assertThat(restoredText.resourceDefaultVersion().id().toString())
                    .isEqualTo(guard.at("/text/currentVersionId").asText());
            assertThat(artifacts.listVersions(owner, project.id(), restored.subjectId())).hasSize(1);
            assertThatThrownBy(() -> artifacts.revise(owner, project.id(), restored.subjectId(),
                    guard.at("/text/artifact/version").asLong(), null, text("Stale autosave")))
                    .isInstanceOf(ApiProblemException.class);
            JsonNode nextGuard = copies.capture(owner, project.id(), restored);
            delete(restored);
            var independent = artifacts.get(owner, project.id(), restored.subjectId());
            artifacts.revise(owner, project.id(), independent.artifact().id(), independent.artifact().version(), null,
                    text("Later independent work"));
            assertThatThrownBy(() -> restore(copied, nextGuard)).isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void guardIncludesTerminalTaskRejectedApprovalAndHistoricalUnselectedStoryboardUsage() {
        Copied copy = clone(emptyMedia());
        var target = copy.target();
        var pristine = copies.capture(owner, project.id(), target);
        assertThat(pristine.path("hasTaskOwnership").asBoolean()).isFalse();
        assertThat(pristine.path("hasApprovalOwnership").asBoolean()).isFalse();
        assertThat(pristine.path("hasStoryboardReference").asBoolean()).isFalse();
        var now = Instant.now();
        var input = mapper.createObjectNode();
        input.putObject("secondary").put("sourceCanvasItemId", target.id().toString());
        UUID historicTaskId = UUID.randomUUID();
        // The creation repository intentionally inserts only fresh tasks with no completion
        // timestamp. Advance this synthetic ledger row afterwards to model retained terminal work.
        tasks.create(new Task(historicTaskId, project.id(), null, "copy-history-task", Task.Kind.TEXT_GENERATION,
                Task.Status.READY, false, input, "0".repeat(64), null, null, 1, now, null, null, 0, 0, null, now, now,
                null));
        assertThat(jdbc.sql(
                "update task set status='CANCELED', cancel_requested=true, completed_at=current_timestamp where id=:id")
                .param("id", historicTaskId).update()).isEqualTo(1);
        assertThat(copies.capture(owner, project.id(), target).path("hasTaskOwnership").asBoolean()).isTrue();
        var agent = configureDirector(agents, owner, project.id(), "Director", "Synthetic copy test");
        var run = runs.create(owner, project.id(), agent.id(), "Review copied shot", "copy-approval-run").run();
        var targets = mapper.createObjectNode().put("schemaVersion", 1);
        targets.putArray("outputs").addObject().put("canvasItemId", target.id().toString());
        var request = mapper.createObjectNode().put("schemaVersion", 1);
        request.putArray("outputs").addObject().put("kind", "IMAGE");
        assertThat(approvals.insert(new AgentMediaApproval(UUID.randomUUID(), owner, project.id(), run.id(), 0,
                "synthetic-copy-tool", UUID.randomUUID(), request, targets, List.of(), null,
                AgentMediaApproval.Status.REJECTED, 0, now, now.plusSeconds(60), null, null, null))).isTrue();
        assertThat(copies.capture(owner, project.id(), target).path("hasApprovalOwnership").asBoolean()).isTrue();
        storyboards.save(owner, project.id(), new StoryboardService.Save(0, List.of(new StoryboardService.ShotInput(
                UUID.randomUUID(),
                "S1", "Scene", "Copied shot", "", "", BigDecimal.ONE, null, List.of(),
                List.of(new StoryboardService.ProductionInput(Storyboard.Role.KEYFRAME, target.id(), null, false)))),
                null));
        storyboards.save(owner, project.id(), new StoryboardService.Save(1, List.of(), null));
        var used = copies.capture(owner, project.id(), target);
        assertThat(used.path("hasTaskOwnership").asBoolean()).isTrue();
        assertThat(used.path("hasApprovalOwnership").asBoolean()).isTrue();
        assertThat(used.path("hasStoryboardReference").asBoolean()).isTrue();
        assertThat(used).isNotEqualTo(pristine);
        assertThat(used.toString()).doesNotContain("0".repeat(64), "synthetic-copy-tool", "Synthetic copy test");
        assertThatThrownBy(() -> delete(target)).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), target.id())).isPresent();
    }

    @Test
    void actualCopyUndoPreservesLaterDraftChoiceAndConnectionWork() {
        Copied draftCopy = clone(emptyMedia());
        MediaDraft original = drafts.get(owner, project.id(), draftCopy.target().id());
        drafts.save(owner, project.id(), draftCopy.target().id(), original.version(), "Later work",
                mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null);
        assertThatThrownBy(() -> delete(draftCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(drafts.get(owner, project.id(), draftCopy.target().id()).prompt()).isEqualTo("Later work");

        UUID version = image("Selected result");
        UUID artifactId = artifacts.requireMediaVersionForTask(owner, project.id(), version, Artifact.Kind.IMAGE)
                .artifactId();
        CanvasItem selected = place(artifactId);
        Copied selectionCopy = clone(selected);
        // Explicit selection is a new content decision even when choosing the same exact result.
        canvas.selectMediaVersion(owner, project.id(), selectionCopy.target().id(), version,
                selectionCopy.target().version());
        assertThatThrownBy(() -> delete(selectionCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), selectionCopy.target().id())).isPresent();

        Copied connectionCopy = clone(emptyMedia());
        connections.connect(owner, project.id(), selected.id(), connectionCopy.target().id(), version,
                CanvasConnection.RelationType.MEDIA_INPUT,
                drafts.get(owner, project.id(), connectionCopy.target().id()).version());
        assertThatThrownBy(() -> delete(connectionCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(connections.list(owner, project.id()))
                .anySatisfy(edge -> assertThat(edge.targetCanvasItemId()).isEqualTo(connectionCopy.target().id()));

        // Source-side connect/disconnect returns the visible list and draft to their original
        // state, but it still represents later work and cannot make an old copy receipt valid.
        Copied sourceCopy = clone(selected);
        JsonNode sourceBefore = copies.capture(owner, project.id(), sourceCopy.target());
        MediaDraft sourceDraft = drafts.get(owner, project.id(), sourceCopy.target().id());
        CanvasItem downstream = emptyMedia();
        var connected = connections.connect(owner, project.id(), sourceCopy.target().id(), downstream.id(), version,
                CanvasConnection.RelationType.MEDIA_INPUT, drafts.get(owner, project.id(), downstream.id()).version());
        connections.disconnect(owner, project.id(), connected.connection().id(), connected.connection().version(),
                connected.draft().version());
        JsonNode sourceAfter = copies.capture(owner, project.id(), sourceCopy.target());
        assertThat(sourceAfter.path("connections").isEmpty()).isTrue();
        assertThat(sourceAfter.path("topologyEpoch").asLong())
                .isGreaterThan(sourceBefore.path("topologyEpoch").asLong());
        assertThat(drafts.get(owner, project.id(), sourceCopy.target().id())).isEqualTo(sourceDraft);
        assertThatThrownBy(() -> delete(sourceCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), sourceCopy.target().id())).isPresent();
    }

    @Test
    void subgraphCopyRemapsPublicTextAndRestoresFrozenTopologyAcrossUndoRedo() throws Exception {
        var writing = artifacts.create(owner, project.id(), Artifact.Kind.TEXT, "Shared", text("Shared style"));
        CanvasItem source = place(writing.artifact().id()), target = emptyMedia(), outside = emptyMedia();
        var internal = connections
                .connect(owner, project.id(), source.id(), target.id(), writing.resourceDefaultVersion().id(),
                        CanvasConnection.RelationType.PROMPT_INPUT, null)
                .connection();
        connections.connect(owner, project.id(), source.id(), outside.id(), writing.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.PROMPT_INPUT, null);
        UUID sourceCopy = UUID.randomUUID(), targetCopy = UUID.randomUUID(), edgeCopy = UUID.randomUUID();
        Edit command = new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(),
                List.of(copyRequest(source, sourceCopy), copyRequest(target, targetCopy)),
                List.of(new ConnectionCopy(ConnectionKind.ARTIFACT, internal.id(), edgeCopy, internal.version())));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                new AdminPrincipal(owner, "copy-support-admin"), null, List.of()));
        var response = mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(
                        "/api/v1/projects/" + project.id() + "/canvas/edits")
                .with(auth)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content(mapper.writeValueAsString(command)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(response.path("connections").size()).isEqualTo(3);
        assertThat(response.path("characterConnections").isArray()).isTrue();
        var result = editor.edit(owner, project.id(), command);
        assertThat(connections.list(owner, project.id())).filteredOn(e -> e.sourceCanvasItemId().equals(sourceCopy))
                .singleElement().satisfies(e -> {
                    assertThat(e.id()).isEqualTo(edgeCopy);
                    assertThat(e.targetCanvasItemId()).isEqualTo(targetCopy);
                    var copiedText = artifacts.get(owner, project.id(),
                            items.find(owner, project.id(), sourceCopy).orElseThrow().subjectId());
                    assertThat(e.sourceArtifactVersionId()).isEqualTo(copiedText.resourceDefaultVersion().id())
                            .isNotEqualTo(writing.resourceDefaultVersion().id());
                });
        assertThat(editor.edit(owner, project.id(), command).receipt()).isEqualTo(result.receipt());
        artifacts.revise(owner, project.id(), source.subjectId(), writing.artifact().version(), null,
                text("Changed original"));
        var undone = editor.replay(owner, project.id(), result.receipt().id(),
                new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        assertThat(items.find(owner, project.id(), sourceCopy)).isEmpty();
        assertThat(connections.list(owner, project.id())).noneMatch(e -> e.id().equals(edgeCopy));
        editor.replay(owner, project.id(), result.receipt().id(),
                new Replay(UUID.randomUUID(), undone.receipt().revision(), Direction.REDO));
        assertThat(connections.list(owner, project.id())).anyMatch(e -> e.id().equals(edgeCopy));
        var copiedText = artifacts.get(owner, project.id(),
                items.find(owner, project.id(), sourceCopy).orElseThrow().subjectId());
        assertThat(copiedText.resourceDefaultVersion().content().path("text").asText()).isEqualTo("Shared style");
        assertThat(editor.get(owner, project.id()).connections()).hasSize(3);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/projects/" + project.id() + "/canvas/connections/" + edgeCopy + "/disconnect")
                .with(auth)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        editor.replay(owner, project.id(), result.receipt().id(), new Replay(UUID.randomUUID(), 2, Direction.UNDO));
    }

    @Test
    void subgraphCopyPersistsCharacterConfigurationBeforeLogicalEdgesAndFencesRedo() {
        UUID character = UUID.randomUUID(), look = UUID.randomUUID(), node = UUID.randomUUID();
        UUID reference = image("Character reference");
        characters.save(owner, project.id(), character, new SaveCharacter(UUID.randomUUID(), 0,
                new CharacterContent("Aster", "Fixed identity",
                        List.of(new CharacterLook(look, "Coat", "Blue coat", "", "",
                                List.of(new CharacterView(reference, "Front", "")))),
                        null)));
        var selection = new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Selection(character, 1, look,
                List.of(reference), true, false);
        var source = characterNodes.create(owner, project.id(), node,
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.CreateNode(selection, BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(300), BigDecimal.valueOf(300), 0, null, false))
                .item();
        CanvasItem target = emptyMedia(), outside = emptyMedia();
        var edge = characterNodes.connect(owner, project.id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Connect(
                        UUID.randomUUID(), node, target.id(), 0, 0, 0));
        characterNodes.connect(owner, project.id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Connect(
                        UUID.randomUUID(), node, outside.id(), 0, 0, 0));
        var preview = characterNodes.preview(owner, project.id(), target.id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Preview(0, 0, ClearMode.REMOVE_OWNED));
        var applied = characterNodes.apply(owner, project.id(), target.id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Apply(UUID.randomUUID(), 0, 0,
                        ClearMode.REMOVE_OWNED, preview.previewHash()));
        UUID copiedNode = UUID.randomUUID(), copiedTarget = UUID.randomUUID(), copiedEdge = UUID.randomUUID();
        var copied = editor.edit(owner, project.id(),
                new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(),
                        List.of(copyRequest(source, copiedNode), copyRequest(target, copiedTarget)),
                        List.of(new ConnectionCopy(ConnectionKind.CHARACTER, edge.id(), copiedEdge, 0))));
        for (int revision = 0; revision < 4; revision += 2) {
            long expectedEdgeVersion = revision / 2;
            var restored = items.find(owner, project.id(), copiedNode).orElseThrow();
            assertThat(characterNodes.view(owner, restored).selection()).isEqualTo(selection);
            assertThat(characterNodes.connections(owner, project.id()))
                    .filteredOn(e -> e.sourceCanvasItemId().equals(copiedNode))
                    .singleElement().satisfies(e -> {
                        assertThat(e.id()).isEqualTo(copiedEdge);
                        assertThat(e.targetCanvasItemId()).isEqualTo(copiedTarget);
                        assertThat(e.version()).isEqualTo(expectedEdgeVersion);
                    });
            assertThat(drafts.get(owner, project.id(), copiedTarget).prompt()).isEqualTo(applied.draft().prompt());
            assertThat(characters.list(owner, project.id()).bindings())
                    .filteredOn(b -> b.canvasItemId().equals(copiedTarget))
                    .singleElement().satisfies(b -> assertThat(b.needsReview()).isTrue());
            editor.replay(owner, project.id(), copied.receipt().id(),
                    new Replay(UUID.randomUUID(), revision, Direction.UNDO));
            assertThat(characterNodes.connections(owner, project.id())).noneMatch(e -> e.id().equals(copiedEdge));
            editor.replay(owner, project.id(), copied.receipt().id(),
                    new Replay(UUID.randomUUID(), revision + 1, Direction.REDO));
        }
        assertThatThrownBy(() -> characterNodes.disconnect(owner, project.id(), copiedEdge,
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Disconnect(0)))
                .isInstanceOf(ApiProblemException.class);
        // Logical connect/disconnect ABA on an otherwise untouched media copy must block undo.
        var extra = characterNodes.connect(owner, project.id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Connect(
                        UUID.randomUUID(), node, copiedTarget, 0, 0,
                        items.find(owner, project.id(), copiedTarget).orElseThrow().version()));
        characterNodes.disconnect(owner, project.id(), extra.id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Disconnect(0));
        assertThatThrownBy(() -> editor.replay(owner, project.id(), copied.receipt().id(),
                new Replay(UUID.randomUUID(), 4, Direction.UNDO)))
                .isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), copiedTarget)).isPresent();
        // Copying the target alone keeps the applied role as an independent fixed selection.
        var standalone = clone(target);
        var standaloneBinding = characters.list(owner, project.id()).bindings().stream()
                .filter(b -> b.canvasItemId().equals(standalone.target().id())).findFirst().orElseThrow();
        var standalonePreview = characterNodes.preview(owner, project.id(), standalone.target().id(),
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Preview(standaloneBinding.version(),
                        drafts.get(owner, project.id(), standalone.target().id()).version(), ClearMode.REMOVE_OWNED));
        assertThat(standalonePreview.characterPreview().snapshots()).extracting(CharacterSnapshot::characterId)
                .containsExactly(character);
        characterNodes.disconnect(owner, project.id(), copiedEdge,
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Disconnect(2));
        var binding = characters.list(owner, project.id()).bindings().stream()
                .filter(b -> b.canvasItemId().equals(copiedTarget)).findFirst().orElseThrow();
        var draft = drafts.get(owner, project.id(), copiedTarget);
        var clear = characterNodes.preview(owner, project.id(), copiedTarget,
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Preview(binding.version(), draft.version(),
                        ClearMode.REMOVE_OWNED));
        assertThat(clear.characterPreview().snapshots()).isEmpty();
        var cleared = characterNodes.apply(owner, project.id(), copiedTarget,
                new dev.agenvas.canvas.application.CanvasCharacterNodeModels.Apply(UUID.randomUUID(), binding.version(),
                        draft.version(), ClearMode.REMOVE_OWNED, clear.previewHash()));
        assertThat(cleared.draft().mediaInputs()).isEmpty();
        assertThat(cleared.draft().prompt()).doesNotContain("Blue coat");
        assertThat(drafts.get(owner, project.id(), target.id()).mediaInputs()).hasSize(1);
    }

    @Test
    void mediaSubgraphKeepsExactInputRolesMentionsAndReferenceCounting() {
        UUID exact = image("Fixed reference");
        UUID artifact = artifacts.requireMediaVersionForTask(owner, project.id(), exact, Artifact.Kind.IMAGE)
                .artifactId();
        for (int mode = 0; mode < 3; mode++) {
            CanvasItem source = place(artifact), target = emptyMedia();
            if (mode == 1)
                drafts.save(owner, project.id(), target.id(), 0, "", mapper.createObjectNode(), null, null, null,
                        List.of(new MediaDraftService.SaveMediaInput(exact, MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                        List.of(), null);
            var internal = connections.connect(owner, project.id(), source.id(), target.id(), exact,
                    CanvasConnection.RelationType.MEDIA_INPUT,
                    drafts.get(owner, project.id(), target.id()).version()).connection();
            if (mode == 2)
                connections.connect(owner, project.id(), place(artifact).id(), target.id(), exact,
                        CanvasConnection.RelationType.MEDIA_INPUT,
                        drafts.get(owner, project.id(), target.id()).version());
            var current = drafts.get(owner, project.id(), target.id());
            var saved = drafts.save(owner, project.id(), target.id(), current.version(), "Use \uFFFC",
                    mapper.createObjectNode(), null, null, null,
                    List.of(new MediaDraftService.SaveMediaInput(exact, MediaDraft.InputRole.REFERENCE, "#7C3AED")),
                    List.of(new MediaDraft.PromptMention(exact, MediaDraft.InputRole.REFERENCE)), null);
            UUID copiedSource = UUID.randomUUID(), copiedTarget = UUID.randomUUID(), copiedEdge = UUID.randomUUID();
            var result = editor.edit(owner, project.id(),
                    new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(),
                            List.of(copyRequest(source, copiedSource), copyRequest(target, copiedTarget)),
                            List.of(new ConnectionCopy(ConnectionKind.ARTIFACT, internal.id(), copiedEdge, 0))));
            var first = drafts.get(owner, project.id(), copiedTarget);
            assertThat(first.prompt()).isEqualTo(saved.prompt());
            assertThat(first.mentions()).isEqualTo(saved.mentions());
            assertThat(first.mediaInputs()).singleElement().satisfies(input -> {
                assertThat(input.versionId()).isEqualTo(exact);
                assertThat(input.role()).isEqualTo(MediaDraft.InputRole.REFERENCE);
                assertThat(input.color()).isEqualTo("#7C3AED");
                assertThat(input.sources()).filteredOn(i -> i.type() == MediaDraft.SourceType.CONNECTION)
                        .singleElement().satisfies(i -> assertThat(i.connectionId()).isEqualTo(copiedEdge));
                assertThat(input.sources()).doesNotContainAnyElementsOf(saved.mediaInputs().getFirst().sources());
            });
            editor.replay(owner, project.id(), result.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
            editor.replay(owner, project.id(), result.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
            var restored = drafts.get(owner, project.id(), copiedTarget);
            assertThat(restored.mediaInputs()).isEqualTo(first.mediaInputs());
            assertThat(restored.version()).isGreaterThan(first.version());
            var disconnected = connections.disconnect(owner, project.id(), copiedEdge, 1, restored.version()).draft();
            // save() above preserves connection-only ownership for mode 0; manual/external sources survive.
            if (mode == 0)
                assertThat(disconnected.mediaInputs()).isEmpty();
            else
                assertThat(disconnected.mediaInputs()).singleElement().satisfies(input -> assertThat(input.sources())
                        .allSatisfy(i -> assertThat(i.type()).isEqualTo(MediaDraft.SourceType.MANUAL)));
        }
    }

    @Test
    void staleOrExternalTopologyRejectsTheEntireCopyWithoutArtifactsGroupsOrEvents() {
        var textArtifact = artifacts.create(owner, project.id(), Artifact.Kind.TEXT, "Shared", text("Saved"));
        var source = place(textArtifact.artifact().id());
        var target = emptyMedia();
        var edge = connections
                .connect(owner, project.id(), source.id(), target.id(), textArtifact.resourceDefaultVersion().id(),
                        CanvasConnection.RelationType.PROMPT_INPUT, null)
                .connection();
        UUID targetId = UUID.randomUUID(), groupId = UUID.randomUUID();
        var request = new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, groupId, 0, "Copy", false)),
                List.of(copyRequest(source, targetId), copyRequest(target, UUID.randomUUID())),
                List.of(new ConnectionCopy(ConnectionKind.ARTIFACT, edge.id(), UUID.randomUUID(), 0)));
        connections.disconnect(owner, project.id(), edge.id(), edge.version(), null);
        connections.connect(owner, project.id(), source.id(), target.id(), textArtifact.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.PROMPT_INPUT, null);
        long before = events.replayWindow(owner, project.id()).latestSequence();
        long count = jdbc.sql("select count(*) from artifact where project_id=:p").param("p", project.id())
                .query(Long.class).single();
        assertThatThrownBy(() -> editor.edit(owner, project.id(), request)).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), targetId)).isEmpty();
        assertThat(editor.groups(owner, project.id())).isEmpty();
        assertThat(events.replayWindow(owner, project.id()).latestSequence()).isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from artifact where project_id=:p").param("p", project.id())
                .query(Long.class).single()).isEqualTo(count);
    }

    @Test
    void foreignConnectionIdentityCollisionRollsBackTheEntireGraphAndDoesNotLeakTheOtherProject() {
        var other = projects.create(owner, "Other isolated graph", Project.AspectRatio.SQUARE_1_1);
        var otherText = artifacts.create(owner, other.id(), Artifact.Kind.TEXT, "Other text",
                text("Private other text"));
        var otherMedia = artifacts.create(owner, other.id(), Artifact.Kind.IMAGE, "Other media", null);
        UUID otherSource = UUID.randomUUID(), otherTarget = UUID.randomUUID();
        canvas.apply(owner, other.id(), List.of(
                new CanvasService.PlaceArtifact(otherSource, otherText.artifact().id(), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.valueOf(320), BigDecimal.valueOf(240), 0, null, false),
                new CanvasService.PlaceArtifact(otherTarget, otherMedia.artifact().id(), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.valueOf(320), BigDecimal.valueOf(240), 0, null, false)));
        var foreign = connections
                .connect(owner, other.id(), otherSource, otherTarget, otherText.resourceDefaultVersion().id(),
                        CanvasConnection.RelationType.PROMPT_INPUT, null)
                .connection();
        // Compare persisted state on both sides, including PostgreSQL's timestamp precision.
        var foreignBefore = connections.list(owner, other.id());
        assertThat(foreignBefore).singleElement()
                .satisfies(connection -> assertThat(connection.id()).isEqualTo(foreign.id()));
        var localText = artifacts.create(owner, project.id(), Artifact.Kind.TEXT, "Local text", text("Local"));
        var source = place(localText.artifact().id());
        var target = emptyMedia();
        var edge = connections
                .connect(owner, project.id(), source.id(), target.id(), localText.resourceDefaultVersion().id(),
                        CanvasConnection.RelationType.PROMPT_INPUT, null)
                .connection();
        UUID copiedSource = UUID.randomUUID(), copiedTarget = UUID.randomUUID();
        long before = events.replayWindow(owner, project.id()).latestSequence();
        long count = jdbc.sql("select count(*) from artifact where project_id=:p").param("p", project.id())
                .query(Long.class).single();
        var command = new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(),
                List.of(copyRequest(source, copiedSource), copyRequest(target, copiedTarget)),
                List.of(new ConnectionCopy(ConnectionKind.ARTIFACT, edge.id(), foreign.id(), 0)));
        assertThatThrownBy(() -> editor.edit(owner, project.id(), command)).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), copiedSource)).isEmpty();
        assertThat(items.find(owner, project.id(), copiedTarget)).isEmpty();
        assertThat(events.replayWindow(owner, project.id()).latestSequence()).isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from artifact where project_id=:p").param("p", project.id())
                .query(Long.class).single()).isEqualTo(count);
        assertThat(connections.list(owner, other.id())).containsExactlyElementsOf(foreignBefore);
    }

    private Copy copyRequest(CanvasItem item, UUID target) {
        return new Copy(item.id(), target, item.version(), item.subjectType() == CanvasItem.SubjectType.CHARACTER
                ? Long.valueOf(copies.characterSelectionVersion(owner, project.id(), item))
                : artifacts.get(owner, project.id(), item.subjectId()).artifact().kind() == Artifact.Kind.TEXT
                        ? null
                        : Long.valueOf(drafts.get(owner, project.id(), item.id()).version()),
                item.subjectType() == CanvasItem.SubjectType.ARTIFACT
                        && artifacts.get(owner, project.id(), item.subjectId()).artifact().kind() == Artifact.Kind.TEXT
                                ? artifacts.get(owner, project.id(), item.subjectId()).resourceDefaultVersion().id()
                                : null,
                BigDecimal.valueOf(32), BigDecimal.valueOf(32), item.width(), item.height(), 1, null);
    }

    private Copied clone(CanvasItem source) {
        UUID targetId = UUID.randomUUID();
        var artifact = artifacts.get(owner, project.id(), source.subjectId());
        Long draftVersion = artifact.artifact().kind() == Artifact.Kind.TEXT
                ? null
                : drafts.get(owner, project.id(), source.id()).version();
        UUID contentVersion = artifact.resourceDefaultVersion() == null ? null : artifact.resourceDefaultVersion().id();
        var response = editor.edit(owner, project.id(),
                new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(),
                        List.of(new Copy(source.id(), targetId, source.version(), draftVersion, contentVersion,
                                new BigDecimal("1000"), BigDecimal.ZERO, source.width(), source.height(), 1, null))));
        receipts.put(targetId, response.receipt());
        var copy = edits.receipt(project.id(), response.receipt().id()).orElseThrow().history().copies().getFirst();
        return new Copied(items.find(owner, project.id(), targetId).orElseThrow(), copy.frozen());
    }

    private CanvasItem restore(Copied copy, JsonNode guard) {
        Receipt receipt = receipts.get(copy.target().id());
        var response = editor.replay(owner, project.id(), receipt.id(),
                new Replay(UUID.randomUUID(), receipt.revision(), Direction.REDO));
        receipts.put(copy.target().id(), response.receipt());
        return items.find(owner, project.id(), copy.target().id()).orElseThrow();
    }

    private void delete(CanvasItem target) {
        Receipt receipt = receipts.get(target.id());
        var response = editor.replay(owner, project.id(), receipt.id(),
                new Replay(UUID.randomUUID(), receipt.revision(), Direction.UNDO));
        receipts.put(target.id(), response.receipt());
    }
    private CanvasItem emptyMedia() {
        return place(artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, "Shot", null).artifact().id());
    }
    private CanvasItem place(UUID artifact) {
        UUID item = UUID.randomUUID();
        canvas.apply(owner, project.id(),
                List.of(new CanvasService.PlaceArtifact(item, artifact, BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("320"), new BigDecimal("240"), 0, null, false)));
        return items.find(owner, project.id(), item).orElseThrow();
    }
    private CanvasItem card(UUID id, UUID artifact, UUID selected, long version) {
        Instant now = Instant.now();
        return new CanvasItem(id, project.id(), CanvasItem.SubjectType.ARTIFACT, artifact, selected, "Copied shot",
                BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false, version, now, now);
    }
    private UUID image(String title) {
        var body = mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId",
                ImageAssetFixture.archive(assets, owner, project.id()).toString());
        return artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, title, body).resourceDefaultVersion().id();
    }
    private JsonNode text(String value) {
        return mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", value);
    }
    private record Copied(CanvasItem target, JsonNode frozen) {
    }
}

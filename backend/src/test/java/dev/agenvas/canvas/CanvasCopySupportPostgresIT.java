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
    @TempDir static Path storage;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> storage.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasItemRepository items;
    @Autowired MediaDraftService drafts;
    @Autowired CharacterService characters;
    @Autowired CanvasCopySupport copies;
    @Autowired CanvasEditService editor;
    @Autowired CanvasEditRepository edits;
    @Autowired CanvasConnectionService connections;
    private final Map<UUID, Receipt> receipts = new HashMap<>();
    @Autowired ProjectEventService events;
    @Autowired TaskRepository tasks;
    @Autowired AgentMediaApprovalRepository approvals;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired StoryboardService storyboards;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    private UUID owner;
    private Project project;

    @BeforeEach void setup() {
        owner = jdbc.sql("select id from app_user where login_name='copy-support-admin'").query(UUID.class).optional()
                .orElseGet(() -> identities.setup("copy-support-admin", "synthetic-copy-password-123").userId());
        project = projects.create(owner, "Copy support", Project.AspectRatio.SQUARE_1_1);
    }

    @Test
    void mediaRedoKeepsFixedCharacterContentAndPreciseRemovalOwnershipAndFencesOldAutosave() {
        UUID manual = image("Manual reference"), owned = image("Role-owned reference");
        CanvasItem source = emptyMedia();
        var initial = drafts.save(owner, project.id(), source.id(), 0, "Saved shot", mapper.createObjectNode(), null,
                null, null, List.of(new MediaDraftService.SaveMediaInput(manual, MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of(), null);
        UUID characterId = UUID.randomUUID(), lookId = UUID.randomUUID();
        CharacterContent content = new CharacterContent("Aster", "Fixed character", List.of(new CharacterLook(lookId,
                "Travel coat", "Blue coat", "Calm", "", List.of(new CharacterView(owned, "Front", ""),
                        new CharacterView(manual, "Already manually present", "")))), null);
        var profile = characters.save(owner, project.id(), characterId, new SaveCharacter(UUID.randomUUID(), 0, content));
        var selections = List.of(new CharacterBindingSelection(characterId, profile.version(), lookId, true, false));
        var preview = characters.preview(owner, project.id(), source.id(), new PreviewCharacterBindings(0, initial.version(), selections, ClearMode.REMOVE_OWNED));
        var applied = characters.apply(owner, project.id(), source.id(), new ApplyCharacterBindings(UUID.randomUUID(), 0,
                initial.version(), selections, ClearMode.REMOVE_OWNED, preview.previewHash()));
        long before = events.replayWindow(owner, project.id()).latestSequence();
        Copied copy = clone(source);
        assertThat(events.replayWindow(owner, project.id()).latestSequence()).isEqualTo(before + 1);
        MediaDraft first = drafts.get(owner, project.id(), copy.target().id());
        assertThat(first.prompt()).contains("Blue coat");
        assertThat(first.mediaInputs()).allSatisfy(input -> assertThat(input.sources()).singleElement().satisfies(sourceId -> {
            assertThat(sourceId.type()).isEqualTo(MediaDraft.SourceType.MANUAL);
            assertThat(sourceId.connectionId()).isNull();
        }));
        assertThat(first.mediaInputs()).flatExtracting(MediaDraft.MediaInput::sources)
                .doesNotContainAnyElementsOf(applied.draft().mediaInputs().stream().flatMap(input -> input.sources().stream()).toList());
        JsonNode guard = copies.capture(owner, project.id(), copy.target());
        delete(copy.target());
        drafts.save(owner, project.id(), source.id(), applied.draft().version(), "Source changed later", mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null);
        characters.save(owner, project.id(), characterId, new SaveCharacter(UUID.randomUUID(), profile.version(),
                new CharacterContent("Revised Aster", "Changed later", content.looks(), null)));
        CanvasItem restored = restore(copy, guard);
        MediaDraft second = drafts.get(owner, project.id(), restored.id());
        assertThat(second.prompt()).isEqualTo(first.prompt());
        assertThat(second.mediaInputs()).isEqualTo(first.mediaInputs());
        assertThat(second.version()).isGreaterThan(first.version());
        assertThatThrownBy(() -> drafts.save(owner, project.id(), restored.id(), first.version(), "Old autosave", mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null)).isInstanceOf(ApiProblemException.class);
        var binding = characters.list(owner, project.id()).bindings().stream().filter(value -> value.canvasItemId().equals(restored.id())).findFirst().orElseThrow();
        assertThat(binding.snapshots().getFirst().name()).isEqualTo("Aster");
        assertThat(binding.version()).isGreaterThan(guard.at("/characters/binding/version").asLong());
        var clearPreview = characters.preview(owner, project.id(), restored.id(), new PreviewCharacterBindings(binding.version(), second.version(), List.of(), ClearMode.REMOVE_OWNED));
        var cleared = characters.apply(owner, project.id(), restored.id(), new ApplyCharacterBindings(UUID.randomUUID(), binding.version(),
                second.version(), List.of(), ClearMode.REMOVE_OWNED, clearPreview.previewHash()));
        assertThat(cleared.draft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(manual);
        assertThat(cleared.draft().prompt()).isEqualTo("Saved shot");
    }

    @Test
    void textCopiesHaveIndependentBodiesIncludingEmptyTextAndRedoRejectsEditingDeletedCopyArtifact() {
        for (String text : List.of("Original writing", "")) {
            var sourceArtifact = artifacts.create(owner, project.id(), Artifact.Kind.TEXT, "Text", text(text));
            CanvasItem source = place(sourceArtifact.artifact().id());
            Copied copied = clone(source);
            assertThat(copied.target().subjectId()).isNotEqualTo(source.subjectId());
            assertThat(artifacts.get(owner, project.id(), copied.target().subjectId()).resourceDefaultVersion().content().path("text").asText()).isEqualTo(text);
            JsonNode guard = copies.capture(owner, project.id(), copied.target());
            delete(copied.target());
            artifacts.revise(owner, project.id(), source.subjectId(), sourceArtifact.artifact().version(), null, text("Source changed"));
            CanvasItem restored = restore(copied, guard);
            var restoredText = artifacts.get(owner, project.id(), restored.subjectId());
            assertThat(restoredText.resourceDefaultVersion().content().path("text").asText()).isEqualTo(text);
            assertThat(restoredText.artifact().version()).isGreaterThan(guard.at("/text/artifact/version").asLong());
            assertThat(restoredText.resourceDefaultVersion().id().toString()).isEqualTo(guard.at("/text/currentVersionId").asText());
            assertThat(artifacts.listVersions(owner, project.id(), restored.subjectId())).hasSize(1);
            assertThatThrownBy(() -> artifacts.revise(owner, project.id(), restored.subjectId(),
                    guard.at("/text/artifact/version").asLong(), null, text("Stale autosave")))
                    .isInstanceOf(ApiProblemException.class);
            JsonNode nextGuard = copies.capture(owner, project.id(), restored);
            delete(restored);
            var independent = artifacts.get(owner, project.id(), restored.subjectId());
            artifacts.revise(owner, project.id(), independent.artifact().id(), independent.artifact().version(), null, text("Later independent work"));
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
                Task.Status.READY, false, input, "0".repeat(64), null, null, 1, now, null, null, 0, 0, null, now, now, null));
        assertThat(jdbc.sql("update task set status='CANCELED', cancel_requested=true, completed_at=current_timestamp where id=:id")
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
        storyboards.save(owner, project.id(), new StoryboardService.Save(0, List.of(new StoryboardService.ShotInput(UUID.randomUUID(),
                "S1", "Scene", "Copied shot", "", "", BigDecimal.ONE, null, List.of(),
                List.of(new StoryboardService.ProductionInput(Storyboard.Role.KEYFRAME, target.id(), null, false)))), null));
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
        drafts.save(owner, project.id(), draftCopy.target().id(), original.version(), "Later work", mapper.createObjectNode(),
                null, null, null, List.of(), List.of(), null);
        assertThatThrownBy(() -> delete(draftCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(drafts.get(owner, project.id(), draftCopy.target().id()).prompt()).isEqualTo("Later work");

        UUID version = image("Selected result");
        UUID artifactId = artifacts.requireMediaVersionForTask(owner, project.id(), version, Artifact.Kind.IMAGE).artifactId();
        CanvasItem selected = place(artifactId);
        Copied selectionCopy = clone(selected);
        // Explicit selection is a new content decision even when choosing the same exact result.
        canvas.selectMediaVersion(owner, project.id(), selectionCopy.target().id(), version, selectionCopy.target().version());
        assertThatThrownBy(() -> delete(selectionCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), selectionCopy.target().id())).isPresent();

        Copied connectionCopy = clone(emptyMedia());
        connections.connect(owner, project.id(), selected.id(), connectionCopy.target().id(), version,
                CanvasConnection.RelationType.MEDIA_INPUT, drafts.get(owner, project.id(), connectionCopy.target().id()).version());
        assertThatThrownBy(() -> delete(connectionCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(connections.list(owner, project.id())).anySatisfy(edge -> assertThat(edge.targetCanvasItemId()).isEqualTo(connectionCopy.target().id()));

        // Source-side connect/disconnect returns the visible list and draft to their original
        // state, but it still represents later work and cannot make an old copy receipt valid.
        Copied sourceCopy = clone(selected);
        JsonNode sourceBefore = copies.capture(owner, project.id(), sourceCopy.target());
        MediaDraft sourceDraft = drafts.get(owner, project.id(), sourceCopy.target().id());
        CanvasItem downstream = emptyMedia();
        var connected = connections.connect(owner, project.id(), sourceCopy.target().id(), downstream.id(), version,
                CanvasConnection.RelationType.MEDIA_INPUT, drafts.get(owner, project.id(), downstream.id()).version());
        connections.disconnect(owner, project.id(), connected.connection().id(), connected.draft().version());
        JsonNode sourceAfter = copies.capture(owner, project.id(), sourceCopy.target());
        assertThat(sourceAfter.path("connections").isEmpty()).isTrue();
        assertThat(sourceAfter.path("topologyEpoch").asLong()).isGreaterThan(sourceBefore.path("topologyEpoch").asLong());
        assertThat(drafts.get(owner, project.id(), sourceCopy.target().id())).isEqualTo(sourceDraft);
        assertThatThrownBy(() -> delete(sourceCopy.target())).isInstanceOf(ApiProblemException.class);
        assertThat(items.find(owner, project.id(), sourceCopy.target().id())).isPresent();
    }

    private Copied clone(CanvasItem source) {
        UUID targetId = UUID.randomUUID();
        var artifact = artifacts.get(owner, project.id(), source.subjectId());
        Long draftVersion = artifact.artifact().kind() == Artifact.Kind.TEXT ? null : drafts.get(owner, project.id(), source.id()).version();
        UUID contentVersion = artifact.resourceDefaultVersion() == null ? null : artifact.resourceDefaultVersion().id();
        var response = editor.edit(owner, project.id(), new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(),
                List.of(new Copy(source.id(), targetId, source.version(), draftVersion, contentVersion,
                        new BigDecimal("1000"), BigDecimal.ZERO, source.width(), source.height(), 1, null))));
        receipts.put(targetId, response.receipt());
        var copy = edits.receipt(project.id(), response.receipt().id()).orElseThrow().history().copies().getFirst();
        return new Copied(items.find(owner, project.id(), targetId).orElseThrow(), copy.frozen());
    }

    private CanvasItem restore(Copied copy, JsonNode guard) {
        Receipt receipt = receipts.get(copy.target().id());
        var response = editor.replay(owner, project.id(), receipt.id(), new Replay(UUID.randomUUID(), receipt.revision(), Direction.REDO));
        receipts.put(copy.target().id(), response.receipt());
        return items.find(owner, project.id(), copy.target().id()).orElseThrow();
    }

    private void delete(CanvasItem target) {
        Receipt receipt = receipts.get(target.id());
        var response = editor.replay(owner, project.id(), receipt.id(), new Replay(UUID.randomUUID(), receipt.revision(), Direction.UNDO));
        receipts.put(target.id(), response.receipt());
    }
    private CanvasItem emptyMedia() { return place(artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, "Shot", null).artifact().id()); }
    private CanvasItem place(UUID artifact) {
        UUID item = UUID.randomUUID();
        canvas.apply(owner, project.id(), List.of(new CanvasService.PlaceArtifact(item, artifact, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("320"), new BigDecimal("240"), 0, null, false)));
        return items.find(owner, project.id(), item).orElseThrow();
    }
    private CanvasItem card(UUID id, UUID artifact, UUID selected, long version) {
        Instant now = Instant.now();
        return new CanvasItem(id, project.id(), CanvasItem.SubjectType.ARTIFACT, artifact, selected, "Copied shot", BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false, version, now, now);
    }
    private UUID image(String title) {
        var body = mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", ImageAssetFixture.archive(assets, owner, project.id()).toString());
        return artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, title, body).resourceDefaultVersion().id();
    }
    private JsonNode text(String value) { return mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", value); }
    private record Copied(CanvasItem target, JsonNode frozen) {}
}

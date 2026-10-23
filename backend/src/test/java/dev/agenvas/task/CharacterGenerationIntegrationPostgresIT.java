package dev.agenvas.task;

import static dev.agenvas.testing.AgentFixture.configureDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.character.application.CharacterModels.*;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.export.application.ProjectExportManifestService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.CanvasAgentToolService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and archived synthetic images; generation uses the built-in Mock adapter. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CharacterGenerationIntegrationPostgresIT {
    @TempDir static Path storage;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> storage.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AssetService assets;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired MediaDraftService drafts;
    @Autowired CharacterService characters;
    @Autowired DirectMediaTaskService media;
    @Autowired TaskService tasks;
    @Autowired MediaExecutionWorker worker;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired CanvasAgentToolService agentCanvas;
    @Autowired ReadToolService reader;
    @Autowired ProjectExportManifestService exports;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;

    @Test
    void fixedCharacterFlowsThroughDraftApprovalTaskResultAndSafeProjectExport() {
        var owner = identities.setup("character-generation-admin", "character-generation-password-123");
        var project = projects.create(owner.userId(), "Character generation", Project.AspectRatio.SQUARE_1_1);
        UUID oldImage = image(owner.userId(), project.id(), "Old appearance");
        UUID newImage = image(owner.userId(), project.id(), "New appearance");
        UUID characterId = UUID.randomUUID(), lookId = UUID.randomUUID();
        var content = characterContent(lookId, oldImage, "Blue coat", "Short silver hair");
        var character = characters.save(owner.userId(), project.id(), characterId,
                new SaveCharacter(UUID.randomUUID(), 0, content));
        var target = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Shot 1", null);
        UUID itemId = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(itemId,
                target.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"),
                new BigDecimal("240"), 0, null, false)));
        var draft = drafts.save(owner.userId(), project.id(), itemId, 0, "A traveler at the station",
                mapper.createObjectNode().put("generationCount", 2), null, null, null, List.of(), List.of(), null);
        var selection = new CharacterBindingSelection(characterId, character.version(), lookId, true, false);
        var preview = characters.preview(owner.userId(), project.id(), itemId,
                new PreviewCharacterBindings(0, draft.version(), List.of(selection), ClearMode.REMOVE_OWNED));
        var applied = characters.apply(owner.userId(), project.id(), itemId,
                new ApplyCharacterBindings(UUID.randomUUID(), 0, draft.version(), List.of(selection),
                        ClearMode.REMOVE_OWNED, preview.previewHash()));
        assertThat(applied.draft().prompt()).contains("Aster", "Short silver hair", "Blue coat");
        assertThat(applied.draft().mediaInputs()).extracting(input -> input.versionId()).containsExactly(oldImage);
        UUID copyId = UUID.randomUUID();
        var sourceItem = canvas.list(owner.userId(), project.id()).getFirst().item();
        canvas.duplicate(owner.userId(), project.id(), itemId, copyId, sourceItem.version(), applied.draft().version(),
                new BigDecimal("1000"), BigDecimal.ZERO, sourceItem.width(), sourceItem.height(), 1);
        assertThat(characters.listForAgent(owner.userId(), project.id()).bindings()).hasSize(2);
        var preflight = media.preflight(owner.userId(), project.id(), target.artifact().id(), itemId,
                applied.draft().version());
        assertThat(preflight.safeSummary().at("/characterBindings/0/name").asText()).isEqualTo("Aster");
        Task accepted = media.run(owner.userId(), project.id(), target.artifact().id(), itemId,
                applied.draft().version(), "character-fixed-generation");
        JsonNode frozen = accepted.input().path("mediaInput").deepCopy();
        assertThat(frozen.at("/images/0/versionId").asText()).isEqualTo(oldImage.toString());
        assertThat(frozen.at("/characterBindings/0/characterVersion").asLong()).isEqualTo(character.version());
        assertThat(frozen.at("/characterContribution/versionIds/0").asText()).isEqualTo(oldImage.toString());
        assertThat(frozen.at("/characterContribution/promptBlock").asText()).contains("Blue coat");

        // A later profile revision marks review, while the applied draft and accepted task stay fixed.
        var revised = characters.save(owner.userId(), project.id(), characterId, new SaveCharacter(UUID.randomUUID(),
                character.version(), characterContent(lookId, newImage, "Red coat", "Long black hair")));
        var change = characters.previewChange(owner.userId(), project.id(), characterId,
                new SaveCharacter(UUID.randomUUID(), revised.version(), revised.content()));
        assertThat(change.affectedItems()).hasSize(3);
        assertThat(characters.listForAgent(owner.userId(), project.id()).bindings()).allSatisfy(binding -> {
            assertThat(binding.snapshots().getFirst().characterVersion()).isEqualTo(character.version());
            assertThat(binding.needsReview()).isTrue();
        });
        assertThat(drafts.get(owner.userId(), project.id(), itemId).prompt()).contains("Blue coat").doesNotContain("Red coat");
        assertThat(characters.listForAgent(owner.userId(), project.id()).bindings().getFirst().needsReview()).isTrue();
        assertJsonSemantics(tasks.get(owner.userId(), project.id(), accepted.id()).input().path("mediaInput"), frozen);
        assertThat(worker.submitOnce("character-integration-worker")).isEqualTo(1);
        assertThat(worker.submitOnce("character-integration-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        var resultVersion = canvas.listMediaVersions(owner.userId(), project.id(), itemId).getFirst();
        assertJsonSemantics(resultVersion.frozenInput(), frozen);
        assertThat(resultVersion.frozenInput().at("/characterBindings/0/characterVersion").asLong()).isEqualTo(character.version());
        assertThat(resultVersion.frozenInput().at("/images/0/versionId").asText()).isEqualTo(oldImage.toString());

        // The directory exposes planning metadata; an off-canvas profile image remains unreadable.
        var agent = configureDirector(agents, owner.userId(), project.id(), "Director", "Read continuity settings");
        var run = runs.create(owner.userId(), project.id(), agent.id(), "Review characters", "character-agent-run").run();
        var context = new TrustedToolContext(owner.userId(), project.id(), run.id());
        var directory = agentCanvas.readCanvas(context, run, UUID.randomUUID(), "{\"limit\":1}");
        assertThat(directory.at("/characters/0/name").asText()).isEqualTo("Aster");
        assertThat(directory.at("/characters/0/textDetailsOmitted").asBoolean()).isTrue();
        assertThat(directory.at("/data/0/characterBindings/0/snapshots/0/characterVersion").asLong()).isEqualTo(character.version());
        assertThat(directory.at("/data/0/characterBindings/0/needsReview").asBoolean()).isTrue();
        assertThat(directory.toString()).doesNotContain(newImage.toString());
        var detailRequest = mapper.createObjectNode().put("limit", 1).put("characterId", characterId.toString())
                .put("expectedCharacterVersion", revised.version()).put("characterTextLimit", 4000);
        var details = agentCanvas.readCanvas(context, run, UUID.randomUUID(), detailRequest.toString());
        assertThat(details.at("/characterDetails/text").asText()).contains("Red coat", "Long black hair");
        assertThat(details.at("/characterDetails/textPage/nextOffset").isNull()).isTrue();
        assertThat(details.toString()).doesNotContain(newImage.toString());
        var read = mapper.createObjectNode();
        read.putArray("versionIds").add(newImage.toString());
        assertThatThrownBy(() -> reader.artifacts(context, run, UUID.randomUUID(), read.toString()))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("INPUT_SCOPE_DENIED"));

        JsonNode manifest = mapper.valueToTree(exports.build(owner.userId(), project.id()));
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(9);
        assertThat(manifest.at("/characters/characters/0/content/looks/0/clothing").asText()).isEqualTo("Red coat");
        assertThat(manifest.at("/characters/bindings/0/snapshots/0/look/clothing").asText()).isEqualTo("Blue coat");
        assertThat(manifest.at("/characters/bindings/0/contribution/promptBlock").asText()).contains("Blue coat");
        assertThat(manifest.toString()).doesNotContain("objectKey", "contentUrl", "thumbnailUrl", "ownerId", "relayProfileId");

        // An Agent replacement removes the applied description: the binding becomes unusable.
        var replace = mapper.createObjectNode().put("itemId", itemId.toString())
                .put("expectedVersion", drafts.get(owner.userId(), project.id(), itemId).version())
                .put("prompt", "A different person");
        agentCanvas.updateMediaDraft(context, run, UUID.randomUUID(), replace.toString());
        assertThat(characters.frozenBindings(owner.userId(), project.id(), itemId)).isEmpty();
        assertThat(characters.listForAgent(owner.userId(), project.id()).bindings().getFirst().needsReview()).isTrue();
        assertJsonSemantics(resultVersion.frozenInput(), frozen);

        // A late additional result restores the old accepted continuity even after the source
        // has explicitly applied a newer character snapshot and entirely different references.
        var currentBinding = characters.listForAgent(owner.userId(), project.id()).bindings().stream()
                .filter(binding -> binding.canvasItemId().equals(itemId)).findFirst().orElseThrow();
        var currentDraft = drafts.get(owner.userId(), project.id(), itemId);
        var nextSelection = new CharacterBindingSelection(characterId, revised.version(), lookId, true, false);
        var nextPreview = characters.preview(owner.userId(), project.id(), itemId,
                new PreviewCharacterBindings(currentBinding.version(), currentDraft.version(), List.of(nextSelection), ClearMode.REMOVE_OWNED));
        characters.apply(owner.userId(), project.id(), itemId,
                new ApplyCharacterBindings(UUID.randomUUID(), currentBinding.version(), currentDraft.version(),
                        List.of(nextSelection), ClearMode.REMOVE_OWNED, nextPreview.previewHash()));
        assertThat(characters.frozenBindings(owner.userId(), project.id(), itemId).at("/0/characterVersion").asLong())
                .isEqualTo(revised.version());
        var lateCard = new TransactionTemplate(transactions).execute(status -> canvas.placeTaskMediaOutputWithinChange(
                owner.userId(), project.id(), itemId, target.artifact().id(), 1, frozen));
        assertThat(lateCard).isNotNull();
        var lateBinding = characters.listForAgent(owner.userId(), project.id()).bindings().stream()
                .filter(binding -> binding.canvasItemId().equals(lateCard.id())).findFirst().orElseThrow();
        assertThat(lateBinding.snapshots().getFirst().characterVersion()).isEqualTo(character.version());
        assertThat(lateBinding.snapshots().getFirst().look().clothing()).isEqualTo("Blue coat");
        assertThat(lateBinding.contribution().versionIds()).containsExactly(oldImage);
        assertThat(lateBinding.needsReview()).isTrue();
        assertThat(drafts.get(owner.userId(), project.id(), lateCard.id()).mediaInputs())
                .extracting(input -> input.versionId()).containsExactly(oldImage);
        assertJsonSemantics(characters.frozenBindings(owner.userId(), project.id(), lateCard.id()), frozen.path("characterBindings"));
        assertJsonSemantics(resultVersion.frozenInput(), frozen);
    }

    /** JSONB parsers may materialize a small long as an IntNode. Serialize and parse both
     * trees with the same mapper so equality checks every JSON value, role and array order. */
    private void assertJsonSemantics(JsonNode actual, JsonNode expected) {
        assertThat(mapper.readTree(actual.toString())).isEqualTo(mapper.readTree(expected.toString()));
    }

    private UUID image(UUID owner, UUID project, String title) {
        UUID asset = ImageAssetFixture.archive(assets, owner, project);
        return artifacts.create(owner, project, Artifact.Kind.IMAGE, title,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()))
                .resourceDefaultVersion().id();
    }

    private CharacterContent characterContent(UUID lookId, UUID versionId, String clothing, String description) {
        return new CharacterContent("Aster", description, List.of(new CharacterLook(lookId, "Street look",
                clothing, "Calm", "Traveler", List.of(new CharacterView(versionId, "FRONT", "Front view")))), null);
    }
}

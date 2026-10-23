package dev.agenvas.character;

import static dev.agenvas.character.application.CharacterModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
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

/** Real PostgreSQL verifies HTTP ownership, command replay, CAS and explicit shot review. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CharacterPostgresIT {
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
    @Autowired AssetService assets;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired CanvasItemQueryService items;
    @Autowired CanvasConnectionService connections;
    @Autowired MediaDraftService drafts;
    @Autowired CharacterService characters;
    @Autowired ProjectEventService events;
    @Autowired DirectMediaTaskService media;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired javax.sql.DataSource datasource;
    @Autowired WebApplicationContext context;

    @Test
    void explicitApplicationPreservesManualReferencesAndInvalidatesIndependentReview() throws Exception {
        AdminPrincipal owner = identities.setup("character-admin", "character-password-123");
        UUID ownerId = owner.userId();
        var project = projects.create(ownerId, "Character shots", Project.AspectRatio.SQUARE_1_1);
        var otherProject = projects.create(ownerId, "Other project", Project.AspectRatio.SQUARE_1_1);
        UUID first = image(ownerId, project.id(), "Manual reference").resourceDefaultVersion().id();
        UUID second = image(ownerId, project.id(), "Owned reference").resourceDefaultVersion().id();
        UUID foreign = image(ownerId, otherProject.id(), "Foreign reference").resourceDefaultVersion().id();
        UUID character = UUID.randomUUID(), look = UUID.randomUUID();
        var content = content(look, first, second, "Blue coat");
        SaveCharacter save = new SaveCharacter(UUID.randomUUID(), 0, content);
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String path = "/api/v1/projects/" + project.id() + "/characters";
        mvc.perform(put(path + "/" + character).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(save))).andExpect(status().isOk());
        mvc.perform(put(path + "/" + character).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(save))).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from project_character_revision where character_id=:id")
                .param("id", character).query(Long.class).single()).isEqualTo(1);
        mvc.perform(put(path + "/" + character).with(auth).contentType("application/json")
                .content(mapper.writeValueAsString(save))).andExpect(status().isForbidden());
        var outsider = authentication(new UsernamePasswordAuthenticationToken(new AdminPrincipal(UUID.randomUUID(), "outsider"), null, List.of()));
        mvc.perform(get(path).with(outsider)).andExpect(status().isNotFound());
        mvc.perform(put(path + "/" + UUID.randomUUID()).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(new SaveCharacter(UUID.randomUUID(), 0, content(look, foreign, second, "Foreign")))))
                .andExpect(status().isNotFound());
        var text = artifacts.create(ownerId, project.id(), Artifact.Kind.TEXT, "Wrong kind",
                mapper.createObjectNode().put("format", "MARKDOWN").put("text", "Not an image"));
        assertThatThrownBy(() -> characters.save(ownerId, project.id(), UUID.randomUUID(),
                new SaveCharacter(UUID.randomUUID(), 0, content(look, text.resourceDefaultVersion().id(), second, "Wrong type"))))
                .isInstanceOf(ApiProblemException.class);

        var target = image(ownerId, project.id(), "Shot with existing result");
        UUID item = UUID.randomUUID();
        canvas.apply(ownerId, project.id(), List.of(new CanvasService.PlaceArtifact(item, target.artifact().id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false)));
        var before = drafts.save(ownerId, project.id(), item, 0, "A station", mapper.createObjectNode(), null,
                null, null, List.of(new MediaDraftService.SaveMediaInput(first, MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of(), null);
        var selections = List.of(new CharacterBindingSelection(character, 1, look, true, false));
        var request = new PreviewCharacterBindings(0, before.version(), selections, ClearMode.REMOVE_OWNED);
        var preview = characters.preview(ownerId, project.id(), item, request);
        long eventCursor = events.replayWindow(ownerId, project.id()).latestSequence();
        var applyCommand = new ApplyCharacterBindings(UUID.randomUUID(), 0, before.version(), selections, ClearMode.REMOVE_OWNED, preview.previewHash());
        var applied = characters.apply(ownerId, project.id(), item, applyCommand);
        assertThat(applied.draft().prompt()).contains("A station", "Blue coat");
        assertThat(applied.draft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(first, second);
        assertThat(applied.binding().contribution().versionIds()).containsExactly(second);
        assertThat(applied.binding().needsReview()).isTrue();
        assertThat(events.listAfter(ownerId, project.id(), eventCursor, 10)).extracting(event -> event.type())
                .containsExactly("media.draft.changed", "character.bindings.changed");
        assertThat(characters.apply(ownerId, project.id(), item, applyCommand)).isEqualTo(applied);
        assertThat(drafts.get(ownerId, project.id(), item).version()).isEqualTo(applied.draft().version());
        assertThat(characters.previewChange(ownerId, project.id(), character, new SaveCharacter(UUID.randomUUID(), 1, content)).affectedItems())
                .extracting(AffectedItem::canvasItemId).containsExactly(item);

        var reviewed = characters.review(ownerId, project.id(), item, new ReviewCharacterBindings(applied.binding().version(),
                applied.draft().version(), target.resourceDefaultVersion().id()));
        assertThat(reviewed.needsReview()).isFalse();
        // Selecting the same result again advances the selection epoch and still invalidates review.
        canvas.selectMediaVersion(ownerId, project.id(), item, target.resourceDefaultVersion().id(),
                items.requireArtifactItem(ownerId, project.id(), item).version());
        assertThat(characters.list(ownerId, project.id()).bindings().getFirst().needsReview()).isTrue();
        var current = drafts.get(ownerId, project.id(), item);
        drafts.save(ownerId, project.id(), item, current.version(), current.prompt() + " at dusk", current.parameters(), null,
                current.capabilityId(), null, commands(current), current.mentions(), current.styleId());
        assertThat(characters.list(ownerId, project.id()).bindings().getFirst().reviewReasons()).contains(ReviewReason.DRAFT_CHANGED);

        // A preview is invalid after a draft CAS change, including when the chosen profile stayed the same.
        var stale = new ApplyCharacterBindings(UUID.randomUUID(), reviewed.version(), applied.draft().version(), selections,
                ClearMode.REMOVE_OWNED, preview.previewHash());
        assertThatThrownBy(() -> characters.apply(ownerId, project.id(), item, stale)).isInstanceOf(ApiProblemException.class);
        current = drafts.get(ownerId, project.id(), item);
        var clear = characters.preview(ownerId, project.id(), item,
                new PreviewCharacterBindings(reviewed.version(), current.version(), List.of(), ClearMode.REMOVE_OWNED));
        var cleared = characters.apply(ownerId, project.id(), item, new ApplyCharacterBindings(UUID.randomUUID(), reviewed.version(),
                current.version(), List.of(), ClearMode.REMOVE_OWNED, clear.previewHash()));
        assertThat(cleared.draft().mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(first);
        assertThat(cleared.draft().prompt()).isEqualTo("A station at dusk");

        // Both writes see the same profile version; the project lock and CAS accept exactly one.
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> update = () -> {
                try { characters.save(ownerId, project.id(), character, new SaveCharacter(UUID.randomUUID(), 1,
                        content(look, first, second, "Updated coat"))); return true; }
                catch (ApiProblemException error) { assertThat(error.code()).isEqualTo("CHARACTER_PREVIEW_CONFLICT"); return false; }
            };
            var results = pool.invokeAll(List.of(update, update));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.sql("select count(*) from project_character_revision where character_id=:id")
                .param("id", character).query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.sql("update project_character_revision set archived=true where character_id=:id")
                .param("id", character).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);

        var profile = characters.list(ownerId, project.id()).characters().getFirst();
        characters.archive(ownerId, project.id(), character, new ArchiveCharacter(UUID.randomUUID(), profile.version()));
        assertThatThrownBy(() -> characters.preview(ownerId, project.id(), item,
                new PreviewCharacterBindings(cleared.binding().version(), cleared.draft().version(),
                        List.of(new CharacterBindingSelection(character, profile.version() + 1, look, true, false)), ClearMode.REMOVE_OWNED)))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("CHARACTER_ARCHIVED"));
        // Generation may continue on an unbound draft; explicit application remains unavailable while busy.
        media.run(ownerId, project.id(), target.artifact().id(), item, cleared.draft().version(), "character-busy");
        assertThatThrownBy(() -> characters.preview(ownerId, project.id(), item,
                new PreviewCharacterBindings(cleared.binding().version(), cleared.draft().version(), List.of(), ClearMode.KEEP_INPUTS)))
                .isInstanceOfSatisfying(ApiProblemException.class, error -> assertThat(error.code()).isEqualTo("CANVAS_ITEM_BUSY"));
        archiveRaceRejectsEveryNewMutation(ownerId);
        releaseOwnedSourceThenDisconnect(ownerId);
    }

    private void releaseOwnedSourceThenDisconnect(UUID owner) {
        var project = projects.create(owner, "Character source ownership", Project.AspectRatio.SQUARE_1_1);
        var portrait = image(owner, project.id(), "Connected portrait");
        var unrelated = image(owner, project.id(), "Manual atmosphere");
        var shot = image(owner, project.id(), "Shot result");
        UUID source = UUID.randomUUID(), target = UUID.randomUUID(), character = UUID.randomUUID(), look = UUID.randomUUID();
        canvas.apply(owner, project.id(), List.of(
                new CanvasService.PlaceArtifact(source, portrait.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO,
                        new BigDecimal("320"), new BigDecimal("240"), 0, null, false),
                new CanvasService.PlaceArtifact(target, shot.artifact().id(), new BigDecimal("400"), BigDecimal.ZERO,
                        new BigDecimal("320"), new BigDecimal("240"), 1, null, false)));
        var draft = drafts.save(owner, project.id(), target, 0, "Keep atmosphere", mapper.createObjectNode(), null,
                null, null, List.of(new MediaDraftService.SaveMediaInput(unrelated.resourceDefaultVersion().id(),
                        MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of(), null);
        var content = new CharacterContent("Aster", "Silver hair", List.of(new CharacterLook(look, "Main",
                "Coat", "Calm", "", List.of(new CharacterView(portrait.resourceDefaultVersion().id(), "Front", "")))), null);
        characters.save(owner, project.id(), character, new SaveCharacter(UUID.randomUUID(), 0, content));
        var selections = List.of(new CharacterBindingSelection(character, 1, look, true, false));
        var preview = characters.preview(owner, project.id(), target, new PreviewCharacterBindings(0, draft.version(), selections, ClearMode.REMOVE_OWNED));
        var applied = characters.apply(owner, project.id(), target,
                new ApplyCharacterBindings(UUID.randomUUID(), 0, draft.version(), selections, ClearMode.REMOVE_OWNED, preview.previewHash()));
        var connected = connections.connect(owner, project.id(), source, target, portrait.resourceDefaultVersion().id(),
                CanvasConnection.RelationType.MEDIA_INPUT, applied.draft().version());
        var clear = characters.preview(owner, project.id(), target,
                new PreviewCharacterBindings(applied.binding().version(), connected.draft().version(), List.of(), ClearMode.REMOVE_OWNED));
        var cleared = characters.apply(owner, project.id(), target, new ApplyCharacterBindings(UUID.randomUUID(),
                applied.binding().version(), connected.draft().version(), List.of(), ClearMode.REMOVE_OWNED, clear.previewHash()));
        var retained = cleared.draft().mediaInputs().stream().filter(input -> input.versionId().equals(portrait.resourceDefaultVersion().id())).findFirst().orElseThrow();
        assertThat(retained.sources()).extracting(MediaDraft.InputSource::type).containsExactly(MediaDraft.SourceType.CONNECTION);
        connections.disconnect(owner, project.id(), connected.connection().id(), cleared.draft().version());
        var after = drafts.get(owner, project.id(), target);
        assertThat(after.mediaInputs()).extracting(MediaDraft.MediaInput::versionId).containsExactly(unrelated.resourceDefaultVersion().id());
        assertThat(after.prompt()).contains("Keep atmosphere").doesNotContain("Character: Aster");
    }

    private void archiveRaceRejectsEveryNewMutation(UUID owner) throws Exception {
        for (String operation : List.of("SAVE", "ARCHIVE", "REVIEW")) {
            var project = projects.create(owner, "Archive race " + operation, Project.AspectRatio.SQUARE_1_1);
            UUID character = UUID.randomUUID(), look = UUID.randomUUID();
            CharacterContent content = new CharacterContent("Race character", "Identity", List.of(
                    new CharacterLook(look, "Main", "Coat", "Calm", "Details", List.of())), null);
            characters.save(owner, project.id(), character, new SaveCharacter(UUID.randomUUID(), 0, content));
            Callable<Object> mutation;
            if (operation.equals("SAVE")) mutation = () -> characters.save(owner, project.id(), character,
                    new SaveCharacter(UUID.randomUUID(), 1, content));
            else if (operation.equals("ARCHIVE")) mutation = () -> characters.archive(owner, project.id(), character,
                    new ArchiveCharacter(UUID.randomUUID(), 1));
            else {
                var target = image(owner, project.id(), "Review result");
                UUID item = UUID.randomUUID();
                canvas.apply(owner, project.id(), List.of(new CanvasService.PlaceArtifact(item, target.artifact().id(),
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("240"), 0, null, false)));
                var selections = List.of(new CharacterBindingSelection(character, 1, look, true, false));
                var preview = characters.preview(owner, project.id(), item,
                        new PreviewCharacterBindings(0, 0, selections, ClearMode.REMOVE_OWNED));
                var applied = characters.apply(owner, project.id(), item,
                        new ApplyCharacterBindings(UUID.randomUUID(), 0, 0, selections, ClearMode.REMOVE_OWNED, preview.previewHash()));
                mutation = () -> characters.review(owner, project.id(), item,
                        new ReviewCharacterBindings(applied.binding().version(), applied.draft().version(), target.resourceDefaultVersion().id()));
            }
            try (var gate = datasource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
                gate.setAutoCommit(false);
                int gatePid;
                try (var statement = gate.createStatement(); var result = statement.executeQuery("select pg_backend_pid()")) {
                    result.next(); gatePid = result.getInt(1);
                }
                try (var statement = gate.prepareStatement("update project set status='ARCHIVED', archived_at=now(), updated_at=now(), version=version+1 where id=?")) {
                    statement.setObject(1, project.id()); assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                var pending = pool.submit(mutation);
                try {
                    // The precheck reads committed ACTIVE while the new archive is held uncommitted;
                    // the later project event lock blocks until this real archive transaction commits.
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                            assertThat(jdbc.sql("select count(*) from pg_stat_activity where :gate=any(pg_blocking_pids(pid))")
                                    .param("gate", gatePid).query(Integer.class).single()).isPositive());
                    gate.commit();
                    assertThatThrownBy(() -> pending.get(10, java.util.concurrent.TimeUnit.SECONDS))
                            .hasCauseInstanceOf(ApiProblemException.class)
                            .satisfies(error -> assertThat(((ApiProblemException) error.getCause()).code()).isEqualTo("PROJECT_ARCHIVED"));
                } finally { gate.rollback(); }
            }
            assertThat(jdbc.sql("select version from project_character where id=:id").param("id", character).query(Long.class).single()).isOne();
            assertThat(jdbc.sql("select archived from project_character where id=:id").param("id", character).query(Boolean.class).single()).isFalse();
        }
    }
    private ArtifactService.ArtifactView image(UUID owner, UUID project, String title) {
        UUID asset = ImageAssetFixture.archive(assets, owner, project);
        return artifacts.create(owner, project, Artifact.Kind.IMAGE, title,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
    }
    private CharacterContent content(UUID look, UUID first, UUID second, String clothing) {
        return new CharacterContent("Aster", "Silver hair", List.of(new CharacterLook(look, "Main look", clothing,
                "Calm", "Traveler", List.of(new CharacterView(first, "Front", "Face"), new CharacterView(second, "Side", "Silhouette")))), null);
    }
    private List<MediaDraftService.SaveMediaInput> commands(MediaDraft draft) {
        return draft.mediaInputs().stream().map(input -> new MediaDraftService.SaveMediaInput(input.versionId(), input.role(), input.color())).toList();
    }
}

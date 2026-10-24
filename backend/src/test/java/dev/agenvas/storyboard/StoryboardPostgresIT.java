package dev.agenvas.storyboard;

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
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.application.StoryboardService.*;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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

/** Synthetic media, real PostgreSQL/HTTP: scoped selections, immutable history and isolated review impact. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class StoryboardPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired StoryboardService boards;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired MediaToolRunner media;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    AdminPrincipal owner;
    Project project;

    @BeforeEach void setup() {
        owner = jdbc.sql("select id from app_user where login_name='storyboard-admin'").query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "storyboard-admin"))
                .orElseGet(() -> identities.setup("storyboard-admin", "storyboard-password-123"));
        project = projects.create(owner.userId(), "Storyboard", Project.AspectRatio.LANDSCAPE_16_9);
    }

    @Test void dialogueChangeRetainsPaidSelectionsAndOnlyMarksItsShotAndCompositionForReview() throws Exception {
        var first = image(project);
        var second = image(project);
        var film = video(project);
        ShotInput one = shot(UUID.randomUUID(), "SHOT-001", "hello", List.of(production(first, false)));
        ShotInput two = shot(UUID.randomUUID(), "SHOT-002", "other", List.of(production(second, false)));
        var initial = boards.save(owner.userId(), project.id(), new Save(0, List.of(one, two), composition(film, false)));
        var revised = boards.save(owner.userId(), project.id(), new Save(1,
                List.of(shot(one.id(), one.code(), "new dialogue", one.productions()), two), composition(film, false)));
        assertThat(revised.shots().getFirst().revision()).isEqualTo(2);
        assertThat(revised.shots().getFirst().productions().getFirst().selectedVersionId()).isEqualTo(first.version());
        assertThat(revised.shots().getFirst().productions().getFirst().needsReview()).isTrue();
        assertThat(revised.shots().get(1)).isEqualTo(initial.shots().get(1));
        assertThat(revised.composition().selectedVersionId()).isEqualTo(film.version());
        assertThat(revised.composition().needsReview()).isTrue();
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", project.id()).query(Long.class).single()).isZero();
        var confirmed = boards.save(owner.userId(), project.id(), new Save(2,
                List.of(shot(one.id(), one.code(), "new dialogue", List.of(production(first, true))), two), composition(film, true)));
        assertThat(confirmed.shots().getFirst().productions().getFirst().reviewedRevision()).isEqualTo(2);
        assertThat(confirmed.shots().getFirst().productions().getFirst().needsReview()).isFalse();
        assertThat(confirmed.composition().needsReview()).isFalse();
        var reordered = boards.save(owner.userId(), project.id(), new Save(3,
                List.of(two, shot(one.id(), "SHOT-003", "new dialogue", List.of(production(first, false)))), composition(film, false)));
        assertThat(reordered.shots().get(1).revision()).isEqualTo(2);
        assertThat(reordered.shots().get(1).productions().getFirst().needsReview()).isFalse();
        assertThat(reordered.composition().needsReview()).isTrue();
        assertThat(boards.listRevisions(owner.userId(), project.id())).hasSize(4);
        assertThat(boards.revisions(owner.userId(), project.id(), 0, 1)).containsExactly(initial);
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:id and type='storyboard.changed'")
                .param("id", project.id()).query(Long.class).single()).isEqualTo(4);
    }

    @Test void validatesPreciseProjectAndNodeOwnershipAndAllowsRemovingDetachedBindings() throws Exception {
        var own = image(project);
        var foreignProject = projects.create(owner.userId(), "Other", Project.AspectRatio.SQUARE_1_1);
        var foreign = image(foreignProject);
        var shotId = UUID.randomUUID();
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0,
                List.of(shot(shotId, "SHOT-001", "a", List.of(production(foreign, false)))), null)));
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0,
                List.of(shot(shotId, "SHOT-001", "a", List.of(new ProductionInput(Storyboard.Role.VIDEO, own.item(), own.version(), false)))), null)));
        var sibling = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), own.artifact());
        UUID revised = artifacts.revise(owner.userId(), project.id(), own.artifact(), 0, null,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", own.asset().toString())).resourceDefaultVersion().id();
        // Same Artifact still does not confer this card's result ownership.
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0,
                List.of(shot(shotId, "SHOT-001", "a", List.of(new ProductionInput(Storyboard.Role.KEYFRAME, sibling, revised, false)))), null)));
        var text = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "Script",
                mapper.createObjectNode().put("format", "MARKDOWN").put("text", "script"));
        var badReference = new ShotInput(shotId, "SHOT-001", "", "", "", "", BigDecimal.ONE,
                null, List.of(text.resourceDefaultVersion().id()), List.of());
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0, List.of(badReference), null)));
        var badSource = new ShotInput(shotId, "SHOT-001", "", "", "", "", BigDecimal.ONE,
                own.version(), List.of(), List.of());
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0, List.of(badSource), null)));
        var original = shot(shotId, "SHOT-001", "a", List.of(production(own, false)));
        boards.save(owner.userId(), project.id(), new Save(0, List.of(original), null));
        var item = canvas.list(owner.userId(), project.id()).stream().filter(entry -> entry.item().id().equals(own.item())).findFirst().orElseThrow().item();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(own.item(), item.version())));
        assertThat(boards.save(owner.userId(), project.id(), new Save(1,
                List.of(shot(shotId, "SHOT-001", "edited", original.productions())), null)).shots().getFirst().productions()).hasSize(1);
        assertThat(boards.save(owner.userId(), project.id(), new Save(2,
                List.of(shot(shotId, "SHOT-001", "edited", List.of())), null)).shots().getFirst().productions()).isEmpty();
    }

    @Test void rejectsMalformedBoardsAndMetadataEditsPreserveShotRevisions() {
        var id = UUID.randomUUID();
        var first = shot(id, "SHOT-001", "hello", List.of());
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0,
                java.util.Arrays.asList((ShotInput) null), null)));
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0,
                List.of(first, shot(UUID.randomUUID(), "SHOT-001", "other", List.of())), null)));
        invalid(() -> boards.save(owner.userId(), project.id(), new Save(0,
                List.of(new ShotInput(id, "SHOT-001", "", "", "", "", new BigDecimal("0.0001"), null, List.of(), List.of())), null)));
        boards.save(owner.userId(), project.id(), new Save(0, List.of(first), null));
        var renamed = new ShotInput(id, "SHOT-002", "SC-02", "Renamed", first.description(), first.dialogue(),
                first.durationSeconds(), null, List.of(), List.of());
        assertThat(boards.save(owner.userId(), project.id(), new Save(1, List.of(renamed), null)).shots().getFirst().revision()).isEqualTo(1);
    }

    @Test void concurrentCasAppendsOneImmutableRevisionAndOneEvent() throws Exception {
        UUID shotId = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await();
                try { boards.save(owner.userId(), project.id(), new Save(0,
                        List.of(shot(shotId, "SHOT-001", "a", List.of())), null)); return true; }
                catch (ApiProblemException problem) { assertThat(problem.code()).isEqualTo("STORYBOARD_VERSION_CONFLICT"); return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(boards.get(owner.userId(), project.id()).version()).isEqualTo(1);
        assertThat(boards.listRevisions(owner.userId(), project.id())).hasSize(1);
        assertThatThrownBy(() -> jdbc.sql("update storyboard_revision set content_json='{}' where project_id=:id")
                .param("id", project.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from storyboard_revision where project_id=:id")
                .param("id", project.id()).update()).isInstanceOf(DataAccessException.class);
        assertThat(boards.listRevisions(owner.userId(), project.id())).hasSize(1);
        assertThatThrownBy(() -> jdbc.sql("insert into storyboard_revision(project_id,version,content_json,created_at) values (:id,2,'{}',now())")
                .param("id", project.id()).update()).isInstanceOf(DataAccessException.class);
    }

    @Test void eventFailureRollsBackTheCurrentPointerAndImmutableRevision() {
        jdbc.sql("CREATE FUNCTION storyboard_test_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.type = 'storyboard.changed' THEN RAISE EXCEPTION 'synthetic event failure'; "
                + "END IF; RETURN NEW; END; $$").update();
        jdbc.sql("CREATE TRIGGER storyboard_test_event_failure BEFORE INSERT ON project_event "
                + "FOR EACH ROW EXECUTE FUNCTION storyboard_test_reject_event()").update();
        var request = new Save(0, List.of(shot(UUID.randomUUID(), "SHOT-001", "hello", List.of())), null);
        try {
            assertThatThrownBy(() -> boards.save(owner.userId(), project.id(), request))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class);
        } finally {
            jdbc.sql("DROP TRIGGER storyboard_test_event_failure ON project_event").update();
            jdbc.sql("DROP FUNCTION storyboard_test_reject_event()").update();
        }
        assertThat(boards.get(owner.userId(), project.id()).version()).isZero();
        assertThat(boards.listRevisions(owner.userId(), project.id())).isEmpty();
        assertThat(jdbc.sql("select count(*) from project_event where project_id=:id and type='storyboard.changed'")
                .param("id", project.id()).query(Long.class).single()).isZero();
        assertThat(boards.save(owner.userId(), project.id(), request).version()).isEqualTo(1);
    }

    @Test void httpContractEnforcesAuthenticationCsrfCasAndHistoryBounds() throws Exception {
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id() + "/storyboard";
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
        var empty = mapper.readTree(mvc.perform(get(base).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(empty.path("version").asInt()).isZero();
        assertThat(empty.path("shots").isArray()).isTrue();
        assertThat(empty.path("composition").isNull()).isTrue();
        var save = new Save(0, List.of(shot(UUID.randomUUID(), "SHOT-001", "hello", List.of())), null);
        mvc.perform(put(base).with(auth).contentType("application/json").content(mapper.writeValueAsString(save))).andExpect(status().isForbidden());
        mvc.perform(put(base).with(auth).with(csrf()).contentType("application/json").content("{\"shots\":[],\"composition\":null}"))
                .andExpect(status().isBadRequest());
        var accepted = mapper.readTree(mvc.perform(put(base).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(save)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(accepted.at("/shots/0/revision").asInt()).isEqualTo(1);
        mvc.perform(put(base).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(save))).andExpect(status().isConflict());
        mvc.perform(get(base + "/revisions?limit=101").with(auth)).andExpect(status().isBadRequest());
        var page = mapper.readTree(mvc.perform(get(base + "/revisions?limit=1").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("nextAfterVersion").asInt()).isEqualTo(1);
        var approvals = mapper.readTree(mvc.perform(get(base + "/media-approvals").with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(approvals.path("items").isArray()).isTrue();
    }

    private ShotInput shot(UUID id, String code, String dialogue, List<ProductionInput> productions) {
        return new ShotInput(id, code, "SC-01", "Shot", "Description", dialogue, BigDecimal.valueOf(4), null, List.of(), productions);
    }
    private ProductionInput production(Target target, boolean review) { return new ProductionInput(Storyboard.Role.KEYFRAME, target.item(), target.version(), review); }
    private CompositionInput composition(Target target, boolean review) { return new CompositionInput(target.item(), target.version(), review); }
    private Target image(Project target) throws Exception {
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), target.id());
        return place(target, Artifact.Kind.IMAGE, asset);
    }
    private Target video(Project target) throws Exception {
        var source = Files.createTempFile("storyboard-synthetic-video-", ".mp4");
        try {
            media.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=32x32:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", source.toString()));
            try (var stream = Files.newInputStream(source)) { return place(target, Artifact.Kind.VIDEO, assets.archiveVideo(owner.userId(), target.id(), stream).id()); }
        } finally { Files.deleteIfExists(source); }
    }
    private Target place(Project target, Artifact.Kind kind, UUID asset) {
        var artifact = artifacts.create(owner.userId(), target.id(), kind, "Synthetic",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        UUID item = CanvasMediaFixture.place(canvas, owner.userId(), target.id(), artifact.artifact().id());
        return new Target(artifact.artifact().id(), item, artifact.resourceDefaultVersion().id(), asset);
    }
    private void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(ApiProblemException.class); }
    private record Target(UUID artifact, UUID item, UUID version, UUID asset) {}
}

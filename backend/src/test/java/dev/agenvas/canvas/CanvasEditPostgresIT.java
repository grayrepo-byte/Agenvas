package dev.agenvas.canvas;

import static dev.agenvas.canvas.application.CanvasEditModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasEditService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Real PostgreSQL proves atomic groups/copies/history, durable retry, concurrency and event rollback. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CanvasEditPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired CanvasService canvas;
    @Autowired CanvasEditService edits;
    @Autowired ProjectEventService events;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    AdminPrincipal owner;
    Project project;
    @BeforeEach void setup() {
        owner = jdbc.sql("select id from app_user where login_name='canvas-edit-admin'").query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "canvas-edit-admin"))
                .orElseGet(() -> identities.setup("canvas-edit-admin", "canvas-edit-password-123"));
        project = projects.create(owner.userId(), "Canvas edit", Project.AspectRatio.LANDSCAPE_16_9);
    }
    @Test void groupsArePersistentScopedAndCasProtectedAndLocksRejectPartialMembership() {
        CanvasItem first = text("First"), second = text("Second");
        UUID group = UUID.randomUUID();
        var create = edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(first, first.title(), group), change(second, second.title(), group)),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "  Scene 😀  ", true)), List.of()));
        assertThat(create.groups()).hasSize(1); assertThat(create.groups().getFirst().name()).isEqualTo("Scene 😀");
        assertThat(create.groups().getFirst().version()).isZero();
        assertThat(create.items()).allMatch(entry -> group.equals(entry.item().groupId()));
        var rename = edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Scene two", false)), List.of()));
        assertThat(rename.groups().getFirst().version()).isEqualTo(1);
        assertConflict(() -> edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Stale", false)), List.of())));
        CanvasItem savedFirst = item(first.id()), savedSecond = item(second.id());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.SetLocked(second.id(), savedSecond.version(), true)));
        assertConflict(() -> edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(savedFirst, savedFirst.title(), null), change(item(second.id()), second.title(), null)),
                List.of(new GroupChange(GroupChangeType.DELETE, group, 1, null, null)), List.of())));
        assertThat(item(first.id()).groupId()).isEqualTo(group);
        assertThat(edits.get(owner.userId(), project.id()).groups()).hasSize(1);
        Project other = projects.create(owner.userId(), "Other", Project.AspectRatio.SQUARE_1_1);
        var foreignGroup = edits.edit(owner.userId(), other.id(), new Edit(UUID.randomUUID(), Operation.GROUP, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, UUID.randomUUID(), 0, "Foreign", false)), List.of())).groups().getFirst();
        assertConflict(() -> edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(item(first.id()), "First", foreignGroup.id())), List.of(), List.of())));
        assertThatThrownBy(() -> jdbc.sql("update canvas_item set group_id=:group where id=:item")
                .param("group", foreignGroup.id()).param("item", first.id()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void directGroupCreationIsAtomicAndRetryableAtTheMemberLimit() {
        CanvasItem source = text("Synthetic group source");
        UUID group = UUID.randomUUID();
        edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(source, source.title(), group)),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Direct creation", false)), List.of()));
        List<CanvasService.PlaceArtifact> placements = java.util.stream.IntStream.range(0, 99).mapToObj(index ->
                new CanvasService.PlaceArtifact(UUID.randomUUID(), source.subjectId(), BigDecimal.TEN, new BigDecimal("400"),
                        source.width(), source.height(), 1, group, false)).toList();
        canvas.apply(owner.userId(), project.id(), placements);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(100);
        long seq = events.replayWindow(owner.userId(), project.id()).latestSequence();
        canvas.apply(owner.userId(), project.id(), List.of(placements.getLast()));
        assertThat(events.replayWindow(owner.userId(), project.id()).latestSequence()).isEqualTo(seq);
        var overflow = new CanvasService.PlaceArtifact(UUID.randomUUID(), source.subjectId(), BigDecimal.TEN, new BigDecimal("400"),
                source.width(), source.height(), 1, group, false);
        assertThatThrownBy(() -> canvas.apply(owner.userId(), project.id(), List.of(overflow)))
                .isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo("CANVAS_EDIT_LIMIT"));
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(100);
        assertThat(events.replayWindow(owner.userId(), project.id()).latestSequence()).isEqualTo(seq);
    }

    @Test void droppingIntoAGroupReplaysPositionAndMembershipTogetherWithoutChangingContent() {
        CanvasItem member = text("Existing member"), incoming = text("Incoming body");
        UUID group = UUID.randomUUID();
        edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(member, member.title(), group)),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Drop target", false)), List.of()));
        UUID content = currentVersion(incoming);
        var drop = new ItemChange(incoming.id(), incoming.version(), incoming.title(), new BigDecimal("120"), new BigDecimal("360"),
                incoming.width(), incoming.height(), incoming.zIndex(), group, incoming.locked());
        var saved = edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.DISPLAY, List.of(drop), List.of(), List.of()));
        assertThat(item(incoming.id()).groupId()).isEqualTo(group);
        assertThat(item(incoming.id()).x()).isEqualByComparingTo("120");
        edits.replay(owner.userId(), project.id(), saved.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        assertThat(item(incoming.id()).groupId()).isNull();
        assertThat(item(incoming.id()).x()).isEqualByComparingTo(incoming.x());
        edits.replay(owner.userId(), project.id(), saved.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
        assertThat(item(incoming.id()).groupId()).isEqualTo(group);
        assertThat(item(incoming.id()).y()).isEqualByComparingTo("360");
        assertThat(currentVersion(item(incoming.id()))).isEqualTo(content);
    }
    @Test void textCopiesHaveIndependentBodiesAndFixedRedoWhileRetriesCreateOneBranch() {
        CanvasItem source = text("Before"); UUID target = UUID.randomUUID();
        var copyRequest = copyRequest(source, target);
        var copied = edits.edit(owner.userId(), project.id(), copyRequest);
        var repeated = edits.edit(owner.userId(), project.id(), copyRequest);
        assertThat(repeated.receipt()).isEqualTo(copied.receipt());
        CanvasItem clone = item(target);
        assertThat(clone.subjectId()).isNotEqualTo(source.subjectId());
        assertThat(edits.get(owner.userId(), project.id()).items()).hasSize(2);
        assertThat(artifacts.get(owner.userId(), project.id(), clone.subjectId()).resourceDefaultVersion().content().path("text").asText()).isEqualTo("Before");
        var sourceContent = artifacts.get(owner.userId(), project.id(), source.subjectId());
        artifacts.revise(owner.userId(), project.id(), source.subjectId(), sourceContent.artifact().version(), "Source edited",
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Source changed after copy"));
        var undone = edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        assertThat(undone.items()).hasSize(1);
        var redone = edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
        assertThat(redone.items()).hasSize(2); assertThat(item(target).version()).isGreaterThan(clone.version());
        var cloneContent = artifacts.get(owner.userId(), project.id(), clone.subjectId());
        assertThat(cloneContent.resourceDefaultVersion().content().path("text").asText()).isEqualTo("Before");
        artifacts.revise(owner.userId(), project.id(), clone.subjectId(), cloneContent.artifact().version(), "Clone edited",
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", "Keep this later work"));
        assertConflict(() -> edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 2, Direction.UNDO)));
        assertThat(item(target).subjectId()).isEqualTo(clone.subjectId());
    }
    @Test void groupCopyCreatesNewMetadataAndIndependentTextNodesAndReplaysAtomically() {
        CanvasItem one = text("One"), two = text("Two"); UUID sourceGroup = UUID.randomUUID(), copiedGroup = UUID.randomUUID();
        edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                List.of(change(one, "One", sourceGroup), change(two, "Two", sourceGroup)),
                List.of(new GroupChange(GroupChangeType.UPSERT, sourceGroup, 0, "Scene", true)), List.of()));
        UUID firstTarget = UUID.randomUUID(), secondTarget = UUID.randomUUID();
        Copy first = copy(item(one.id()), firstTarget), second = copy(item(two.id()), secondTarget);
        var copied = edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, copiedGroup, 0, "Scene copy", false)), List.of(
                        new Copy(first.sourceItemId(), first.targetItemId(), first.expectedSourceVersion(), null, first.expectedSourceContentVersion(), first.x(), first.y(), first.width(), first.height(), first.zIndex(), copiedGroup),
                        new Copy(second.sourceItemId(), second.targetItemId(), second.expectedSourceVersion(), null, second.expectedSourceContentVersion(), second.x(), second.y(), second.width(), second.height(), second.zIndex(), copiedGroup))));
        assertThat(copied.groups()).hasSize(2); assertThat(item(firstTarget).groupId()).isEqualTo(copiedGroup);
        assertThat(item(firstTarget).subjectId()).isNotEqualTo(one.subjectId()); assertThat(item(secondTarget).subjectId()).isNotEqualTo(two.subjectId());
        edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        assertThat(edits.get(owner.userId(), project.id()).groups()).extracting(Group::id).containsExactly(sourceGroup);
        assertThat(edits.get(owner.userId(), project.id()).items()).hasSize(2);
        edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
        assertThat(edits.get(owner.userId(), project.id()).groups()).hasSize(2);
        assertThat(item(firstTarget).groupId()).isEqualTo(copiedGroup);
        assertThat(jdbc.sql("select count(*) from task where project_id=:id").param("id", project.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from canvas_connection where project_id=:id").param("id", project.id()).query(Long.class).single()).isZero();
    }
    @Test void oneHundredNodeGroupCopiesAndReplaysAsAnAtomicEdit() {
        List<CanvasItem> sources = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) sources.add(text("Synthetic node " + i));
        UUID group = UUID.randomUUID(), copiedGroup = UUID.randomUUID();
        edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.GROUP,
                sources.stream().map(source -> change(source, source.title(), group)).toList(),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Large scene", false)), List.of()));
        List<Copy> batch = sources.stream().map(source -> {
            Copy copy = copy(item(source.id()), UUID.randomUUID());
            return new Copy(copy.sourceItemId(), copy.targetItemId(), copy.expectedSourceVersion(), null,
                    copy.expectedSourceContentVersion(), copy.x(), copy.y(), copy.width(), copy.height(), copy.zIndex(), copiedGroup);
        }).toList();
        var copied = edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.DUPLICATE,
                List.of(), List.of(new GroupChange(GroupChangeType.UPSERT, copiedGroup, 0, "Large copy", true)), batch));
        assertThat(copied.items()).hasSize(200);
        assertThat(copied.items().stream().map(CanvasService.CanvasEntry::item).filter(entry -> copiedGroup.equals(entry.groupId()))).hasSize(100);
        assertThat(copied.items().stream().map(entry -> entry.item().subjectId()).distinct()).hasSize(200);
        edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        assertThat(edits.get(owner.userId(), project.id()).items()).hasSize(100);
        var redone = edits.replay(owner.userId(), project.id(), copied.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
        assertThat(redone.items()).hasSize(200);
        assertThat(redone.groups().stream().filter(entry -> entry.id().equals(copiedGroup)).findFirst().orElseThrow().collapsed()).isTrue();
    }
    @Test void presentationHistorySupportsRepeatedSameNodeUndoAndDetectsExternalChanges() {
        CanvasItem source = text("Original");
        var first = edits.edit(owner.userId(), project.id(), display(source, "One"));
        var second = edits.edit(owner.userId(), project.id(), display(item(source.id()), "Two"));
        edits.replay(owner.userId(), project.id(), second.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        edits.replay(owner.userId(), project.id(), first.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        assertThat(item(source.id()).title()).isEqualTo("Original");
        edits.replay(owner.userId(), project.id(), first.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
        edits.replay(owner.userId(), project.id(), second.receipt().id(), new Replay(UUID.randomUUID(), 1, Direction.REDO));
        assertThat(item(source.id()).title()).isEqualTo("Two");
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.UpdateTitle(source.id(), item(source.id()).version(), "External")));
        long sequence = events.replayWindow(owner.userId(), project.id()).latestSequence();
        assertConflict(() -> edits.replay(owner.userId(), project.id(), second.receipt().id(), new Replay(UUID.randomUUID(), 2, Direction.UNDO)));
        assertThat(events.replayWindow(owner.userId(), project.id()).latestSequence()).isEqualTo(sequence);
        assertThat(item(source.id()).title()).isEqualTo("External");
    }
    @Test void staleSourceInAtomicCopyBatchLeavesNoTargetsOrTextArtifacts() {
        CanvasItem one = text("One"), two = text("Two"); long count = artifactCount();
        Copy valid = copy(one, UUID.randomUUID()); Copy stale = new Copy(two.id(), UUID.randomUUID(), 99,
                null, currentVersion(two), BigDecimal.ZERO, BigDecimal.ZERO, two.width(), two.height(), 0, null);
        assertConflict(() -> edits.edit(owner.userId(), project.id(), new Edit(UUID.randomUUID(), Operation.DUPLICATE,
                List.of(), List.of(), List.of(valid, stale))));
        assertThat(artifactCount()).isEqualTo(count); assertThat(edits.get(owner.userId(), project.id()).items()).hasSize(2);
        assertThat(receiptCount()).isZero();
    }
    @Test void eventFailureRollsBackCopiedTextGroupReceiptAndSequence() {
        CanvasItem source = text("One"); long beforeCount = artifactCount();
        long sequence = events.replayWindow(owner.userId(), project.id()).latestSequence();
        UUID group = UUID.randomUUID(), target = UUID.randomUUID();
        Copy copy = copy(source, target);
        Edit request = new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, group, 0, "Rollback", false)),
                List.of(new Copy(copy.sourceItemId(), copy.targetItemId(), copy.expectedSourceVersion(), null, copy.expectedSourceContentVersion(),
                        copy.x(), copy.y(), copy.width(), copy.height(), copy.zIndex(), group)));
        jdbc.sql("CREATE FUNCTION canvas_edit_test_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type='canvas.edited' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END; $$").update();
        jdbc.sql("CREATE TRIGGER canvas_edit_test_event_failure BEFORE INSERT ON project_event FOR EACH ROW EXECUTE FUNCTION canvas_edit_test_reject_event()").update();
        try { assertThatThrownBy(() -> edits.edit(owner.userId(), project.id(), request)).isInstanceOf(org.jooq.exception.DataAccessException.class); }
        finally {
            jdbc.sql("DROP TRIGGER canvas_edit_test_event_failure ON project_event").update();
            jdbc.sql("DROP FUNCTION canvas_edit_test_reject_event()").update();
        }
        assertThat(events.replayWindow(owner.userId(), project.id()).latestSequence()).isEqualTo(sequence);
        assertThat(artifactCount()).isEqualTo(beforeCount); assertThat(receiptCount()).isZero();
        assertThat(edits.get(owner.userId(), project.id()).groups()).isEmpty();
        assertThat(edits.edit(owner.userId(), project.id(), request).items()).hasSize(2);
    }
    @Test void competingCasCommandsHaveExactlyOneWinner() throws Exception {
        CanvasItem source = text("Original"); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return tryEdit(display(source, "One")); });
            var second = pool.submit(() -> { start.await(); return tryEdit(display(source, "Two")); });
            start.countDown(); assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(item(source.id()).version()).isEqualTo(1); assertThat(receiptCount()).isEqualTo(1);
    }
    @Test void httpRequiresAuthenticationCsrfAndOwnershipAndMatchesReceiptShape() throws Exception {
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id() + "/canvas";
        CanvasItem source = text("Original"); String json = mapper.writeValueAsString(display(source, "Named"));
        mvc.perform(get(base + "/editor")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/edits").with(auth).contentType("application/json").content(json)).andExpect(status().isForbidden());
        var response = mapper.readTree(mvc.perform(post(base + "/edits").with(auth).with(csrf()).contentType("application/json").content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("receipt").path("operation").asText()).isEqualTo("DISPLAY");
        assertThat(response.path("receipt").path("state").asText()).isEqualTo("APPLIED");
        assertThat(response.path("receipt").path("revision").asInt()).isZero();
        assertThat(response.path("groups").isArray()).isTrue(); assertThat(response.path("items").size()).isEqualTo(1);
        var outsider = authentication(new UsernamePasswordAuthenticationToken(new AdminPrincipal(UUID.randomUUID(), "outsider"), null, List.of()));
        mvc.perform(get(base + "/editor").with(outsider)).andExpect(status().isNotFound());
        mvc.perform(post(base + "/edits").with(outsider).with(csrf()).contentType("application/json").content(json)).andExpect(status().isNotFound());
    }
    @Test void expiredOriginalCopyCommandCannotResurrectAnUndoneBranch() {
        CanvasItem source = text("Original"); UUID target = UUID.randomUUID(); Edit request = copyRequest(source, target);
        var copy = edits.edit(owner.userId(), project.id(), request);
        edits.replay(owner.userId(), project.id(), copy.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO));
        for(int i=0;i<100;i++) edits.edit(owner.userId(), project.id(), display(item(source.id()), "Step "+i));
        long artifactsBefore = artifactCount();
        assertThatThrownBy(() -> edits.edit(owner.userId(), project.id(), request)).isInstanceOfSatisfying(ApiProblemException.class,
                failure -> assertThat(failure.code()).isEqualTo("CANVAS_EDIT_EXPIRED"));
        assertThat(edits.get(owner.userId(), project.id()).items()).hasSize(1);
        assertThat(artifactCount()).isEqualTo(artifactsBefore);
        assertThat(jdbc.sql("select count(*) from canvas_edit_command where project_id=:id and command_id=:command")
                .param("id", project.id()).param("command", request.commandId()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void projectArchiveAndExpiredReceiptDoNotChangeCanvas() {
        CanvasItem source = text("Original");
        var first = edits.edit(owner.userId(), project.id(), display(source, "0"));
        for (int i=1; i<=100; i++) edits.edit(owner.userId(), project.id(), display(item(source.id()), Integer.toString(i)));
        assertThat(receiptCount()).isEqualTo(100);
        assertConflict(() -> edits.replay(owner.userId(), project.id(), first.receipt().id(), new Replay(UUID.randomUUID(), 0, Direction.UNDO)));
        projects.archive(owner.userId(), project.id(), projects.get(owner.userId(), project.id()).version());
        assertThatThrownBy(() -> edits.edit(owner.userId(), project.id(), display(item(source.id()), "Archived"))).isInstanceOf(ApiProblemException.class);
        assertThat(item(source.id()).title()).isEqualTo("100");
    }
    private CanvasItem text(String body) {
        var artifact = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, body,
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", body)); UUID id = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(id, artifact.artifact().id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("300"), new BigDecimal("200"), 0, null, false)));
        return item(id);
    }
    private CanvasItem item(UUID id) { return canvas.list(owner.userId(), project.id()).stream().map(CanvasService.CanvasEntry::item).filter(value -> value.id().equals(id)).findFirst().orElseThrow(); }
    private ItemChange change(CanvasItem item, String title, UUID group) { return new ItemChange(item.id(), item.version(), title,
            item.x(), item.y(), item.width(), item.height(), item.zIndex(), group, item.locked()); }
    private Edit display(CanvasItem item, String title) { return new Edit(UUID.randomUUID(), Operation.DISPLAY, List.of(change(item, title, item.groupId())), List.of(), List.of()); }
    private UUID currentVersion(CanvasItem item) { return artifacts.get(owner.userId(), project.id(), item.subjectId()).resourceDefaultVersion().id(); }
    private Copy copy(CanvasItem source, UUID target) { return new Copy(source.id(), target, source.version(), null, currentVersion(source),
            source.x().add(new BigDecimal("40")), source.y().add(new BigDecimal("40")), source.width(), source.height(), source.zIndex(), null); }
    private Edit copyRequest(CanvasItem source, UUID target) { return new Edit(UUID.randomUUID(), Operation.DUPLICATE, List.of(), List.of(), List.of(copy(source, target))); }
    private boolean tryEdit(Edit edit) { try { edits.edit(owner.userId(), project.id(), edit); return true; } catch (ApiProblemException conflict) { assertThat(conflict.code()).isEqualTo("CANVAS_EDIT_CONFLICT"); return false; } }
    private void assertConflict(Runnable command) { assertThatThrownBy(command::run).isInstanceOf(ApiProblemException.class); }
    private long artifactCount() { return jdbc.sql("select count(*) from artifact where project_id=:id").param("id", project.id()).query(Long.class).single(); }
    private long receiptCount() { return jdbc.sql("select count(*) from canvas_edit_receipt where project_id=:id").param("id", project.id()).query(Long.class).single(); }
}

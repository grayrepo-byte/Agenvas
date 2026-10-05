package dev.agenvas.canvas;

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
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import tools.jackson.databind.node.ObjectNode;

/** Mock media through real PostgreSQL: node histories, selection CAS and late-result protection. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class CanvasMediaVersionsPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private MediaDraftService drafts;
    @Autowired private CanvasService canvas;
    @Autowired private CanvasConnectionService connections;
    @Autowired private DirectMediaTaskService direct;
    @Autowired private TaskService tasks;
    @Autowired private MediaExecutionWorker worker;
    @Autowired private AssetService assets;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private WebApplicationContext context;
    private AdminPrincipal owner;
    private Project project;

    @BeforeEach
    void setup() {
        owner = jdbc.sql("select id from app_user where login_name = 'media-version-admin'")
                .query(UUID.class).optional().map(id -> new AdminPrincipal(id, "media-version-admin"))
                .orElseGet(() -> identities.setup("media-version-admin",
                        "media-version-password-123"));
        project = projects.create(owner.userId(), "Node versions", Project.AspectRatio.LANDSCAPE_16_9);
    }

    private UUID create(Artifact.Kind kind) {
        var artifact = artifacts.create(owner.userId(), project.id(), kind, kind.name(), null);
        UUID card = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifact.artifact().id());
        save(card, "First generation", kind == Artifact.Kind.VIDEO);
        return card;
    }

    private MediaDraft save(UUID card, String prompt, boolean video) {
        var draft = drafts.get(owner.userId(), project.id(), card);
        return drafts.save(owner.userId(), project.id(), card, draft.version(), prompt,
                mapper.createObjectNode(), video ? 1 : null, null,
                video ? MediaDraft.VideoInputMode.TEXT : null, List.of(), List.of(), null);
    }

    private CanvasService.CanvasEntry card(UUID id) {
        return canvas.list(owner.userId(), project.id()).stream()
                .filter(entry -> entry.item().id().equals(id)).findFirst().orElseThrow();
    }

    private Task run(UUID card) {
        return direct.run(owner.userId(), project.id(), card(card).item().subjectId(), card,
                drafts.get(owner.userId(), project.id(), card).version(), UUID.randomUUID().toString());
    }

    private UUID generate(UUID card) {
        Task task = run(card);
        assertThat(task.input().path("canvasItemId").asText()).isEqualTo(card.toString());
        assertThat(worker.submitOnce("media-version-worker")).isEqualTo(1);
        Task done = tasks.get(owner.userId(), project.id(), task.id());
        assertThat(done.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(done.output().path("selected").asBoolean()).isTrue();
        return UUID.fromString(done.output().path("artifactVersionId").asText());
    }

    @ParameterizedTest
    @EnumSource(value = Artifact.Kind.class, names = {"IMAGE", "VIDEO"})
    void regenerationKeepsTwoVersionsInTheSameNodeAndAllowsSelectingTheOldResult(Artifact.Kind kind) {
        UUID card = create(kind);
        UUID first = generate(card);
        UUID second = generate(card);
        assertThat(canvas.list(owner.userId(), project.id())).hasSize(1);
        assertThat(connections.list(owner.userId(), project.id())).isEmpty();
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), card))
                .extracting(version -> version.id()).containsExactly(second, first);
        long draftVersion = drafts.get(owner.userId(), project.id(), card).version();
        var selected = canvas.selectMediaVersion(owner.userId(), project.id(), card, first,
                card(card).item().version());
        assertThat(selected.item().selectedVersionId()).isEqualTo(first);
        assertThat(drafts.get(owner.userId(), project.id(), card).version()).isEqualTo(draftVersion);
        assertThat(artifacts.get(owner.userId(), project.id(), selected.item().subjectId())
                .artifact().resourceDefaultVersionId()).isNull();
        assertThat(jdbc.sql("select count(*) from canvas_item_media_version where canvas_item_id = :id")
                .param("id", card).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void emptyNodeCanGenerateAfterAnotherNodeChangesTheSharedResourceDefault() {
        UUID emptyNode = create(Artifact.Kind.IMAGE);
        UUID artifactId = card(emptyNode).item().subjectId();
        UUID sibling = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), artifactId);
        save(sibling, "Independent sibling result", false);
        UUID siblingVersion = generate(sibling);
        var before = artifacts.get(owner.userId(), project.id(), artifactId).artifact();
        var sharedDefault = artifacts.setResourceDefaultVersion(owner.userId(), project.id(),
                artifactId, siblingVersion, before.version()).artifact();
        assertThat(sharedDefault.version()).isPositive();
        assertThat(sharedDefault.resourceDefaultVersionId()).isEqualTo(siblingVersion);
        assertThat(card(emptyNode).item().selectedVersionId()).isNull();
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), emptyNode)).isEmpty();

        Task accepted = run(emptyNode);

        assertThat(accepted.status()).isEqualTo(Task.Status.READY);
        assertThat(accepted.input().path("parentVersionId").isNull()).isTrue();
        assertThat(jdbc.sql("select expected_current_version_id from task_artifact_target where task_id=:id")
                .param("id", accepted.id()).query(UUID.class).optional()).isEmpty();
        assertThat(jdbc.sql("select expected_artifact_version from task_artifact_target where task_id=:id")
                .param("id", accepted.id()).query(Long.class).single()).isEqualTo(sharedDefault.version());
        assertThat(card(emptyNode).item().selectedVersionId()).isNull();
        assertThat(card(sibling).item().selectedVersionId()).isEqualTo(siblingVersion);

        // Finish the accepted task so this class's global claim queue stays isolated between tests.
        assertThat(worker.submitOnce("empty-node-worker")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), project.id(), accepted.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(completed.output().path("selected").asBoolean()).isTrue();
        assertThat(card(emptyNode).item().selectedVersionId()).isNotNull();
        assertThat(card(sibling).item().selectedVersionId()).isEqualTo(siblingVersion);
    }

    @Test
    void versionApiRejectsForeignResultsStaleCasAndUnauthenticatedWrites() throws Exception {
        UUID card = create(Artifact.Kind.IMAGE);
        UUID first = generate(card);
        UUID sibling = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), card(card).item().subjectId());
        save(sibling, "Independent generation", false);
        UUID foreignResult = generate(sibling);
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        String base = "/api/v1/projects/" + project.id() + "/canvas/items/" + card;
        mvc.perform(get(base + "/media-versions")).andExpect(status().isUnauthorized());
        var history = mapper.readTree(mvc.perform(get(base + "/media-versions").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(history.path("items").size()).isEqualTo(1);
        assertThat(history.at("/items/0/id").asText()).isEqualTo(first.toString());
        String request = "{\"versionId\":\"" + first + "\",\"expectedVersion\":1}";
        mvc.perform(post(base + "/select-media-version").with(auth).contentType("application/json")
                .content(request)).andExpect(status().isForbidden());
        mvc.perform(post(base + "/select-media-version").with(auth).with(csrf())
                .contentType("application/json").content(request)).andExpect(status().isOk());
        mvc.perform(post(base + "/select-media-version").with(auth).with(csrf())
                .contentType("application/json").content(request)).andExpect(status().isConflict());
        mvc.perform(post(base + "/select-media-version").with(auth).with(csrf())
                .contentType("application/json").content("{\"versionId\":\"" + foreignResult
                        + "\",\"expectedVersion\":2}")).andExpect(status().isBadRequest());
        mvc.perform(get(base + "/media-versions").with(authentication(
                new UsernamePasswordAuthenticationToken(new AdminPrincipal(UUID.randomUUID(), "foreign"),
                        null, List.of())))).andExpect(status().isNotFound());
        assertThat(card(sibling).item().selectedVersionId()).isEqualTo(foreignResult);
    }

    @Test
    void switchingAwayAndBackDuringGenerationKeepsTheLateResultInNodeHistory() {
        UUID card = create(Artifact.Kind.IMAGE);
        UUID first = generate(card);
        UUID second = generate(card);
        Task pending = run(card);
        Task lease = tasks.claimBoundMedia("late-version-worker", 1).getFirst();
        tasks.beginSubmission(lease, "late-version-worker");
        canvas.selectMediaVersion(owner.userId(), project.id(), card, first, card(card).item().version());
        canvas.selectMediaVersion(owner.userId(), project.id(), card, second, card(card).item().version());
        var late = finish(lease, "late-version-worker");
        assertThat(late.selected()).isFalse();
        assertThat(card(card).item().selectedVersionId()).isEqualTo(second);
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), card))
                .extracting(version -> version.id()).contains(first, second, late.versionId());
        assertThat(tasks.get(owner.userId(), project.id(), pending.id()).output().path("selected").asBoolean())
                .isFalse();
    }

    @Test
    void draftChangesProtectSelectionWhileLayoutChangesDoNot() {
        UUID card = create(Artifact.Kind.IMAGE);
        UUID first = generate(card);
        run(card);
        Task lease = tasks.claimBoundMedia("draft-change-worker", 1).getFirst();
        tasks.beginSubmission(lease, "draft-change-worker");
        save(card, "New user prompt", false);
        var late = finish(lease, "draft-change-worker");
        assertThat(late.selected()).isFalse();
        assertThat(card(card).item().selectedVersionId()).isEqualTo(first);
        assertThat(drafts.get(owner.userId(), project.id(), card).prompt()).isEqualTo("New user prompt");
        run(card);
        Task movingLease = tasks.claimBoundMedia("layout-change-worker", 1).getFirst();
        tasks.beginSubmission(movingLease, "layout-change-worker");
        var item = card(card).item();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.UpdateLayout(card,
                item.version(), new BigDecimal("100"), BigDecimal.ZERO, item.width(), item.height(),
                item.zIndex(), item.groupId())));
        assertThat(finish(movingLease, "layout-change-worker").selected()).isTrue();
    }

    @Test
    void existingInputsStayPinnedAndCopiedNodesStartWithOnlyTheirSelectedVersion() {
        UUID source = create(Artifact.Kind.IMAGE);
        UUID first = generate(source);
        UUID target = create(Artifact.Kind.IMAGE);
        connections.connect(owner.userId(), project.id(), source, target, first,
                CanvasConnection.RelationType.MEDIA_INPUT, drafts.get(owner.userId(), project.id(), target).version());
        UUID second = generate(source);
        assertThat(drafts.get(owner.userId(), project.id(), target).mediaInputs().getFirst().versionId())
                .isEqualTo(first);
        assertThat(connections.list(owner.userId(), project.id()).getFirst().sourceArtifactVersionId())
                .isEqualTo(first);
        UUID copy = UUID.randomUUID();
        var item = card(source).item();
        canvas.duplicate(owner.userId(), project.id(), source, copy, item.version(),
                drafts.get(owner.userId(), project.id(), source).version(), new BigDecimal("600"),
                BigDecimal.ZERO, item.width(), item.height(), 1);
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), copy))
                .extracting(version -> version.id()).containsExactly(second);
        assertThatThrownBy(() -> canvas.selectMediaVersion(owner.userId(), project.id(), copy,
                first, 0)).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void failedAttemptsDoNotCreateVersionsAndRemovedNodesLeaveArtifactHistory() {
        UUID card = create(Artifact.Kind.IMAGE);
        UUID first = generate(card);
        Task canceled = run(card);
        direct.cancelQueued(owner.userId(), project.id(), canceled.id());
        assertThat(canvas.listMediaVersions(owner.userId(), project.id(), card)).hasSize(1);
        Task pending = run(card);
        Task lease = tasks.claimBoundMedia("removed-node-worker", 1).getFirst();
        tasks.beginSubmission(lease, "removed-node-worker");
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(card, card(card).item().version())));
        var late = finish(lease, "removed-node-worker");
        assertThat(late.selected()).isFalse();
        assertThat(canvas.list(owner.userId(), project.id())).isEmpty();
        assertThat(artifacts.listVersions(owner.userId(), project.id(), UUID.fromString(
                pending.input().path("artifactId").asText())))
                .extracting(version -> version.id()).contains(first, late.versionId());
    }

    private ArtifactService.TaskVersionResult finish(Task lease, String worker) {
        ObjectNode content = mapper.createObjectNode();
        content.put("sourceTaskId", lease.id().toString());
        content.put("prompt", "Mock late result");

        content.put("workflowVersion", "mock-v1");
        content.putObject("parameters");
        content.put("assetId", ImageAssetFixture.archive(assets, owner.userId(), project.id()).toString());
        return tasks.succeedWithArtifact(lease, worker, content);
    }
}

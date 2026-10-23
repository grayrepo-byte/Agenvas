package dev.agenvas.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasItemQueryService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentImageInputService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.TaskOutputReadAccessService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ai.chat.messages.UserMessage;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and archived synthetic pixels; no model or external Provider is called. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class AgentStoryboardMediaReadAccessPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE = privateStorage();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", () -> STORAGE.toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasItemQueryService items;
    @Autowired StoryboardService boards;
    @Autowired ProjectEventService events;
    @Autowired ReadToolService reader;
    @Autowired AgentImageInputService images;
    @Autowired TaskOutputReadAccessService taskOutputs;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    private AdminPrincipal owner;
    private Project project;

    @BeforeEach void setup() {
        owner = jdbc.sql("select id from app_user where login_name='synthetic-shot-read-admin'").query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "synthetic-shot-read-admin"))
                .orElseGet(() -> identities.setup("synthetic-shot-read-admin", "synthetic-shot-read-password-123"));
        project = projects.create(owner.userId(), "Synthetic storyboard read project", Project.AspectRatio.LANDSCAPE_16_9);
    }

    @Test void sameRunCanReadAndHydrateExactPrimaryAndAdditionalArchivesWithoutSelectingTheCanvas() {
        UUID run = run(project);
        Media primary = media(project, run, UUID.randomUUID());
        seedTask(primary, true, output(primary));
        Media extra = media(project, run, primary.task());
        var archived = output(primary);
        archived.putArray("additionalResults").add(output(extra).put("canvasItemId", extra.node().toString()));
        taskOutput(primary.task(), archived);

        assertThat(read(run, primary.version()).id()).isEqualTo(primary.version());
        assertThat(read(run, extra.version()).id()).isEqualTo(extra.version());
        denied(() -> artifacts.requireAgentReadableVersion(owner.userId(), project.id(), snapshot(), primary.version()));
        UUID otherRun = run(project);
        denied(() -> read(otherRun, primary.version()));
        var domainRun = runs.get(owner.userId(), project.id(), run);
        JsonNode receipt = reader.artifacts(new TrustedToolContext(owner.userId(), project.id(), run), domainRun,
                UUID.randomUUID(), mapper.createObjectNode().set("versionIds", mapper.createArrayNode().add(primary.version().toString())).toString());
        assertThat(receipt.at("/data/0/imagePreviewRequested").asBoolean()).isTrue();
        assertThat(receipt.at("/data/0").has("expectedVersion")).isFalse();
        var input = new AgentImageInputService.Input(primary.artifact(), primary.version());
        images.validateInputs(owner.userId(), project.id(), run, snapshot(), List.of(input));
        var message = UserMessage.builder().text("Synthetic exact shot image read")
                .metadata(Map.of(AgentImageInputService.METADATA_KEY, List.of(input))).build();
        var hydrated = images.hydrate(owner.userId(), project.id(), run, snapshot(), List.of(message));
        assertThat(((UserMessage) hydrated.getFirst()).getMedia()).hasSize(1);
        assertThat(items.requireArtifactItem(owner.userId(), project.id(), primary.node()).selectedVersionId()).isNull();
        assertThat(items.requireArtifactItem(owner.userId(), project.id(), extra.node()).selectedVersionId()).isNull();
    }

    @Test void onlySavedUserMediaChoicesGrantOtherRunsAccessAndDetachedNodesRevokeIt() {
        UUID run = run(project), otherRun = run(project), shotId = UUID.randomUUID();
        Media media = media(project, run, UUID.randomUUID());
        seedTask(media, true, output(media));
        var referenceOnly = shot(shotId, List.of(media.version()), List.of());
        boards.save(owner.userId(), project.id(), new StoryboardService.Save(0, List.of(referenceOnly), null));
        denied(() -> read(otherRun, media.version()));
        var selected = shot(shotId, List.of(media.version()), List.of(new StoryboardService.ProductionInput(
                Storyboard.Role.KEYFRAME, media.node(), media.version(), false)));
        boards.save(owner.userId(), project.id(), new StoryboardService.Save(1, List.of(selected), null));
        assertThat(read(otherRun, media.version()).id()).isEqualTo(media.version());
        assertThat(items.requireArtifactItem(owner.userId(), project.id(), media.node()).selectedVersionId()).isNull();
        var item = items.requireArtifactItem(owner.userId(), project.id(), media.node());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(item.id(), item.version())));
        denied(() -> read(otherRun, media.version()));
        denied(() -> read(run, media.version()));
    }

    @Test void fabricatedHintsWrongScopeUnapprovedAndUnarchivedVersionsCannotGrantAccess() {
        UUID run = run(project), otherRun = run(project);
        Media allowed = media(project, run, UUID.randomUUID());
        seedTask(allowed, true, output(allowed));
        var fabricated = snapshot().put("runId", run.toString()).put("sourceTaskId", allowed.task().toString())
                .put("approved", true).put("artifactVersionId", allowed.version().toString());
        denied(() -> artifacts.requireAgentReadableVersion(owner.userId(), project.id(), fabricated, otherRun, allowed.version()));
        Project foreign = projects.create(owner.userId(), "Synthetic other project", Project.AspectRatio.SQUARE_1_1);
        denied(() -> artifacts.requireAgentReadableVersion(owner.userId(), foreign.id(), snapshot(), run, allowed.version()));
        denied(() -> artifacts.requireAgentReadableVersion(UUID.randomUUID(), project.id(), snapshot(), run, allowed.version()));

        Media noArchive = media(project, run, UUID.randomUUID());
        seedTask(noArchive, true, null);
        denied(() -> read(run, noArchive.version()));
        Media unlinked = media(project, run, UUID.randomUUID());
        seedTask(unlinked, false, output(unlinked));
        denied(() -> read(run, unlinked.version()));
        jdbc.sql("update task set input_json=input_json-'agentApprovalId' where id=:id").param("id", allowed.task()).update();
        denied(() -> read(run, allowed.version()));

        // A TASK body naming another task cannot borrow an unrelated output ledger entry.
        Media forgedSource = media(project, run, UUID.randomUUID());
        seedTask(new Media(forgedSource.project(), forgedSource.run(), UUID.randomUUID(), forgedSource.artifact(),
                forgedSource.node(), forgedSource.version()), true, output(forgedSource));
        denied(() -> read(run, forgedSource.version()));

        // Even a trusted task output cannot turn a USER-authored version into a same-Run TASK result.
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var user = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Synthetic user upload",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        UUID userNode = CanvasMediaFixture.place(canvas, owner.userId(), project.id(), user.artifact().id());
        jdbc.sql("update canvas_item set selected_version_id=null where id=:id").param("id", userNode).update();
        Media userVersion = new Media(project.id(), run, UUID.randomUUID(), user.artifact().id(), userNode, user.resourceDefaultVersion().id());
        seedTask(userVersion, true, output(userVersion));
        denied(() -> read(run, userVersion.version()));
    }

    @Test void exactCanceledLateArchivesUseOwnedTaskHistoryWithoutGrantingOtherRunsReadAccess() {
        UUID run = run(project), otherRun = run(project);
        Media late = media(project, run, UUID.randomUUID());
        seedTask(late, true, null);
        jdbc.sql("update task set status='CANCELED',cancel_requested=true where id=:id").param("id", late.task()).update();
        jdbc.sql("""
                insert into task_late_result(id,project_id,task_id,lease_epoch,output_json,created_at)
                values (:id,:project,:task,0,cast(:output as jsonb),now())
                """).param("id", UUID.randomUUID()).param("project", project.id()).param("task", late.task())
                .param("output", output(late).toString()).update();
        assertThat(read(run, late.version()).id()).isEqualTo(late.version());
        denied(() -> read(otherRun, late.version()));
        assertThat(taskOutputs.archivedOutputs(owner.userId(), project.id(), late.task())).hasSize(1);
        assertThat(taskOutputs.archivedOutputs(UUID.randomUUID(), project.id(), late.task())).isEmpty();
        Project foreign = projects.create(owner.userId(), "Synthetic foreign late project", Project.AspectRatio.SQUARE_1_1);
        assertThat(taskOutputs.archivedOutputs(owner.userId(), foreign.id(), late.task())).isEmpty();
        assertThat(jdbc.sql("select output_json is null from task where id=:id").param("id", late.task())
                .query(Boolean.class).single()).isTrue();
    }

    private UUID run(Project target) {
        var director = agents.ensureDirector(owner.userId(), target.id());
        UUID conversation = UUID.randomUUID(), run = UUID.randomUUID();
        jdbc.sql("""
                insert into agent_conversation(id,project_id,agent_instance_id,title,created_at,updated_at)
                values (:id,:project,:agent,'Synthetic read conversation',now(),now())
                """).param("id", conversation).param("project", target.id()).param("agent", director.id()).update();
        jdbc.sql("""
                insert into agent_run(id,project_id,agent_instance_id,user_id,status,instruction,context_snapshot_json,
                    policy_snapshot_json,profile_version,created_at,updated_at,conversation_id,conversation_turn)
                values (:id,:project,:agent,:owner,'WAITING_TASKS','Synthetic read scope',
                    '{"creativeSkills":[]}', '{"systemPromptVersion":14}',1,now(),now(),:conversation,1)
                """).param("id", run).param("project", target.id()).param("agent", director.id())
                .param("owner", owner.userId()).param("conversation", conversation).update();
        return run;
    }

    private Media media(Project target, UUID run, UUID task) {
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), target.id());
        var artifact = artifacts.create(owner.userId(), target.id(), Artifact.Kind.IMAGE, "Synthetic unselected archive", null).artifact();
        UUID node = CanvasMediaFixture.place(canvas, owner.userId(), target.id(), artifact.id());
        var content = mapper.createObjectNode().put("assetId", asset.toString()).put("prompt", "Synthetic pixels")
                .put("workflowVersion", "synthetic-read-v1").put("sourceTaskId", task.toString());
        content.putObject("parameters");
        var result = events.recordChange(owner.userId(), target.id(), () -> {
            var appended = artifacts.appendTaskVersionWithinChange(owner.userId(), target.id(), artifact.id(), run,
                    null, artifact.version(), content, null, false);
            canvas.recordTaskMediaVersionWithinChange(owner.userId(), target.id(), node, artifact.id(), appended.versionId());
            return ProjectEventService.Change.unchanged(appended);
        }).value();
        return new Media(target.id(), run, task, artifact.id(), node, result.versionId());
    }

    private void seedTask(Media media, boolean linked, JsonNode output) {
        var input = mapper.createObjectNode().put("canvasItemId", media.node().toString())
                .put(Task.APPROVAL_INPUT_PROPERTY, UUID.randomUUID().toString());
        if (linked) input.put(Task.REQUIRE_EXPLICIT_SELECTION_PROPERTY, true);
        jdbc.sql("""
                insert into task(id,project_id,run_id,step_key,kind,status,input_json,output_json,input_hash,attempt_no,
                    next_action_at,created_at,updated_at,completed_at)
                values (:id,:project,:run,:step,'IMAGE_GENERATION','SUCCEEDED',cast(:input as jsonb),
                    cast(:output as jsonb),repeat('0',64),1,now(),now(),now(),now())
                """).param("id", media.task()).param("project", media.project()).param("run", media.run())
                .param("step", "synthetic-read-" + media.task()).param("input", input.toString())
                .param("output", output == null ? null : output.toString()).update();
    }
    private void taskOutput(UUID task, JsonNode output) {
        jdbc.sql("update task set output_json=cast(:output as jsonb) where id=:id")
                .param("output", output.toString()).param("id", task).update();
    }
    private ObjectNode output(Media media) {
        return mapper.createObjectNode().put("artifactId", media.artifact().toString())
                .put("artifactVersionId", media.version().toString()).put("selected", false);
    }
    private StoryboardService.ShotInput shot(UUID id, List<UUID> references, List<StoryboardService.ProductionInput> productions) {
        return new StoryboardService.ShotInput(id, "SHOT-001", "", "Synthetic shot", "Synthetic design", "", BigDecimal.ONE,
                null, references, productions);
    }
    private ObjectNode snapshot() { return mapper.createObjectNode().set("creativeSkills", mapper.createArrayNode()); }
    private dev.agenvas.artifact.domain.ArtifactVersion read(UUID run, UUID version) {
        return artifacts.requireAgentReadableVersion(owner.userId(), project.id(), snapshot(), run, version);
    }
    private void denied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                failure -> assertThat(failure.status().value()).isIn(403, 404));
    }
    private static Path privateStorage() {
        try { return Files.createTempDirectory("agenvas-shot-read-it-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @AfterAll static void removeOnlySyntheticStorage() throws IOException {
        try (var files = Files.walk(STORAGE)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    private record Media(UUID project, UUID run, UUID task, UUID artifact, UUID node, UUID version) {}
}

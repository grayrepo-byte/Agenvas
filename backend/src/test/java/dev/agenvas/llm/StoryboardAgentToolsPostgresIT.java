package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ToolBatchExecutionService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.skill.application.SkillRunService;
import dev.agenvas.storyboard.application.StoryboardService;
import dev.agenvas.storyboard.application.StoryboardService.CompositionInput;
import dev.agenvas.storyboard.application.StoryboardService.ProductionInput;
import dev.agenvas.storyboard.application.StoryboardService.Save;
import dev.agenvas.storyboard.application.StoryboardService.ShotInput;
import dev.agenvas.storyboard.domain.Storyboard;
import dev.agenvas.support.CanvasMediaFixture;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL and a synthetic director/media fixture; no model or paid Provider is contacted. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, StoryboardAgentToolsPostgresIT.FakeConfig.class},
        properties = {"agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class StoryboardAgentToolsPostgresIT {
    private static final String SOURCE_TEXT = "合成剧本：玄关夜，小林右手持钥匙。阿青伸出左手说：把钥匙还给我。小林把钥匙交给阿青。";
    private static final String LONG_DESCRIPTION = "第三镜画面🙂".repeat(100);
    private static final String ORIGINAL_DIALOGUE = "把钥匙还给我。";
    private static final String REVISED_DIALOGUE = "请把钥匙还给我。";
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path storage;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("agenvas.storage.root", () -> storage.toString());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired CanvasService canvas;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner media;
    @Autowired StoryboardService boards;
    @Autowired AgentTurnWorker worker;
    @Autowired AgentTurnCommitService commits;
    @Autowired InitialModelContextService initial;
    @Autowired LlmTurnCheckpointService checkpoints;
    @Autowired LlmProtocolCodec codec;
    @Autowired ToolExecutionService executor;
    @Autowired ToolBatchExecutionService batch;
    @Autowired ToolRegistry registry;
    @Autowired TaskService tasks;
    @Autowired FakeGateway gateway;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    AdminPrincipal owner;
    Project project;

    @BeforeEach void setup() {
        owner = jdbc.sql("select id from app_user where login_name='director-board-admin'").query(UUID.class).optional()
                .map(id -> new AdminPrincipal(id, "director-board-admin"))
                .orElseGet(() -> identities.setup("director-board-admin", "director-board-password-123"));
        project = projects.create(owner.userId(), "Director board", Project.AspectRatio.LANDSCAPE_16_9);
    }

    @Test void directorReadsScriptAppendsEightShotsAndRevisesOnlyTheThirdWithoutRegeneratingResults() throws Exception {
        var source = source(project, true);
        var director = agents.ensureDirector(owner.userId(), project.id());
        var run = runs.create(owner.userId(), project.id(), director.id(),
                "把剧本拆解写进分镜制作台，共八镜，然后仅修改第三镜台词，媒体仍等待人工审批。",
                "synthetic-director-board", director.version(), List.of(source.item()), null, null, null, null, null,
                new SkillRunService.Selection(SkillRunService.SelectionMode.NONE, List.of())).run();
        assertThat(initial.assemble(owner.userId(), project.id(), run.id()).toString()).doesNotContain(SOURCE_TEXT);
        gateway.reset(source.version());
        for (int turn = 0; turn < 4; turn++) assertThat(worker.runOnce("synthetic-director-board-worker")).isEqualTo(1);
        Storyboard appended = boards.get(owner.userId(), project.id());
        assertThat(appended.version()).isEqualTo(2);
        assertThat(appended.shots()).hasSize(8);
        assertThat(appended.shots()).extracting(Storyboard.Shot::id).doesNotHaveDuplicates();
        assertThat(appended.shots()).extracting(Storyboard.Shot::code)
                .containsExactly("SHOT-001", "SHOT-002", "SHOT-003", "SHOT-004", "SHOT-005", "SHOT-006", "SHOT-007", "SHOT-008");
        assertThat(appended.shots()).allSatisfy(shot -> {
            assertThat(shot.revision()).isEqualTo(1);
            assertThat(shot.sourceVersionId()).isEqualTo(source.version());
            assertThat(shot.productions()).isEmpty();
        });

        // Uploaded synthetic bytes stand in for already chosen results. The test never purchases generation.
        List<Target> targets = new ArrayList<>();
        List<ShotInput> bound = new ArrayList<>();
        for (var shot : appended.shots()) {
            var target = image(project);
            targets.add(target);
            bound.add(input(shot, List.of(new ProductionInput(Storyboard.Role.KEYFRAME, target.item(), target.version(), false))));
        }
        var film = video(project);
        Storyboard selected = boards.save(owner.userId(), project.id(), new Save(appended.version(), bound,
                new CompositionInput(film.item(), film.version(), false)));
        // An unchanged historical source may remain pinned after the source leaves the current canvas.
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.Remove(source.item(), 0)));
        for (int turn = 4; turn < 8; turn++) assertThat(worker.runOnce("synthetic-director-board-worker")).isEqualTo(1);

        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.calls.get()).isEqualTo(8);
        Storyboard revised = boards.get(owner.userId(), project.id());
        assertThat(revised.version()).isEqualTo(4);
        assertThat(revised.shots()).hasSize(8);
        for (int index = 0; index < 8; index++) {
            var before = selected.shots().get(index);
            var after = revised.shots().get(index);
            if (index != 2) assertThat(after).isEqualTo(before);
            else {
                assertThat(after.id()).isEqualTo(before.id());
                assertThat(after.code()).isEqualTo(before.code());
                assertThat(after.dialogue()).isEqualTo(REVISED_DIALOGUE);
                assertThat(after.description()).isEqualTo(LONG_DESCRIPTION);
                assertThat(after.sourceVersionId()).isEqualTo(source.version());
                assertThat(after.revision()).isEqualTo(2);
                assertThat(after.productions()).singleElement().satisfies(production -> {
                    assertThat(production.canvasItemId()).isEqualTo(targets.get(2).item());
                    assertThat(production.selectedVersionId()).isEqualTo(targets.get(2).version());
                    assertThat(production.reviewedRevision()).isEqualTo(1);
                    assertThat(production.needsReview()).isTrue();
                });
            }
            assertThat(artifacts.listVersions(owner.userId(), project.id(), targets.get(index).artifact())).hasSize(1);
        }
        assertThat(revised.composition().canvasItemId()).isEqualTo(film.item());
        assertThat(revised.composition().selectedVersionId()).isEqualTo(film.version());
        assertThat(revised.composition().needsReview()).isTrue();
        assertThat(boards.listRevisions(owner.userId(), project.id())).hasSize(4);
        long eventCount = boardEvents();
        var trusted = new TrustedToolContext(owner.userId(), project.id(), run.id());
        var replay = executor.execute(trusted, 6, "board-call-7");
        assertThat(executor.execute(trusted, 6, "board-call-7")).isEqualTo(replay);
        assertThat(replay.at("/data/shots/0/id").asText()).isEqualTo(revised.shots().get(2).id().toString());
        assertThat(boards.listRevisions(owner.userId(), project.id())).hasSize(4);
        assertThat(boardEvents()).isEqualTo(eventCount);
        assertThat(toolCount(run.id())).isEqualTo(7);
        assertNoMediaSubmission();
    }

    @Test void persistedCallsRejectStaleCasForeignOrOffCanvasSourcesAndForgedReviewOrApprovalFields() {
        UUID shotId = UUID.randomUUID();
        Storyboard before = boards.save(owner.userId(), project.id(), new Save(0,
                List.of(new ShotInput(shotId, "SHOT-001", "", "", "Original", "Original", BigDecimal.ONE,
                        null, List.of(), List.of())), null));
        var hidden = source(project, false);
        var foreignProject = projects.create(owner.userId(), "Foreign board", Project.AspectRatio.SQUARE_1_1);
        var foreign = source(foreignProject, true);
        var hiddenImage = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Hidden image", null);
        // A genuine media version that is off canvas must also fail the Agent's input scope check.
        UUID hiddenAsset = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        hiddenImage = artifacts.revise(owner.userId(), project.id(), hiddenImage.artifact().id(), 0, null,
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", hiddenAsset.toString()));
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        calls.add(call("stale", "write_storyboard_shots", patch(0, shotId).put("dialogue", "Stale")));
        calls.add(call("hidden-source", "write_storyboard_shots", patch(1, shotId).put("sourceVersionId", hidden.version().toString())));
        calls.add(call("foreign-source", "write_storyboard_shots", patch(1, shotId).put("sourceVersionId", foreign.version().toString())));
        var hiddenReference = patch(1, shotId);
        hiddenReference.putArray("referenceVersionIds").add(hiddenImage.resourceDefaultVersion().id().toString());
        calls.add(call("hidden-reference", "write_storyboard_shots", hiddenReference));
        for (String forbidden : List.of("productions", "confirmReviewed", "needsReview", "reviewedRevision", "selectedVersionId", "approvalId")) {
            var malicious = patch(1, shotId).put(forbidden, "forged");
            calls.add(call("forged-" + forbidden, "write_storyboard_shots", malicious));
        }
        ObjectNode identity = writeEnvelope(1);
        identity.putArray("shots").addObject().put("shotId", shotId.toString()).put("dialogue", "Forged owner");
        identity.put("ownerId", owner.userId().toString());
        calls.add(rawCall("forged-owner", "write_storyboard_shots", identity));
        var readMixed = mapper.createObjectNode().put("offset", 0);
        readMixed.putArray("shotIds").add(shotId.toString());
        calls.add(rawCall("read-mixed", "read_storyboard", readMixed));
        calls.add(rawCall("read-too-many", "read_storyboard", mapper.createObjectNode().put("limit", 41)));
        var tooMany = writeEnvelope(1);
        for (int index = 0; index < 7; index++) tooMany.withArray("shots").add(newShot(index, null));
        calls.add(rawCall("write-too-many", "write_storyboard_shots", tooMany));
        Leased scenario = checkpoint(calls);
        long eventCount = boardEvents();
        assertCode(() -> execute(scenario, "stale"), "STORYBOARD_VERSION_CONFLICT");
        assertCode(() -> execute(scenario, "hidden-source"), "INPUT_SCOPE_DENIED");
        assertCode(() -> execute(scenario, "foreign-source"), "RESOURCE_NOT_FOUND");
        assertCode(() -> execute(scenario, "hidden-reference"), "INPUT_SCOPE_DENIED");
        for (var call : calls.subList(4, calls.size()))
            assertCode(() -> execute(scenario, call.id()), "TOOL_ARGUMENT_INVALID");
        assertThatThrownBy(() -> executor.execute(new TrustedToolContext(owner.userId(), foreignProject.id(), scenario.run().id()), 0, "stale"))
                .isInstanceOf(ApiProblemException.class);
        assertThat(boards.get(owner.userId(), project.id())).isEqualTo(before);
        assertThat(boards.listRevisions(owner.userId(), project.id())).hasSize(1);
        assertThat(toolCount(scenario.run().id())).isZero();
        assertThat(boardEvents()).isEqualTo(eventCount);
        assertNoMediaSubmission();
    }

    @Test void laterInvalidToolRollsBackEarlierStoryboardRevisionLedgerAndEvent() {
        var valid = writeEnvelope(0);
        valid.withArray("shots").add(newShot(0, null));
        var invalid = writeEnvelope(1);
        invalid.withArray("shots").add(newShot(1, null).put("confirmReviewed", true));
        var calls = List.of(rawCall("first-write", "write_storyboard_shots", valid),
                rawCall("second-invalid", "write_storyboard_shots", invalid));
        Leased scenario = checkpoint(calls);
        assertCode(() -> batch.executeLeased(scenario.context(), 0, calls, scenario.lease(), scenario.worker()), "TOOL_ARGUMENT_INVALID");
        assertThat(boards.get(owner.userId(), project.id()).version()).isZero();
        assertThat(boards.listRevisions(owner.userId(), project.id())).isEmpty();
        assertThat(toolCount(scenario.run().id())).isZero();
        assertThat(boardEvents()).isZero();
        // The original durable response survives rollback and can be inspected or repaired.
        assertThat(jdbc.sql("select status from llm_turn where run_id=:run and step_index=0")
                .param("run", scenario.run().id()).query(String.class).single()).isEqualTo("RESPONDED");
        assertNoMediaSubmission();
    }

    @Test void aFrozenRunWithoutTheNewAllowlistCannotAcquireStoryboardTools() {
        var args = writeEnvelope(0);
        args.withArray("shots").add(newShot(0, null));
        Leased scenario = checkpoint(List.of(rawCall("old-read", "read_storyboard", mapper.createObjectNode()),
                rawCall("old-write", "write_storyboard_shots", args)));
        // Model a Run frozen before these names existed, while retaining its supported protocol version.
        jdbc.sql("update agent_run set policy_snapshot_json=jsonb_set(policy_snapshot_json,'{allowedTools}', "
                + "(policy_snapshot_json->'allowedTools') - 'read_storyboard' - 'write_storyboard_shots') where id=:run")
                .param("run", scenario.run().id()).update();
        var frozen = runs.get(owner.userId(), project.id(), scenario.run().id());
        assertThat(registry.modelDefinitions(frozen.policySnapshot())).extracting(tool -> tool.getToolDefinition().name())
                .doesNotContain("read_storyboard", "write_storyboard_shots");
        assertCode(() -> execute(scenario, "old-read"), "TOOL_ARGUMENT_INVALID");
        assertCode(() -> execute(scenario, "old-write"), "TOOL_ARGUMENT_INVALID");
        assertThat(boards.get(owner.userId(), project.id()).version()).isZero();
        assertThat(toolCount(scenario.run().id())).isZero();
        assertThat(boardEvents()).isZero();
        assertNoMediaSubmission();
    }

    private Leased checkpoint(List<AssistantMessage.ToolCall> calls) {
        var director = agents.ensureDirector(owner.userId(), project.id());
        var run = runs.create(owner.userId(), project.id(), director.id(), "Synthetic board command", UUID.randomUUID().toString()).run();
        String workerId = "board-checkpoint-" + run.id();
        Task lease = tasks.claimAgentTurns(workerId, 1).getFirst();
        assertThat(lease.runId()).isEqualTo(run.id());
        AgentRun running = commits.start(lease, workerId);
        int version = running.policySnapshot().path("modelConfigVersion").asInt();
        checkpoints.reserveLeased(owner.userId(), project.id(), run.id(), 0, version,
                running.policySnapshot().path("modelConfigSource").asText(),
                codec.request(List.of(new UserMessage("Synthetic board command")), registry.modelDefinitions(running.policySnapshot())), lease, workerId);
        tasks.startAgentStream(lease, workerId);
        var response = AssistantMessage.builder().content("").toolCalls(calls).build();
        checkpoints.saveResponseLeased(owner.userId(), project.id(), run.id(), 0, version,
                codec.response(new ChatResponse(List.of(new Generation(response)))), lease, workerId);
        return new Leased(running, lease, workerId, new TrustedToolContext(owner.userId(), project.id(), run.id()));
    }

    private JsonNode execute(Leased scenario, String id) {
        return executor.executeLeased(scenario.context(), 0, id, scenario.lease(), scenario.worker());
    }
    /** Wrap one per-shot patch in the write tool's board-level CAS envelope. */
    private AssistantMessage.ToolCall call(String id, String name, ObjectNode shot) {
        long expected = shot.path("expectedVersion").asLong();
        shot.remove("expectedVersion");
        var args = writeEnvelope(expected);
        args.withArray("shots").add(shot);
        return rawCall(id, name, args);
    }
    private static AssistantMessage.ToolCall rawCall(String id, String name, ObjectNode args) {
        return new AssistantMessage.ToolCall(id, "function", name, args.toString());
    }
    private ObjectNode patch(long expected, UUID shotId) {
        return mapper.createObjectNode().put("expectedVersion", expected).put("shotId", shotId.toString());
    }
    private ObjectNode writeEnvelope(long expected) {
        var args = mapper.createObjectNode().put("expectedVersion", expected);
        args.putArray("shots");
        return args;
    }
    private ObjectNode newShot(int index, UUID source) {
        var shot = mapper.createObjectNode().put("code", "SHOT-%03d".formatted(index + 1))
                .put("scene", "SC-01").put("title", "合成镜头" + (index + 1))
                .put("description", index == 2 ? LONG_DESCRIPTION : "合成画面" + (index + 1))
                .put("dialogue", ORIGINAL_DIALOGUE).put("durationSeconds", 4);
        if (source != null) shot.put("sourceVersionId", source.toString());
        return shot;
    }
    private ShotInput input(Storyboard.Shot shot, List<ProductionInput> productions) {
        return new ShotInput(shot.id(), shot.code(), shot.scene(), shot.title(), shot.description(), shot.dialogue(),
                shot.durationSeconds(), shot.sourceVersionId(), shot.referenceVersionIds(), productions);
    }
    private Source source(Project target, boolean place) {
        var artifact = artifacts.create(owner.userId(), target.id(), Artifact.Kind.TEXT, "合成验收剧本",
                mapper.createObjectNode().put("format", "PLAIN_TEXT").put("text", SOURCE_TEXT));
        return new Source(artifact.resourceDefaultVersion().id(), place
                ? CanvasMediaFixture.place(canvas, owner.userId(), target.id(), artifact.artifact().id()) : null);
    }
    private Target image(Project target) {
        return placeMedia(target, Artifact.Kind.IMAGE, ImageAssetFixture.archive(assets, owner.userId(), target.id()));
    }
    private Target video(Project target) throws Exception {
        var path = Files.createTempFile("director-board-synthetic-", ".mp4");
        try {
            media.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                    "color=c=black:s=32x32:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-y", path.toString()));
            try (var stream = Files.newInputStream(path)) {
                return placeMedia(target, Artifact.Kind.VIDEO, assets.archiveVideo(owner.userId(), target.id(), stream).id());
            }
        } finally { Files.deleteIfExists(path); }
    }
    private Target placeMedia(Project target, Artifact.Kind kind, UUID asset) {
        var artifact = artifacts.create(owner.userId(), target.id(), kind, "Synthetic selected result",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.toString()));
        return new Target(artifact.artifact().id(), CanvasMediaFixture.place(canvas, owner.userId(), target.id(), artifact.artifact().id()),
                artifact.resourceDefaultVersion().id());
    }
    private long boardEvents() {
        return jdbc.sql("select count(*) from project_event where project_id=:project and type='storyboard.changed'")
                .param("project", project.id()).query(Long.class).single();
    }
    private long toolCount(UUID runId) {
        return jdbc.sql("select count(*) from tool_execution where run_id=:run").param("run", runId).query(Long.class).single();
    }
    private void assertNoMediaSubmission() {
        assertThat(jdbc.sql("select count(*) from task where project_id=:project and kind<>'AGENT_TURN'")
                .param("project", project.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from agent_media_approval where project_id=:project")
                .param("project", project.id()).query(Long.class).single()).isZero();
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiProblemException.class, problem -> assertThat(problem.code()).isEqualTo(code));
    }
    private record Source(UUID version, UUID item) {}
    private record Target(UUID artifact, UUID item, UUID version) {}
    private record Leased(AgentRun run, Task lease, String worker, TrustedToolContext context) {}

    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway(ObjectMapper mapper) { return new FakeGateway(mapper); }
    }

    /** Subsequent writes use only identities and versions returned by committed tool receipts. */
    static class FakeGateway implements ChatGateway {
        private final ObjectMapper mapper;
        final AtomicInteger calls = new AtomicInteger();
        private UUID sourceVersion;
        private UUID thirdShot;
        private long boardVersion;
        FakeGateway(ObjectMapper mapper) { this.mapper = mapper; }
        void reset(UUID sourceVersion) { this.sourceVersion = sourceVersion; calls.set(0); }
        @Override public String configSource() { return "test-synthetic-director-board"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> toolContext) {
            assertThat(tools).extracting(tool -> tool.getToolDefinition().name())
                    .contains("read_canvas", "read_artifacts", "read_storyboard", "write_storyboard_shots", "propose_media_generation");
            int turn = calls.incrementAndGet();
            return switch (turn) {
                case 1 -> tool(turn, "read_canvas", mapper.createObjectNode());
                case 2 -> {
                    assertThat(reply(messages).at("/data/0/selectedVersionId").asText()).isEqualTo(sourceVersion.toString());
                    var read = mapper.createObjectNode();
                    read.putArray("versionIds").add(sourceVersion.toString());
                    yield tool(turn, "read_artifacts", read);
                }
                case 3 -> {
                    assertThat(reply(messages).at("/data/0/content/text").asText()).isEqualTo(SOURCE_TEXT);
                    yield append(turn, 0, 0, 6);
                }
                case 4 -> {
                    JsonNode saved = reply(messages).path("data");
                    assertThat(saved.path("version").asLong()).isEqualTo(1);
                    assertThat(saved.path("shots")).hasSize(6);
                    thirdShot = UUID.fromString(saved.at("/shots/2/id").asText());
                    yield append(turn, saved.path("version").asLong(), 6, 8);
                }
                case 5 -> {
                    JsonNode saved = reply(messages).path("data");
                    assertThat(saved.path("version").asLong()).isEqualTo(2);
                    assertThat(saved.path("total").asInt()).isEqualTo(8);
                    assertThat(saved.path("shots")).hasSize(2);
                    yield tool(turn, "read_storyboard", mapper.createObjectNode());
                }
                case 6 -> {
                    JsonNode directory = reply(messages).path("data");
                    assertThat(directory.path("version").asLong()).isEqualTo(3);
                    assertThat(directory.path("total").asInt()).isEqualTo(8);
                    assertThat(directory.path("shots")).hasSize(8);
                    assertThat(directory.path("nextOffset").isNull()).isTrue();
                    var third = directory.path("shots").get(2);
                    assertThat(third.path("id").asText()).isEqualTo(thirdShot.toString());
                    assertThat(third.has("description")).isFalse();
                    assertThat(third.path("descriptionTruncated").asBoolean()).isTrue();
                    String preview = third.path("descriptionPreview").asText();
                    assertThat(preview.codePointCount(0, preview.length())).isEqualTo(256);
                    assertThat(third.path("descriptionCharacters").asInt()).isEqualTo(LONG_DESCRIPTION.codePointCount(0, LONG_DESCRIPTION.length()));
                    var read = mapper.createObjectNode();
                    read.putArray("shotIds").add(thirdShot.toString());
                    yield tool(turn, "read_storyboard", read);
                }
                case 7 -> {
                    JsonNode detail = reply(messages).path("data");
                    boardVersion = detail.path("version").asLong();
                    assertThat(detail.path("shots")).hasSize(1);
                    assertThat(detail.at("/shots/0/description").asText()).isEqualTo(LONG_DESCRIPTION);
                    assertThat(detail.at("/shots/0/dialogue").asText()).isEqualTo(ORIGINAL_DIALOGUE);
                    assertThat(detail.at("/shots/0/sourceVersionId").asText()).isEqualTo(sourceVersion.toString());
                    assertThat(detail.toString()).doesNotContain(SOURCE_TEXT, "assetUrl", "downloadUrl");
                    var patch = mapper.createObjectNode().put("expectedVersion", boardVersion);
                    patch.putArray("shots").addObject().put("shotId", thirdShot.toString()).put("dialogue", REVISED_DIALOGUE);
                    yield tool(turn, "write_storyboard_shots", patch);
                }
                case 8 -> {
                    JsonNode receipt = reply(messages).path("data");
                    assertThat(receipt.path("version").asLong()).isEqualTo(boardVersion + 1);
                    assertThat(receipt.path("total").asInt()).isEqualTo(8);
                    assertThat(receipt.path("shots")).hasSize(1);
                    assertThat(receipt.at("/shots/0/id").asText()).isEqualTo(thirdShot.toString());
                    assertThat(receipt.at("/shots/0/revision").asInt()).isEqualTo(2);
                    assertThat(receipt.at("/shots/0/needsReview").asBoolean()).isTrue();
                    assertThat(receipt.path("compositionNeedsReview").asBoolean()).isTrue();
                    yield response(new AssistantMessage("八镜已保存到分镜制作台，仅修订第三镜台词；保留已选媒体并标记待复核，未调用媒体生成。"));
                }
                default -> throw new IllegalStateException("Unexpected synthetic director turn " + turn);
            };
        }
        private Exchange append(int turn, long expected, int from, int to) {
            var args = mapper.createObjectNode().put("expectedVersion", expected);
            var shots = args.putArray("shots");
            for (int index = from; index < to; index++) shots.addObject().put("code", "SHOT-%03d".formatted(index + 1))
                    .put("description", index == 2 ? LONG_DESCRIPTION : "合成画面" + (index + 1))
                    .put("dialogue", ORIGINAL_DIALOGUE).put("durationSeconds", 4)
                    .put("sourceVersionId", sourceVersion.toString());
            return tool(turn, "write_storyboard_shots", args);
        }
        private JsonNode reply(List<Message> messages) {
            var reply = messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .reduce((earlier, later) -> later).orElseThrow();
            return mapper.readTree(reply.getResponses().getLast().responseData());
        }
        private Exchange tool(int turn, String name, ObjectNode args) {
            return response(AssistantMessage.builder().content("").toolCalls(List.of(
                    rawCall("board-call-" + turn, name, args))).build());
        }
        private Exchange response(AssistantMessage message) {
            return new Exchange(1, new ChatResponse(List.of(new Generation(message))));
        }
    }
}

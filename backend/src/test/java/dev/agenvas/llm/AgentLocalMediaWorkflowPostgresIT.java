package dev.agenvas.llm;

import static dev.agenvas.testing.AgentFixture.configureDirector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventRecorded;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentMediaOutcomeService;
import dev.agenvas.llm.application.AgentTurnCommitService;
import dev.agenvas.llm.application.LlmConversationService;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmTurnCheckpointService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.llm.application.ToolRegistry;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.MediaFunctionService;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskRecoveryScheduler;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL/FFmpeg and durable built-in tool turns; saved model responses are synthetic, with no Provider call. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, AgentLocalMediaWorkflowPostgresIT.TestConfig.class}, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false",
        "spring.main.allow-bean-definition-overriding=true"})
class AgentLocalMediaWorkflowPostgresIT {
    private static final String MODEL_WORKER = "local-workflow-model";
    private static final String MEDIA_WORKER = "local-workflow-media";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir static Path storage;
    @TempDir Path temp;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("agenvas.storage.root", () -> storage.toString());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired MediaToolRunner mediaTools;
    @Autowired CanvasService canvas;
    @Autowired AgentInstanceService agents;
    @Autowired AgentRunService runs;
    @Autowired TaskService tasks;
    @Autowired DirectMediaTaskService directTasks;
    @Autowired AgentTurnCommitService commits;
    @Autowired LlmTurnCheckpointService checkpoints;
    @Autowired LlmProtocolCodec codec;
    @Autowired ToolRegistry registry;
    @Autowired ToolExecutionService execution;
    @Autowired AgentMediaOutcomeService outcomes;
    @Autowired LlmConversationService conversation;
    @Autowired ReadToolService reader;
    @Autowired MediaExecutionWorker mediaWorker;
    @Autowired MediaFunctionService functions;
    @Autowired ProjectEventService events;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private static AdminPrincipal owner;
    private final List<Scenario> scenarios = new ArrayList<>();

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("local-workflow-test", "synthetic-workflow-password-123");
        // A configurable UI operation route must not turn the built-in local tool into external generation.
        var extraction = functions.list().stream().filter(setting -> setting.operation() == MediaFunction.VIDEO_EXTRACT_AUDIO)
                .findFirst().orElseThrow();
        if (extraction.capabilityId() != null) functions.update(MediaFunction.VIDEO_EXTRACT_AUDIO, extraction.version(), null);
    }

    @AfterEach void stopRemainingRuns() {
        for (Scenario scenario : scenarios) {
            if (!currentRun(scenario).status().terminal()) runs.cancel(owner.userId(), scenario.project(), scenario.run());
            // Accepted local work is independent of Run cancellation. Do not leak READY work into another test.
            for (UUID taskId : jdbc.sql("select id from task where project_id=:project and run_id is null and status='READY'")
                    .param("project", scenario.project()).query(UUID.class).list()) {
                directTasks.cancelQueued(owner.userId(), scenario.project(), taskId);
            }
        }
    }

    @Test
    void builtinToolsExtractTrimAndComposeWithOneDurableContinuationPerResult() throws Exception {
        Scenario scenario = scenario();
        byte[] originalBytes = Files.readAllBytes(assets.get(owner.userId(), scenario.project(), scenario.sourceAsset()).path());
        JsonNode originalContent = artifacts.requireVersionById(owner.userId(), scenario.project(), scenario.sourceVersion()).content();

        Round extraction = begin(scenario, 0, "extract_audio", sourceRequest(scenario, scenario.sourceItem(), scenario.sourceVersion()));
        JsonNode extractReceipt = execute(extraction);
        assertThat(execute(extraction)).isEqualTo(extractReceipt);
        assertReceipt(extractReceipt);
        Task extractTask = task(scenario, extractReceipt);
        assertThat(extractTask.initiator()).isEqualTo(Task.Initiator.BUILTIN_AGENT);
        assertThat(extractTask.runId()).isNull();
        assertThat(extractTask.approvedMedia()).isFalse();
        assertThat(extractTask.input().at("/agentLocalOrigin/runId").asText()).isEqualTo(scenario.run().toString());
        awaitLocal(extraction);
        assertThatThrownBy(() -> conversation.afterToolRound(owner.userId(), scenario.project(), scenario.run(), 0))
                .isInstanceOf(IllegalStateException.class);
        Task extracted = completeLocal(extraction, extractReceipt);
        assertThat(extracted.output().path("additionalResults")).isNotEmpty();
        extracted.output().path("additionalResults").forEach(result -> assertThat(artifacts.requireVersionById(owner.userId(),
                scenario.project(), UUID.fromString(result.path("artifactVersionId").asText())).runId()).isEqualTo(scenario.run()));
        UUID extractedVersion = outputVersion(extracted);
        UUID extractedItem = UUID.fromString(extractReceipt.at("/data/canvasItemId").asText());
        assertFinalReply(extraction, extracted);

        ObjectNode trim = sourceRequest(scenario, extractedItem, extractedVersion).put("startMs", 250).put("endMs", 1250);
        Round trimming = begin(scenario, 1, "trim_audio", trim);
        JsonNode trimReceipt = execute(trimming);
        assertReceipt(trimReceipt);
        awaitLocal(trimming);
        Task trimmed = completeLocal(trimming, trimReceipt);
        UUID trimmedVersion = outputVersion(trimmed);
        assertThat(trimmedVersion).isNotEqualTo(extractedVersion);
        assertThat(item(scenario, extractedItem).selectedVersionId()).isEqualTo(extractedVersion);
        var audio = artifacts.requireMediaVersionForTask(owner.userId(), scenario.project(), trimmedVersion, Artifact.Kind.AUDIO);
        var audioAsset = assets.metadata(owner.userId(), scenario.project(), UUID.fromString(audio.content().path("assetId").asText()));
        assertThat(audioAsset.durationMs()).isEqualTo(1000);
        Path decoded = temp.resolve("trimmed.pcm");
        mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-i",
                assets.get(owner.userId(), scenario.project(), audioAsset.id()).path().toString(),
                "-f", "s16le", "-ac", "1", "-ar", "8000", "-y", decoded.toString()));
        assertThat(Files.size(decoded)).isEqualTo(16000);
        assertFinalReply(trimming, trimmed);

        ObjectNode composition = mapper.createObjectNode().put("title", "Built-in composed clip")
                .put("rationale", "Use the exact freshly trimmed voice sample").put("originalAudioMode", "MUTE");
        composition.putArray("shots").addObject().put("videoVersionId", scenario.sourceVersion().toString())
                .putArray("lines").addObject().put("audioVersionId", trimmedVersion.toString()).put("startMs", 0);
        Round composing = begin(scenario, 2, "compose_video", composition);
        JsonNode composeReceipt = execute(composing);
        assertReceipt(composeReceipt);
        awaitLocal(composing);
        Task composed = completeLocal(composing, composeReceipt);
        var video = artifacts.requireMediaVersionForTask(owner.userId(), scenario.project(), outputVersion(composed), Artifact.Kind.VIDEO);
        var videoAsset = assets.metadata(owner.userId(), scenario.project(), UUID.fromString(video.content().path("assetId").asText()));
        assertThat(videoAsset.durationMs()).isBetween(1900, 2100);
        assertFinalReply(composing, composed);

        assertThat(item(scenario, scenario.sourceItem()).selectedVersionId()).isEqualTo(scenario.sourceVersion());
        assertThat(artifacts.requireVersionById(owner.userId(), scenario.project(), scenario.sourceVersion()).content()).isEqualTo(originalContent);
        assertThat(Files.readAllBytes(assets.get(owner.userId(), scenario.project(), scenario.sourceAsset()).path())).isEqualTo(originalBytes);
        assertThat(ledgerCount(scenario)).isEqualTo(3);
        assertThat(mediaTaskCount(scenario)).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from agent_media_approval where run_id=:run").param("run", scenario.run()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from usage_ledger u join task t on t.id=u.task_id "
                        + "where u.project_id=:project and t.kind <> 'AGENT_TURN' and u.cost_source <> 'LOCAL_NO_COST'")
                .param("project", scenario.project()).query(Long.class).single()).isZero();
        finish(scenario, 3);
        assertThat(turnCount(scenario)).isEqualTo(4);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test
    void toolTaskNodesAndLedgerRollbackTogetherAndReplayQueuesOnlyOnce() throws Exception {
        Scenario scenario = scenario();
        Round round = begin(scenario, 0, "extract_audio", sourceRequest(scenario, scenario.sourceItem(), scenario.sourceVersion()));
        int originalNodes = canvas.list(owner.userId(), scenario.project()).size();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            assertReceipt(execute(round));
            throw new IllegalStateException("Synthetic rollback after local queue acceptance");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Synthetic rollback");
        assertThat(ledgerCount(scenario)).isZero();
        assertThat(mediaTaskCount(scenario)).isZero();
        assertThat(canvas.list(owner.userId(), scenario.project())).hasSize(originalNodes);
        JsonNode accepted = execute(round);
        assertThat(execute(round)).isEqualTo(accepted);
        assertThat(ledgerCount(scenario)).isEqualTo(1);
        assertThat(mediaTaskCount(scenario)).isEqualTo(1);
        assertThat(canvas.list(owner.userId(), scenario.project())).hasSize(originalNodes + 2);
        awaitLocal(round);
    }

    @Test
    void localTaskReadsStayInOriginatingRunAndProjectAndForgedSourceCreatesNothing() throws Exception {
        Scenario scenario = scenario();
        Round round = begin(scenario, 0, "extract_audio", sourceRequest(scenario, scenario.sourceItem(), scenario.sourceVersion()));
        JsonNode accepted = execute(round);
        String query = mapper.createObjectNode().set("taskIds", mapper.createArrayNode().add(accepted.at("/data/taskId").asText())).toString();
        assertThat(reader.taskStatus(round.context(), UUID.randomUUID(), query).at("/data/0/status").asText()).isEqualTo("READY");
        assertThatThrownBy(() -> reader.taskStatus(new TrustedToolContext(owner.userId(), scenario.project(), UUID.randomUUID()), UUID.randomUUID(), query))
                .isInstanceOfSatisfying(ApiProblemException.class, failure -> assertThat(failure.code()).isEqualTo("TASK_NOT_FOUND"));
        var otherProject = projects.create(owner.userId(), "Foreign local workflow", Project.AspectRatio.LANDSCAPE_16_9);
        assertThatThrownBy(() -> reader.taskStatus(new TrustedToolContext(owner.userId(), otherProject.id(), scenario.run()), UUID.randomUUID(), query))
                .isInstanceOf(ApiProblemException.class);
        awaitLocal(round);
        runs.cancel(owner.userId(), scenario.project(), scenario.run());

        Scenario denied = scenario();
        Round invalid = begin(denied, 0, "extract_audio", sourceRequest(denied, denied.sourceItem(), scenario.sourceVersion()));
        assertThatThrownBy(() -> execute(invalid)).isInstanceOf(ApiProblemException.class);
        assertThat(ledgerCount(denied)).isZero();
        assertThat(mediaTaskCount(denied)).isZero();
        assertThat(canvas.list(owner.userId(), denied.project())).hasSize(1);
    }

    @Test
    void canceledRunKeepsIndependentLocalResultWithoutResumingFromRepeatedSignals() throws Exception {
        Scenario scenario = scenario();
        Round round = begin(scenario, 0, "extract_audio", sourceRequest(scenario, scenario.sourceItem(), scenario.sourceVersion()));
        JsonNode accepted = execute(round);
        awaitLocal(round);
        runs.cancel(owner.userId(), scenario.project(), scenario.run());
        replayOutcomes(scenario);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.CANCELED);
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        assertThat(task(scenario, accepted).status()).isEqualTo(Task.Status.SUCCEEDED);
        replayOutcomes(scenario);
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.CANCELED);
        assertThat(turnCount(scenario)).isEqualTo(1);
        assertThat(tasks.claimAgentTurns(MODEL_WORKER, 1)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Task.Status.class, names = {"FAILED", "UNKNOWN"})
    void recoveryReturnsFrozenFailureOrUnknownWithoutResubmission(Task.Status resolved) throws Exception {
        Scenario scenario = scenario();
        Round round = begin(scenario, 0, "extract_audio", sourceRequest(scenario, scenario.sourceItem(), scenario.sourceVersion()));
        JsonNode accepted = execute(round);
        awaitLocal(round);
        Task original = task(scenario, accepted);
        // Deliberately bypass the event publisher to reproduce a restart after durable task completion.
        jdbc.sql("update task set status=:status,error_code='SYNTHETIC_LOCAL_RESULT',completed_at="
                        + (resolved == Task.Status.FAILED ? "now()" : "null") + " where id=:id")
                .param("status", resolved.name()).param("id", original.id()).update();
        assertThat(currentRun(scenario).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        outcomes.reconcileLocalTasks(owner.userId(), scenario.project(), scenario.run());
        replayOutcomes(scenario);
        assertThat(turnCount(scenario)).isEqualTo(2);
        JsonNode frozen = reply(round);
        assertThat(frozen.at("/localTaskOutcome/tasks/0/status").asText()).isEqualTo(resolved.name());
        assertThat(frozen.at("/localTaskOutcome/tasks/0/errorCode").asText()).isEqualTo("SYNTHETIC_LOCAL_RESULT");
        assertThat(frozen.at("/data/artifactVersionId").isMissingNode()).isTrue();
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isZero();
        assertThat(mediaTaskCount(scenario)).isEqualTo(1);
        assertThat(task(scenario, accepted).attemptNo()).isEqualTo(1);
        // Later status evidence must not rewrite a tool reply that was already given to the model.
        jdbc.sql("update task set error_code='LATER_LOCAL_EVIDENCE' where id=:id").param("id", original.id()).update();
        replayOutcomes(scenario);
        assertThat(reply(round)).isEqualTo(frozen);
        finish(scenario, 1);
    }

    private Scenario scenario() throws Exception {
        Project project = projects.create(owner.userId(), "Built-in local workflow", Project.AspectRatio.LANDSCAPE_16_9);
        Path file = temp.resolve(UUID.randomUUID() + ".mp4");
        mediaTools.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "testsrc2=s=160x90:r=12", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-y", file.toString()));
        var asset = assets.archiveTaskVideo(owner.userId(), project.id(), UUID.randomUUID(), () -> {
            try { return Files.newInputStream(file); } catch (IOException failure) { throw new IllegalStateException(failure); }
        });
        var source = artifacts.create(owner.userId(), project.id(), Artifact.Kind.VIDEO, "Synthetic source voice",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", asset.id().toString()));
        UUID item = dev.agenvas.support.CanvasMediaFixture.place(canvas, owner.userId(), project.id(), source.artifact().id());
        var director = configureDirector(agents, owner.userId(), project.id(), "Local workflow director", "Use the built-in media tools");
        var run = runs.create(owner.userId(), project.id(), director.id(), "Extract, trim and compose this voice", "local-workflow-run").run();
        Scenario scenario = new Scenario(project.id(), run.id(), item, source.resourceDefaultVersion().id(), asset.id());
        scenarios.add(scenario);
        return scenario;
    }

    private Round begin(Scenario scenario, int step, String tool, ObjectNode arguments) {
        Task lease = tasks.claimAgentTurns(MODEL_WORKER, 1).getFirst();
        assertThat(lease.runId()).isEqualTo(scenario.run());
        assertThat(lease.input().path("stepIndex").asInt()).isEqualTo(step);
        AgentRun run = commits.start(lease, MODEL_WORKER);
        List<Message> history = step == 0 ? List.of(new UserMessage("Extract, trim and compose the existing voice"))
                : conversation.afterToolRound(owner.userId(), scenario.project(), scenario.run(), step - 1);
        String callId = "local-call-" + step;
        saveResponse(scenario, run, lease, step, history, AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", tool, arguments.toString()))).build());
        return new Round(scenario, new TrustedToolContext(owner.userId(), scenario.project(), scenario.run()), lease, step, callId);
    }

    private void saveResponse(Scenario scenario, AgentRun run, Task lease, int step, List<Message> history, AssistantMessage assistant) {
        int config = run.policySnapshot().path("modelConfigVersion").asInt();
        checkpoints.reserveLeased(owner.userId(), scenario.project(), scenario.run(), step, config,
                run.policySnapshot().path("modelConfigSource").asText(), codec.request(history, registry.modelDefinitions(run.policySnapshot())), lease, MODEL_WORKER);
        tasks.startAgentStream(lease, MODEL_WORKER);
        checkpoints.saveResponseLeased(owner.userId(), scenario.project(), scenario.run(), step, config,
                codec.response(new ChatResponse(List.of(new Generation(assistant)))), lease, MODEL_WORKER);
    }

    private JsonNode execute(Round round) {
        return execution.executeLeased(round.context(), round.step(), round.callId(), round.lease(), MODEL_WORKER);
    }

    private void assertReceipt(JsonNode receipt) {
        assertThat(receipt.path("awaitingLocalTask").asBoolean()).isTrue();
        assertThat(receipt.at("/data/queued").asBoolean()).isTrue();
        assertThat(receipt.at("/data/completed").asBoolean()).isFalse();
        assertThat(receipt.at("/data/localProcessing").asBoolean()).isTrue();
        assertThat(receipt.path("taskIds")).hasSize(1);
    }

    private void awaitLocal(Round round) {
        assertThat(commits.complete(round.lease(), MODEL_WORKER)).isEqualTo(AgentTurnCommitService.Decision.WAIT);
        assertThat(currentRun(round.scenario()).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        assertThat(turnCount(round.scenario())).isEqualTo(round.step() + 1);
        assertThat(tasks.claimAgentTurns(MODEL_WORKER, 1)).isEmpty();
    }

    private Task completeLocal(Round round, JsonNode receipt) {
        assertThat(mediaWorker.submitOnce(MEDIA_WORKER)).isEqualTo(1);
        Task completed = task(round.scenario(), receipt);
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        assertThat(currentRun(round.scenario()).status()).isEqualTo(AgentRun.Status.RUNNING);
        assertThat(turnCount(round.scenario())).isEqualTo(round.step() + 2);
        replayOutcomes(round.scenario());
        assertThat(turnCount(round.scenario())).isEqualTo(round.step() + 2);
        return completed;
    }

    private void assertFinalReply(Round round, Task completed) {
        JsonNode result = reply(round);
        assertThat(result.path("awaitingLocalTask").asBoolean()).isFalse();
        assertThat(result.at("/data/queued").asBoolean()).isFalse();
        assertThat(result.at("/data/completed").asBoolean()).isTrue();
        assertThat(result.at("/data/artifactVersionId").asText()).isEqualTo(outputVersion(completed).toString());
        assertThat(result.at("/localTaskOutcome/tasks/0/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.toString()).doesNotContain("objectKey", "thumbnailKey", "providerRequestId", "requestKey");
        assertThat(artifacts.requireVersionById(owner.userId(), round.scenario().project(), outputVersion(completed)).runId())
                .isEqualTo(round.scenario().run());
    }

    private JsonNode reply(Round round) {
        return conversation.afterToolRound(owner.userId(), round.scenario().project(), round.scenario().run(), round.step()).stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream()).filter(response -> response.id().equals(round.callId()))
                .map(response -> mapper.readTree(response.responseData())).findFirst().orElseThrow();
    }

    private void replayOutcomes(Scenario scenario) {
        outcomes.reconcileLocalTasks(owner.userId(), scenario.project(), scenario.run());
        outcomes.reconcileLocalTasks(owner.userId(), scenario.project(), scenario.run());
        events.listAfter(owner.userId(), scenario.project(), 0, 200).stream()
                .filter(event -> "task.status.changed".equals(event.type()))
                .forEach(event -> outcomes.onProjectEvent(new ProjectEventRecorded(owner.userId(), event)));
    }

    private void finish(Scenario scenario, int step) {
        Task lease = tasks.claimAgentTurns(MODEL_WORKER, 1).getFirst();
        assertThat(lease.runId()).isEqualTo(scenario.run());
        assertThat(lease.input().path("stepIndex").asInt()).isEqualTo(step);
        var run = commits.start(lease, MODEL_WORKER);
        saveResponse(scenario, run, lease, step, conversation.afterToolRound(owner.userId(), scenario.project(), scenario.run(), step - 1),
                AssistantMessage.builder().content("The recorded local task outcome is available.").build());
        assertThat(commits.complete(lease, MODEL_WORKER)).isEqualTo(AgentTurnCommitService.Decision.FINISH);
    }

    private ObjectNode sourceRequest(Scenario scenario, UUID item, UUID version) {
        return mapper.createObjectNode().put("canvasItemId", item.toString()).put("sourceVersionId", version.toString())
                .put("expectedCanvasItemVersion", item(scenario, item).version()).put("format", "WAV");
    }

    private CanvasItem item(Scenario scenario, UUID item) {
        return canvas.list(owner.userId(), scenario.project()).stream().filter(view -> view.item().id().equals(item)).findFirst().orElseThrow().item();
    }
    private AgentRun currentRun(Scenario scenario) { return runs.get(owner.userId(), scenario.project(), scenario.run()); }
    private Task task(Scenario scenario, JsonNode receipt) { return tasks.get(owner.userId(), scenario.project(), UUID.fromString(receipt.at("/data/taskId").asText())); }
    private UUID outputVersion(Task task) { return UUID.fromString(task.output().path("artifactVersionId").asText()); }
    private long turnCount(Scenario scenario) { return jdbc.sql("select count(*) from task where run_id=:run and kind='AGENT_TURN'").param("run", scenario.run()).query(Long.class).single(); }
    private long ledgerCount(Scenario scenario) { return jdbc.sql("select count(*) from tool_execution where run_id=:run").param("run", scenario.run()).query(Long.class).single(); }
    private long mediaTaskCount(Scenario scenario) { return jdbc.sql("select count(*) from task where project_id=:project and kind <> 'AGENT_TURN'").param("project", scenario.project()).query(Long.class).single(); }
    private record Scenario(UUID project, UUID run, UUID sourceItem, UUID sourceVersion, UUID sourceAsset) {}
    private record Round(Scenario scenario, TrustedToolContext context, Task lease, int step, String callId) {}

    @TestConfiguration static class TestConfig {
        /** Keep restart/recovery assertions deterministic; the test calls recovery through the application API. */
        @Bean(name = "taskRecoveryScheduler") TaskRecoveryScheduler disabledRecovery(TaskService tasks, TaskProperties properties) {
            return new TaskRecoveryScheduler(tasks, properties) { @Override public void scan() {} };
        }
    }
}

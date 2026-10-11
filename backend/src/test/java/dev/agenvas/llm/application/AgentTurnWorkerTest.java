package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Worker business outcomes cross the same execution scope as production; PostgreSQL verifies fencing. */
class AgentTurnWorkerTest {
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final String WORKER = "synthetic-worker";
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final Task lease = mock(Task.class);
    private final AgentRun run = mock(AgentRun.class);
    private final TaskService tasks = mock(TaskService.class);
    private final AgentTurnCommitService commits = mock(AgentTurnCommitService.class);
    private final InitialModelContextService initialContext = mock(InitialModelContextService.class);
    private final LlmConversationService conversation = mock(LlmConversationService.class);
    private final RepairModelContextService repairContext = mock(RepairModelContextService.class);
    private final LlmRoundService rounds = mock(LlmRoundService.class);
    private final LlmTurnRepository turns = mock(LlmTurnRepository.class);
    private final ToolBatchExecutionService tools = mock(ToolBatchExecutionService.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ChatGateway gateway = mock(ChatGateway.class);
    private final LeasedModelExecutor modelExecutor = mock(LeasedModelExecutor.class);
    private final LeasedModelExecutor.Execution execution = mock(LeasedModelExecutor.Execution.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);
    private final List<Message> frozen = List.of(new UserMessage("Frozen synthetic request"));
    private final List<AssistantMessage.ToolCall> calls = List.of(
            new AssistantMessage.ToolCall("synthetic-call", "function", "read_project", "{}"));
    private final JsonNode response = codec.response(new ChatResponse(List.of(new Generation(
            AssistantMessage.builder().content("Synthetic complete response").toolCalls(calls).build()))));
    private AgentTurnWorker worker;

    @BeforeEach
    void setup() throws Exception {
        worker = new AgentTurnWorker(tasks, new TaskProperties(Duration.ofMinutes(30), 16), commits,
                initialContext, conversation, repairContext, rounds, turns, codec, tools, registry,
                gateway, Clock.fixed(NOW, ZoneOffset.UTC), modelExecutor);
        when(modelExecutor.open(lease, WORKER)).thenReturn(execution);
        when(lease.id()).thenReturn(UUID.randomUUID());
        when(lease.projectId()).thenReturn(project);
        when(lease.runId()).thenReturn(runId);
        when(lease.leaseEpoch()).thenReturn(7L);
        when(lease.input()).thenReturn(mapper.createObjectNode().put("stepIndex", 0));
        when(tasks.claimAgentTurns(WORKER, 1)).thenReturn(List.of(lease));
        when(tasks.ownerForWorker(lease)).thenReturn(owner);
        when(commits.start(lease, WORKER)).thenReturn(run);
        when(run.status()).thenReturn(AgentRun.Status.RUNNING);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode()
                .put("modelConfigSource", "mock").put("modelConfigVersion", 1));
        when(turns.find(project, runId, 0)).thenReturn(Optional.empty());
        when(initialContext.assemble(owner, project, runId)).thenReturn(frozen);
        when(rounds.callLeased(eq(owner), eq(project), eq(runId), eq(0), anyList(), anyList(),
                anyMap(), eq(lease), eq(WORKER))).thenReturn(response);
        when(execution.call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    Callable<JsonNode> submitted = invocation.getArgument(0);
                    return submitted.call();
                });
    }

    @AfterEach
    void close() {
        try {
            verify(execution).close();
        } finally {
            worker.shutdown();
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource({"WAITING_TASKS, 0", "RUNNING, 1"})
    void resumedTurnsReadTheDurableResponseWithoutSubmittingAModelCall(AgentRun.Status status, int nextStep) throws Exception {
        when(run.status()).thenReturn(status);
        when(run.nextStepIndex()).thenReturn(nextStep);
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.RESPONDED)));

        assertThat(worker.runOnce(WORKER)).isEqualTo(1);

        verifyNoModelCall();
        verifyNoInteractions(rounds, initialContext, conversation, repairContext);
        verifyAppliedResponse();
        verify(tasks).ownerForWorker(lease);
    }

    @Test
    void respondedCheckpointReplaysAfterTheRetryDeadlineUsingTheFullReplayTimeout() throws Exception {
        deadline(NOW.minusSeconds(1));
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.RESPONDED)));

        worker.runOnce(WORKER);

        verify(execution).call(ArgumentMatchers.<Callable<JsonNode>>any(), eq(LlmCallTimeouts.REQUEST));
        verify(rounds).callLeased(owner, project, runId, 0, frozen, List.of(),
                java.util.Map.of("projectId", project.toString(), "runId", runId.toString()), lease, WORKER);
        verifyNoInteractions(initialContext, conversation, repairContext);
        verify(commits, never()).modelFailed(any(), anyString(), anyString());
        verifyAppliedResponse();
    }

    @Test
    void timeoutReplaysACheckpointThatWonTheFailureRace() throws Exception {
        doThrow(new TimeoutException()).when(execution)
                .call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));
        when(commits.modelFailed(lease, WORKER, "LLM_CALL_TIMEOUT")).thenReturn(response);

        worker.runOnce(WORKER);

        verify(commits).modelFailed(lease, WORKER, "LLM_CALL_TIMEOUT");
        verify(tasks, times(2)).ownerForWorker(lease);
        verifyAppliedResponse();
        verify(execution).call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));
    }

    @Test
    void expiredUnrespondedTurnRecordsFailureWithoutCallingTheModelOrTools() throws Exception {
        deadline(NOW);
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.REQUESTED)));

        worker.runOnce(WORKER);

        verify(commits).modelFailed(lease, WORKER, "LLM_CONNECTION_FAILED");
        verifyNoModelCall();
        verifyNoInteractions(rounds, tools, initialContext, conversation, repairContext);
        verify(commits, never()).complete(any(), anyString());
    }

    @Test
    void checkpointCommittedDuringDeadlineHandlingStillContinuesWithoutAnotherRequest() throws Exception {
        deadline(NOW);
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.REQUESTED)));
        when(commits.modelFailed(lease, WORKER, "LLM_CONNECTION_FAILED")).thenReturn(response);

        worker.runOnce(WORKER);

        verifyNoModelCall();
        verifyNoInteractions(rounds, initialContext, conversation, repairContext);
        verifyAppliedResponse();
    }

    @Test
    void retryUsesItsFrozenRequestAndOnlyTheRemainingWaitBudget() throws Exception {
        deadline(NOW.plusMillis(125));
        when(turns.find(project, runId, 0)).thenReturn(Optional.of(turn(LlmTurn.Status.REQUESTED)));

        worker.runOnce(WORKER);

        verify(execution).call(ArgumentMatchers.<Callable<JsonNode>>any(), eq(Duration.ofMillis(125)));
        verifyNoInteractions(initialContext, conversation, repairContext);
        verifyAppliedResponse();
    }

    @Test
    void lostLeaseDuringModelCompletionCannotAdvanceToolsOrOverwriteFailure() throws Exception {
        doThrow(new ExecutionException(new IllegalStateException("Synthetic lease lost"))).when(execution)
                .call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));
        doThrow(new IllegalStateException("Synthetic fenced failure"))
                .when(commits).finishFailure(lease, WORKER, "AGENT_TURN_FAILED");

        worker.runOnce(WORKER);

        verifyNoInteractions(tools);
        verify(commits).finishFailure(lease, WORKER, "AGENT_TURN_FAILED");
        verify(commits, never()).complete(any(), anyString());
        verify(commits, never()).modelFailed(any(), anyString(), anyString());
    }

    @Test
    void rejectedStartStopsBeforeOwnerLookupOrModelAndToolWork() throws Exception {
        when(commits.start(lease, WORKER)).thenThrow(new IllegalStateException("Synthetic lease lost"));
        doThrow(new IllegalStateException("Synthetic fenced failure"))
                .when(commits).finishFailure(lease, WORKER, "AGENT_TURN_FAILED");

        worker.runOnce(WORKER);

        verify(tasks, never()).ownerForWorker(any());
        verifyNoModelCall();
        verifyNoInteractions(rounds, tools);
        verify(commits).finishFailure(lease, WORKER, "AGENT_TURN_FAILED");
        verify(commits, never()).complete(any(), anyString());
    }

    @Test
    void interruptedWaitPreservesTheInterruptAndLeavesDurableRecoveryInCharge() throws Exception {
        doThrow(new InterruptedException()).when(execution)
                .call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));

        worker.runOnce(WORKER);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(commits, never()).finishFailure(any(), anyString(), anyString());
        verify(commits, never()).modelFailed(any(), anyString(), anyString());
        verifyNoInteractions(tools);
    }

    @Test
    void stoppedModelCallDoesNotBecomeAModelTimeoutOrBusinessFailure() throws Exception {
        doThrow(new LeasedModelExecutor.Stopped("Renewal unconfirmed", new TimeoutException()))
                .when(execution).call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));

        worker.runOnce(WORKER);

        verifyNoFailureOrRetry();
        verifyNoInteractions(tools);
    }

    @Test
    void aReturnedResponseCannotEnterToolsAfterTheScopeStops() throws Exception {
        doAnswer(invocation -> {
            Callable<JsonNode> submitted = invocation.getArgument(0);
            JsonNode recorded = submitted.call();
            stopScope();
            return recorded;
        }).when(execution).call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));

        worker.runOnce(WORKER);

        verifyNoFailureOrRetry();
        verifyNoInteractions(tools);
    }

    @Test
    void toolsCommittedBeforeALocalStopAreLeftForDurableReplay() {
        doAnswer(invocation -> {
            stopScope();
            return null;
        }).when(tools).executeLeased(any(), eq(0), anyList(), eq(lease), eq(WORKER));

        worker.runOnce(WORKER);

        verify(tools).executeLeased(new TrustedToolContext(owner, project, runId), 0, calls, lease, WORKER);
        verifyNoFailureOrRetry();
    }

    @Test
    void checkpointWinningTimeoutStillWaitsForRecoveryIfTheScopeHasStopped() throws Exception {
        doThrow(new TimeoutException()).when(execution)
                .call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));
        when(commits.modelFailed(lease, WORKER, "LLM_CALL_TIMEOUT")).thenAnswer(invocation -> {
            stopScope();
            return response;
        });

        worker.runOnce(WORKER);

        verify(commits).modelFailed(lease, WORKER, "LLM_CALL_TIMEOUT");
        verify(commits, never()).finishFailure(any(), anyString(), anyString());
        verify(commits, never()).complete(any(), anyString());
        verifyNoInteractions(tools);
    }

    private void stopScope() {
        doThrow(new LeasedModelExecutor.Stopped("Renewal unconfirmed")).when(execution).checkActive();
    }

    private void verifyNoFailureOrRetry() {
        verify(commits, never()).finishFailure(any(), anyString(), anyString());
        verify(commits, never()).modelFailed(any(), anyString(), anyString());
        verify(commits, never()).complete(any(), anyString());
    }

    private void verifyNoModelCall() throws Exception {
        verify(execution, never()).call(ArgumentMatchers.<Callable<JsonNode>>any(), any(Duration.class));
    }

    private void deadline(Instant deadline) {
        var output = mapper.createObjectNode();
        output.putObject(AgentModelRetryPolicy.PROPERTY).put("deadlineAt", deadline.toString())
                .put("lastErrorCode", "LLM_CONNECTION_FAILED");
        when(lease.output()).thenReturn(output);
    }

    private LlmTurn turn(LlmTurn.Status status) {
        return new LlmTurn(project, runId, 0, status, 1, codec.request(frozen, List.of()),
                status == LlmTurn.Status.RESPONDED ? response : null, NOW, null);
    }

    private void verifyAppliedResponse() {
        verify(tools).executeLeased(new TrustedToolContext(owner, project, runId), 0, calls, lease, WORKER);
        verify(commits).complete(lease, WORKER);
        verify(commits, never()).finishFailure(any(), anyString(), anyString());
    }
}

package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic gateway validates the production streaming orchestration without a Provider. */
class LlmRoundStreamTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final ChatGateway gateway = mock(ChatGateway.class);
    private final LlmTurnCheckpointService checkpoints = mock(LlmTurnCheckpointService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final CallLogService logs = mock(CallLogService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final Task lease = mock(Task.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);
    private final LlmRoundService service = new LlmRoundService(gateway, codec, checkpoints, runs, logs, tasks);
    private final JsonNode response = codec.response(new ChatResponse(List.of(new Generation(new AssistantMessage("first second")))));

    @BeforeEach
    void setup() {
        AgentRun run = mock(AgentRun.class);
        when(run.policySnapshot()).thenReturn(mapper.createObjectNode().put("modelConfigSource", "mock").put("modelConfigVersion", 3));
        when(runs.find(owner, project, runId)).thenReturn(Optional.of(run));
        when(gateway.modelDetailsFor(any())).thenReturn(new ChatGateway.ModelDetails(true, "synthetic", "synthetic-model", true));
        when(checkpoints.reserveLeased(eq(owner), eq(project), eq(runId), eq(0), eq(3), eq("mock"), any(), eq(lease), eq("worker")))
                .thenReturn(turn(LlmTurn.Status.REQUESTED, null));
        when(checkpoints.saveResponseLeased(eq(owner), eq(project), eq(runId), eq(0), eq(3), any(), eq(lease), eq("worker")))
                .thenReturn(turn(LlmTurn.Status.RESPONDED, response));
        when(logs.recordStream(any(), any(), any())).thenAnswer(call -> {
            java.util.function.BiFunction<Boolean, Consumer<dev.agenvas.audit.domain.LlmStreamLog>, ?> invocation = call.getArgument(1);
            return invocation.apply(false, ignored -> {});
        });
        when(tasks.appendAgentStream(eq(lease), eq("worker"), anyLong(), anyString())).thenAnswer(call -> (long) call.getArgument(2) + 1);
        when(gateway.callStreaming(anyList(), anyList(), anyMap(), any(), any(), anyBoolean(), any())).thenAnswer(call -> {
            Consumer<String> delta = call.getArgument(4);
            delta.accept("first ");
            // First batch must already be committed before the Provider has a complete response.
            verify(tasks).appendAgentStream(lease, "worker", 0, "first ");
            verify(checkpoints, never()).saveResponseLeased(any(), any(), any(), anyInt(), anyInt(), any(), any(), anyString());
            delta.accept("second");
            return new ChatGateway.Exchange(3, new ChatResponse(List.of(new Generation(new AssistantMessage("first second")))));
        });
    }

    @Test
    void commitsIncrementalPublicBatchesThenCompleteCheckpointUsingExactLease() {
        assertThat(call()).isEqualTo(response);
        var order = inOrder(tasks, gateway, checkpoints);
        order.verify(checkpoints).reserveLeased(eq(owner), eq(project), eq(runId), eq(0), eq(3), eq("mock"), any(), eq(lease), eq("worker"));
        order.verify(tasks).startAgentStream(lease, "worker");
        order.verify(gateway).callStreaming(anyList(), anyList(), anyMap(), eq(new ChatGateway.ConfigIdentity("mock", 3)), any(), eq(false), any());
        order.verify(tasks).appendAgentStream(lease, "worker", 0, "first ");
        order.verify(tasks).appendAgentStream(lease, "worker", 1, "second");
        order.verify(checkpoints).saveResponseLeased(eq(owner), eq(project), eq(runId), eq(0), eq(3), any(), eq(lease), eq("worker"));
        verify(gateway, never()).call(anyList(), anyList(), anyMap(), any());
    }

    @Test
    void alreadyRecordedResponseReplaysWithoutASecondModelStream() {
        when(checkpoints.reserveLeased(eq(owner), eq(project), eq(runId), eq(0), eq(3), eq("mock"), any(), eq(lease), eq("worker")))
                .thenReturn(turn(LlmTurn.Status.RESPONDED, response));
        assertThat(call()).isEqualTo(response);
        verify(gateway, never()).callStreaming(anyList(), anyList(), anyMap(), any(), any(), anyBoolean(), any());
        verifyNoInteractions(tasks, logs);
    }

    @Test
    void failedTransportKeepsPublishedDeltaAndDoesNotRetryOrSaveACompleteResponse() {
        doAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(4);
            delta.accept("first ");
            throw new IllegalStateException("Stream transport size limit");
        }).when(gateway).callStreaming(anyList(), anyList(), anyMap(), any(), any(), anyBoolean(), any());
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessageContaining("transport size limit");
        verify(tasks).appendAgentStream(lease, "worker", 0, "first ");
        verify(gateway).callStreaming(anyList(), anyList(), anyMap(), any(), any(), anyBoolean(), any());
        verify(gateway, never()).call(anyList(), anyList(), anyMap(), any());
        verify(checkpoints, never()).saveResponseLeased(any(), any(), any(), anyInt(), anyInt(), any(), any(), anyString());
    }

    @Test
    void rejectedDeltaCancelsResponsePathBeforeSavingACompleteCheckpoint() {
        when(tasks.appendAgentStream(lease, "worker", 0, "first ")).thenThrow(new IllegalStateException("Lease lost"));
        assertThatThrownBy(this::call).isInstanceOf(IllegalStateException.class).hasMessage("Lease lost");
        verify(checkpoints, never()).saveResponseLeased(any(), any(), any(), anyInt(), anyInt(), any(), any(), anyString());
    }

    private JsonNode call() { return service.callLeased(owner, project, runId, 0, List.of(new UserMessage("Hello")), List.of(), Map.of(), lease, "worker"); }
    private LlmTurn turn(LlmTurn.Status status, JsonNode body) { return new LlmTurn(project, runId, 0, status, 3, mapper.createObjectNode(), body, NOW, null); }
}

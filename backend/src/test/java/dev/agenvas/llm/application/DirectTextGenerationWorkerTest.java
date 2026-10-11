package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Worker orchestration uses synthetic outcomes; PostgreSQL proves response and Artifact fencing. */
class DirectTextGenerationWorkerTest {
    private static final String WORKER = "direct-text-test";
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProtocolCodec codec = new LlmProtocolCodec(mapper);
    private final TaskService tasks = mock(TaskService.class);
    private final ChatGateway gateway = mock(ChatGateway.class);
    private final CallLogService callLogs = mock(CallLogService.class);
    private final LeasedModelExecutor executor = mock(LeasedModelExecutor.class);
    private final LeasedModelExecutor.Execution execution = mock(LeasedModelExecutor.Execution.class);
    private final ObjectNode input = mapper.createObjectNode().put("schemaVersion", 2)
            .put("systemPrompt", "Frozen creative instruction").put("prompt", "Rewrite")
            .put("currentText", "Original card").put("format", "MARKDOWN")
            .put("modelConfigSource", "mock").put("modelConfigVersion", 3);
    private final ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity("mock", 3);
    private final ChatGateway.Exchange exchange = new ChatGateway.Exchange(3,
            new ChatResponse(List.of(new Generation(new AssistantMessage("  Finished text  ")))));
    private Task lease;
    private DirectTextGenerationWorker worker;

    @BeforeEach
    void setup() throws Exception {
        claimedTask(null);
        worker = new DirectTextGenerationWorker(tasks, gateway, codec, callLogs, mapper, executor,
                mock(ImagePromptInputService.class));
        when(executor.open(any(), eq(WORKER))).thenReturn(execution);
        when(gateway.modelDetailsFor(pinned)).thenReturn(new ChatGateway.ModelDetails(true, "MOCK", "synthetic-text", false));
        when(gateway.call(anyList(), anyList(), anyMap(), eq(pinned))).thenReturn(exchange);
        when(callLogs.record(any(), any(), any())).thenAnswer(call -> {
            Supplier<?> invocation = call.getArgument(1);
            return invocation.get();
        });
        when(execution.call(any(), eq(LlmCallTimeouts.REQUEST))).thenAnswer(call -> {
            Callable<?> invocation = call.getArgument(0);
            return invocation.call();
        });
        when(tasks.checkpointTextResponse(any(), eq(WORKER), any())).thenAnswer(call -> call.getArgument(2));
    }

    @AfterEach
    void close() {
        try {
            verify(execution).close();
        } finally {
            Thread.interrupted();
            worker.close();
        }
    }

    @Test
    void replaysThePersistedResponseWithoutConsultingOrCallingTheModel() throws Exception {
        claimedTask(checkpoint(exchange.response()));

        assertThat(worker.runOnce(WORKER)).isEqualTo(1);

        ArgumentCaptor<JsonNode> content = ArgumentCaptor.forClass(JsonNode.class);
        verify(tasks).succeedWithTextArtifact(eq(lease), eq(WORKER), content.capture());
        assertThat(content.getValue().path("text").asText()).isEqualTo("Finished text");
        assertThat(content.getValue().path("format").asText()).isEqualTo("MARKDOWN");
        verifyNoInteractions(gateway, callLogs);
        verify(execution, never()).call(any(), any());
        verify(tasks, never()).checkpointTextResponse(any(), anyString(), any());
        verify(execution).checkActive();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void callsThePinnedModelWithTheFrozenSchemaSpecificPromptBeforeCheckpointing(int schema) {
        input.put("schemaVersion", schema);
        if (schema == 1) input.remove("systemPrompt");

        assertThat(worker.runOnce(WORKER)).isEqualTo(1);

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.captor();
        verify(gateway).call(messages.capture(), eq(List.of()), eq(java.util.Map.of()), eq(pinned));
        String systemPrompt = messages.getValue().getFirst().getText();
        assertThat(systemPrompt).isEqualTo(schema == 2 ? "Frozen creative instruction"
                : "You write the finished content of one text card. Follow the user instruction using the current card content as context. Return only the finished card text, without commentary, tool calls, XML wrappers, or Markdown code fences. Keep the response under 20000 characters.");
        assertThat(messages.getValue().get(1).getText()).isEqualTo("Instruction:\nRewrite\n\nCurrent card content:\nOriginal card");
        var order = inOrder(execution, tasks);
        order.verify(execution, times(2)).checkActive();
        order.verify(tasks).checkpointTextResponse(eq(lease), eq(WORKER), any());
        order.verify(execution).checkActive();
        order.verify(tasks).succeedWithTextArtifact(eq(lease), eq(WORKER), any());
        verify(tasks, never()).fail(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(InvalidOutput.class)
    void rejectsInvalidPersistedOutputsWithoutCallingTheModel(InvalidOutput invalid) {
        AssistantMessage assistant = switch (invalid) {
            case EMPTY -> new AssistantMessage(" \n ");
            case TOO_LONG -> new AssistantMessage("x".repeat(20_001));
            case TOOL_CALL -> AssistantMessage.builder().content("Unexpected tool request")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "read_project", "{}"))).build();
        };
        claimedTask(checkpoint(new ChatResponse(List.of(new Generation(assistant)))));

        worker.runOnce(WORKER);

        verify(tasks).fail(lease, WORKER, "MODEL_OUTPUT_INVALID");
        verify(tasks, never()).succeedWithTextArtifact(any(), anyString(), any());
        verifyNoInteractions(gateway, callLogs);
    }

    @ParameterizedTest
    @EnumSource(CallFailure.class)
    void preservesModelCallFailureClassificationForFutureTimeoutAndCapacityFailure(CallFailure failure) throws Exception {
        Exception error = switch (failure) {
            case MODEL_ARGUMENT -> new ExecutionException(new IllegalArgumentException("Synthetic gateway argument failure"));
            case TIMEOUT -> new TimeoutException("Synthetic model timeout");
            case CAPACITY -> new RejectedExecutionException("Synthetic queue full");
        };
        doThrow(error).when(execution).call(any(), any());

        worker.runOnce(WORKER);

        verify(tasks).fail(lease, WORKER, "MODEL_CALL_FAILED");
        verify(tasks, never()).checkpointTextResponse(any(), anyString(), any());
        verify(tasks, never()).succeedWithTextArtifact(any(), anyString(), any());
    }

    @Test
    void restoresThreadInterruptionAndLeavesTheTaskForDurableRecovery() throws Exception {
        doThrow(new InterruptedException("Synthetic shutdown")).when(execution).call(any(), any());

        worker.runOnce(WORKER);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(tasks, never()).fail(any(), anyString(), anyString());
        verify(tasks, never()).checkpointTextResponse(any(), anyString(), any());
        verify(tasks, never()).succeedWithTextArtifact(any(), anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(StopPoint.class)
    void stopsAtEachLeaseBoundaryWithoutMisclassifyingItAsAModelFailure(StopPoint point) throws Exception {
        LeasedModelExecutor.Stopped stopped = new LeasedModelExecutor.Stopped("Synthetic lease lost");
        switch (point) {
            case CALL -> doThrow(stopped).when(execution).call(any(), any());
            case CHECKPOINT -> doThrow(stopped).when(execution).checkActive();
            case ARTIFACT -> doNothing().doNothing().doThrow(stopped).when(execution).checkActive();
        }

        worker.runOnce(WORKER);

        verify(tasks, never()).fail(any(), anyString(), anyString());
        verify(tasks, never()).succeedWithTextArtifact(any(), anyString(), any());
        if (point != StopPoint.ARTIFACT) verify(tasks, never()).checkpointTextResponse(any(), anyString(), any());
        else verify(tasks).checkpointTextResponse(eq(lease), eq(WORKER), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryCheckpointOrArtifactFailureStillUsesTheOriginalFailureMapping(boolean afterCheckpoint) {
        if (afterCheckpoint) {
            doThrow(new IllegalStateException("Synthetic artifact failure")).when(tasks)
                    .succeedWithTextArtifact(any(), anyString(), any());
        } else {
            doThrow(new IllegalStateException("Synthetic checkpoint failure")).when(tasks)
                    .checkpointTextResponse(any(), anyString(), any());
        }

        worker.runOnce(WORKER);

        verify(tasks).fail(lease, WORKER, "MODEL_CALL_FAILED");
    }

    @Test
    void rejectsAModelConfigurationChangeBeforeSavingItsResponse() {
        when(gateway.call(anyList(), anyList(), anyMap(), eq(pinned))).thenReturn(
                new ChatGateway.Exchange(4, exchange.response()));

        worker.runOnce(WORKER);

        verify(tasks).fail(lease, WORKER, "MODEL_CALL_FAILED");
        verify(tasks, never()).checkpointTextResponse(any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aStoppedLeaseTakesPrecedenceOverSimultaneousValidationOrArtifactFailure(boolean artifactFailure) {
        LeasedModelExecutor.Stopped stopped = new LeasedModelExecutor.Stopped("Synthetic concurrent heartbeat failure");
        if (artifactFailure) {
            doAnswer(call -> {
                doThrow(stopped).when(execution).checkActive();
                throw new IllegalStateException("Synthetic artifact failure");
            }).when(tasks).succeedWithTextArtifact(any(), anyString(), any());
        } else {
            claimedTask(checkpoint(new ChatResponse(List.of(new Generation(new AssistantMessage(" "))))));
            doThrow(stopped).when(execution).checkActive();
        }

        worker.runOnce(WORKER);

        verify(tasks, never()).fail(any(), anyString(), anyString());
        if (artifactFailure) verify(tasks).succeedWithTextArtifact(eq(lease), eq(WORKER), any());
        else verify(tasks, never()).succeedWithTextArtifact(any(), anyString(), any());
    }

    private ObjectNode checkpoint(ChatResponse response) {
        ObjectNode checkpoint = mapper.createObjectNode().put("schemaVersion", 1);
        checkpoint.set("response", codec.response(response));
        return checkpoint;
    }

    private void claimedTask(JsonNode output) {
        Instant now = Instant.parse("2026-10-11T00:00:00Z");
        lease = new Task(UUID.randomUUID(), UUID.randomUUID(), null, "synthetic-text",
                Task.Kind.TEXT_GENERATION, Task.Status.RUNNING, false, input, "frozen-input",
                output, null, 1, now, WORKER, now.plusSeconds(60), 7, 2, null, now, now, null);
        when(tasks.claimTextGenerations(WORKER, 1)).thenReturn(List.of(lease));
    }

    enum InvalidOutput { EMPTY, TOO_LONG, TOOL_CALL }
    enum CallFailure { MODEL_ARGUMENT, TIMEOUT, CAPACITY }
    enum StopPoint { CALL, CHECKPOINT, ARTIFACT }
}

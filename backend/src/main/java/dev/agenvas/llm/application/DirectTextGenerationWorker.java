package dev.agenvas.llm.application;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Executes direct text and image-to-prompt work with durable responses and fenced result commits. */
@Service
public class DirectTextGenerationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectTextGenerationWorker.class);
    private static final int MAX_OUTPUT_LENGTH = 20_000;

    private final TaskService tasks;
    private final ChatGateway gateway;
    private final LlmProtocolCodec codec;
    private final CallLogService callLogs;
    private final ObjectMapper mapper;
    private final LeasedModelExecutor modelExecutor;
    private final DirectTextPurpose.ImagePrompt imagePrompt;

    @Autowired
    public DirectTextGenerationWorker(TaskService tasks, TaskProperties taskProperties,
            ChatGateway gateway, LlmProtocolCodec codec, CallLogService callLogs,
            ObjectMapper mapper, ImagePromptInputService images) {
        this(tasks, gateway, codec, callLogs, mapper,
                new LeasedModelExecutor("direct-text", tasks, taskProperties), images);
    }

    DirectTextGenerationWorker(TaskService tasks, ChatGateway gateway, LlmProtocolCodec codec,
            CallLogService callLogs, ObjectMapper mapper, LeasedModelExecutor modelExecutor,
            ImagePromptInputService images) {
        this.tasks = tasks;
        this.gateway = gateway;
        this.codec = codec;
        this.callLogs = callLogs;
        this.mapper = mapper;
        this.modelExecutor = modelExecutor;
        this.imagePrompt = new DirectTextPurpose.ImagePrompt(images);
    }

    public synchronized int runOnce(String workerId) {
        List<Task> claimed = tasks.claimTextGenerations(workerId, 1);
        for (Task lease : claimed)
            runClaimed(lease, workerId);
        return claimed.size();
    }

    private void runClaimed(Task lease, String workerId) {
        try (var execution = modelExecutor.open(lease, workerId)) {
            completeText(lease, workerId, execution);
        } catch (LeasedModelExecutor.Stopped stopped) {
            LOGGER.warn("Direct-text task {} stopped before its lease could be confirmed", lease.id());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Direct-text task {} interrupted; durable work remains recoverable", lease.id());
        }
    }

    private void completeText(Task lease, String workerId, LeasedModelExecutor.Execution execution)
            throws InterruptedException {
        DirectTextPurpose purpose = DirectTextPurpose.forTask(lease, imagePrompt);
        try {
            JsonNode checkpoint = lease.output();
            if (checkpoint == null)
                checkpoint = invokeAndCheckpoint(lease, workerId, execution, purpose);
            ObjectNode content = textContent(lease, checkpoint);
            execution.checkActive();
            purpose.publish(tasks, lease, workerId, checkpoint, content);
        } catch (LeasedModelExecutor.Stopped stopped) {
            throw stopped;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            execution.checkActive();
            tasks.fail(lease, workerId, errorCode(purpose, failure));
        }
    }

    private ObjectNode textContent(Task lease, JsonNode checkpoint) {
        var assistant = codec.selectedAssistant(checkpoint.path("response"));
        String text = assistant.getText() == null ? "" : assistant.getText().trim();
        if (!assistant.getToolCalls().isEmpty() || text.isEmpty() || text.length() > MAX_OUTPUT_LENGTH) {
            throw new IllegalArgumentException("Direct text model output is invalid");
        }
        ObjectNode content = mapper.createObjectNode();
        content.put("format", "MARKDOWN".equals(lease.input().path("format").asText())
                ? "MARKDOWN"
                : "PLAIN_TEXT");
        content.put("text", text);
        return content;
    }

    private JsonNode invokeAndCheckpoint(Task lease, String workerId, LeasedModelExecutor.Execution execution,
            DirectTextPurpose purpose)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<Message> messages = purpose.messages(lease);
        ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity(
                lease.input().path("modelConfigSource").asText(""),
                lease.input().path("modelConfigVersion").asInt(-1));
        LlmRequestBudget budget = codec.requestBudget(lease.input());
        if (budget != null)
            budget.requireInput(messages, List.of());
        ChatGateway.ModelDetails model = gateway.modelDetailsFor(pinned);
        if (!model.available())
            throw new IllegalStateException("Pinned text model unavailable");
        ChatGateway.Exchange exchange = execution.call(() -> callLogs.record(
                new CallLogService.CallDescriptor(lease.projectId(), lease.id(), null, null,
                        CallLog.Kind.LLM, CallLog.Operation.CHAT,
                        "mock".equals(pinned.source()) ? "mock" : model.providerAdapter(),
                        model.modelId(), "mock".equals(pinned.source())),
                () -> {
                    List<Message> dispatch = purpose.hydrate(lease, messages);
                    return budget == null
                            ? gateway.call(dispatch, List.of(), Map.of(), pinned)
                            : gateway.call(dispatch, List.of(), Map.of(), pinned, budget);
                },
                ignored -> CallLogService.CallOutcome.succeeded(null)), LlmCallTimeouts.REQUEST);
        execution.checkActive();
        if (exchange.configVersion() != pinned.version()) {
            throw new IllegalStateException("Text model configuration changed during call");
        }
        ObjectNode checkpoint = mapper.createObjectNode();
        checkpoint.put("schemaVersion", 1);
        checkpoint.set("request", codec.withBudget(codec.request(messages, List.of()), budget));
        checkpoint.set("response", codec.response(exchange.response()));
        execution.checkActive();
        return tasks.checkpointTextResponse(lease, workerId, checkpoint);
    }

    private String errorCode(DirectTextPurpose purpose, Throwable failure) {
        if (LlmStreamIncompleteException.matches(failure))
            return LlmStreamIncompleteException.ERROR_CODE;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ImagePromptInputService.Failure input)
                return input.getMessage();
            String specific = purpose.rejectionCode(cause);
            if (specific != null)
                return specific;
            if (cause instanceof java.io.UncheckedIOException)
                return "LLM_CONNECTION_FAILED";
            if (cause instanceof LlmRequestBudget.Failure budget)
                return budget.getMessage();
            if (cause instanceof LlmProtocolCodec.OutputTruncated)
                return LlmProtocolCodec.OUTPUT_TRUNCATED;
            if (cause instanceof dev.agenvas.shared.error.ApiProblemException problem
                    && LlmStreamIncompleteException.ERROR_CODE.equals(problem.code()))
                return problem.code();
        }
        return failure instanceof IllegalArgumentException
                ? "MODEL_OUTPUT_INVALID"
                : "MODEL_CALL_FAILED";
    }

    @PreDestroy
    void close() {
        modelExecutor.close();
    }
}

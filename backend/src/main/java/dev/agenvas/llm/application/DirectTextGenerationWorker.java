package dev.agenvas.llm.application;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Executes direct text and image-to-prompt work with durable responses and fenced result commits. */
@Service
public class DirectTextGenerationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectTextGenerationWorker.class);
    private static final int MAX_OUTPUT_LENGTH = 20_000;
    private static final Duration MODEL_TIMEOUT = LlmCallTimeouts.REQUEST;

    private final TaskService tasks;
    private final ChatGateway gateway;
    private final LlmProtocolCodec codec;
    private final CallLogService callLogs;
    private final ObjectMapper mapper;
    private final LeasedModelExecutor modelExecutor;
    private final ImagePromptInputService images;

    @Autowired
    public DirectTextGenerationWorker(TaskService tasks, TaskProperties taskProperties,
            ChatGateway gateway, LlmProtocolCodec codec, CallLogService callLogs,
            ObjectMapper mapper, ImagePromptInputService images) {
        this(tasks, gateway, codec, callLogs, mapper,
                new LeasedModelExecutor("direct-text", tasks, taskProperties), images);
    }

    DirectTextGenerationWorker(TaskService tasks, ChatGateway gateway, LlmProtocolCodec codec,
            CallLogService callLogs, ObjectMapper mapper, LeasedModelExecutor modelExecutor, ImagePromptInputService images) {
        this.tasks = tasks;
        this.gateway = gateway;
        this.codec = codec;
        this.callLogs = callLogs;
        this.mapper = mapper;
        this.modelExecutor = modelExecutor;
        this.images = images;
    }

    public synchronized int runOnce(String workerId) {
        List<Task> claimed = tasks.claimTextGenerations(workerId, 1);
        for (Task lease : claimed) runClaimed(lease, workerId);
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
        try {
            JsonNode checkpoint = lease.output();
            if (checkpoint == null) checkpoint = invokeAndCheckpoint(lease, workerId, execution);
            ObjectNode content = textContent(lease, checkpoint);
            execution.checkActive();
            if (lease.isImagePrompt()) {
                ObjectNode output = (ObjectNode) checkpoint.deepCopy();
                ObjectNode result = mapper.createObjectNode().put("prompt", content.path("text").asText())
                        .put("sourceVersionId", lease.input().path("sourceVersionId").asText())
                        .put("mock", "mock".equals(lease.input().path("modelConfigSource").asText()));
                output.set("result", result);
                tasks.succeed(lease, workerId, output);
            } else tasks.succeedWithTextArtifact(lease, workerId, content);
        } catch (LeasedModelExecutor.Stopped stopped) {
            throw stopped;
        } catch (ExecutionException | TimeoutException failure) {
            execution.checkActive();
            tasks.fail(lease, workerId, "MODEL_CALL_FAILED");
        } catch (RuntimeException failure) {
            execution.checkActive();
            tasks.fail(lease, workerId, errorCode(failure));
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
                ? "MARKDOWN" : "PLAIN_TEXT");
        content.put("text", text);
        return content;
    }

    private static final int LEGACY_INPUT_SCHEMA_VERSION = 1;
    private static final int FROZEN_PROMPT_INPUT_SCHEMA_VERSION = 2;

    /** Legacy schema 1 tasks retain the original prompt; new tasks never read live settings. */
    private String frozenSystemPrompt(Task lease) {
        if (lease.input().path("schemaVersion").asInt() == LEGACY_INPUT_SCHEMA_VERSION) return "You write the finished content of one text card. Follow the user instruction using the current card content as context. Return only the finished card text, without commentary, tool calls, XML wrappers, or Markdown code fences. Keep the response under 20000 characters.";
        String content = lease.input().path("systemPrompt").asText("");
        if (lease.input().path("schemaVersion").asInt() != FROZEN_PROMPT_INPUT_SCHEMA_VERSION || content.isBlank())
            throw new IllegalArgumentException("Text Task lacks its frozen system prompt");
        return content;
    }

    private JsonNode invokeAndCheckpoint(Task lease, String workerId, LeasedModelExecutor.Execution execution)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<Message> messages = lease.isImagePrompt() ? images.messages(lease) : List.of(
                new SystemMessage(frozenSystemPrompt(lease)),
                new UserMessage("Instruction:\n" + lease.input().path("prompt").asText()
                        + "\n\nCurrent card content:\n"
                        + lease.input().path("currentText").asText("")));
        ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity(
                lease.input().path("modelConfigSource").asText(""),
                lease.input().path("modelConfigVersion").asInt(-1));
        ChatGateway.ModelDetails model = gateway.modelDetailsFor(pinned);
        if (!model.available()) throw new IllegalStateException("Pinned text model unavailable");
        ChatGateway.Exchange exchange = execution.call(() -> callLogs.record(
                new CallLogService.CallDescriptor(lease.projectId(), lease.id(), null, null,
                        CallLog.Kind.LLM, CallLog.Operation.CHAT,
                        "mock".equals(pinned.source()) ? "mock" : model.providerAdapter(),
                        model.modelId(), "mock".equals(pinned.source())),
                () -> gateway.call(lease.isImagePrompt() ? images.hydrate(lease, messages) : messages,
                        List.of(), Map.of(), pinned),
                ignored -> CallLogService.CallOutcome.succeeded(null)), MODEL_TIMEOUT);
        execution.checkActive();
        if (exchange.configVersion() != pinned.version()) {
            throw new IllegalStateException("Text model configuration changed during call");
        }
        ObjectNode checkpoint = mapper.createObjectNode();
        checkpoint.put("schemaVersion", 1);
        checkpoint.set("request", codec.request(messages, List.of()));
        checkpoint.set("response", codec.response(exchange.response()));
        execution.checkActive();
        return tasks.checkpointTextResponse(lease, workerId, checkpoint);
    }

    private String errorCode(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
                ? "MODEL_OUTPUT_INVALID" : "MODEL_CALL_FAILED";
    }

    @PreDestroy
    void close() {
        modelExecutor.close();
    }
}

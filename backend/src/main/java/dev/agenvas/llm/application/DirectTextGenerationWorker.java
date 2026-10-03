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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Executes direct text-card prompts with a durable response checkpoint and fenced Artifact commit. */
@Service
public class DirectTextGenerationWorker {
    private static final int MAX_OUTPUT_LENGTH = 20_000;
    private static final Duration MODEL_TIMEOUT = LlmCallTimeouts.MODEL_REQUEST;

    private final TaskService tasks;
    private final TaskProperties taskProperties;
    private final ChatGateway gateway;
    private final LlmProtocolCodec codec;
    private final CallLogService callLogs;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService heartbeatExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("direct-text-heartbeat").factory());
    private final ExecutorService modelExecutor = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("direct-text-model").factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public DirectTextGenerationWorker(TaskService tasks, TaskProperties taskProperties,
            ChatGateway gateway, LlmProtocolCodec codec, CallLogService callLogs,
            ObjectMapper mapper) {
        this.tasks = tasks;
        this.taskProperties = taskProperties;
        this.gateway = gateway;
        this.codec = codec;
        this.callLogs = callLogs;
        this.mapper = mapper;
    }

    public synchronized int runOnce(String workerId) {
        List<Task> claimed = tasks.claimTextGenerations(workerId, 1);
        for (Task lease : claimed) runClaimed(lease, workerId);
        return claimed.size();
    }

    private void runClaimed(Task lease, String workerId) {
        long intervalMillis = Math.max(1_000, taskProperties.leaseDuration().toMillis() / 3);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> tasks.heartbeat(lease.id(), workerId, lease.leaseEpoch()),
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        try {
            JsonNode checkpoint = lease.output();
            if (checkpoint == null) checkpoint = invokeAndCheckpoint(lease, workerId);
            JsonNode response = checkpoint.path("response");
            var assistant = codec.selectedAssistant(response);
            String text = assistant.getText() == null ? "" : assistant.getText().trim();
            if (!assistant.getToolCalls().isEmpty() || text.isEmpty()
                    || text.length() > MAX_OUTPUT_LENGTH) {
                throw new IllegalArgumentException("Direct text model output is invalid");
            }
            ObjectNode content = mapper.createObjectNode();
            content.put("format", "MARKDOWN".equals(lease.input().path("format").asText())
                    ? "MARKDOWN" : "PLAIN_TEXT");
            content.put("text", text);
            tasks.succeedWithTextArtifact(lease, workerId, content);
        } catch (RuntimeException failure) {
            tasks.fail(lease, workerId, errorCode(failure));
        } finally {
            heartbeat.cancel(false);
        }
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

    private JsonNode invokeAndCheckpoint(Task lease, String workerId) {
        List<Message> messages = List.of(
                new SystemMessage(frozenSystemPrompt(lease)),
                new UserMessage("Instruction:\n" + lease.input().path("prompt").asText()
                        + "\n\nCurrent card content:\n"
                        + lease.input().path("currentText").asText("")));
        ChatGateway.ConfigIdentity pinned = new ChatGateway.ConfigIdentity(
                lease.input().path("modelConfigSource").asText(""),
                lease.input().path("modelConfigVersion").asInt(-1));
        ChatGateway.ModelDetails model = gateway.modelDetailsFor(pinned);
        if (!model.available()) throw new IllegalStateException("Pinned text model unavailable");
        Future<ChatGateway.Exchange> future = modelExecutor.submit(() -> callLogs.record(
                new CallLogService.CallDescriptor(lease.projectId(), lease.id(), null, null,
                        CallLog.Kind.LLM, CallLog.Operation.CHAT,
                        "mock".equals(pinned.source()) ? "mock" : model.providerAdapter(),
                        model.modelId(), "mock".equals(pinned.source())),
                () -> gateway.call(messages, List.of(), Map.of(), pinned),
                ignored -> CallLogService.CallOutcome.succeeded(null)));
        ChatGateway.Exchange exchange;
        try {
            exchange = future.get(MODEL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            throw new IllegalStateException("Direct text model call failed", failure);
        }
        if (exchange.configVersion() != pinned.version()) {
            throw new IllegalStateException("Text model configuration changed during call");
        }
        ObjectNode checkpoint = mapper.createObjectNode();
        checkpoint.put("schemaVersion", 1);
        checkpoint.set("request", codec.request(messages, List.of()));
        checkpoint.set("response", codec.response(exchange.response()));
        return tasks.checkpointTextResponse(lease, workerId, checkpoint);
    }

    private String errorCode(RuntimeException failure) {
        return failure instanceof IllegalArgumentException
                ? "MODEL_OUTPUT_INVALID" : "MODEL_CALL_FAILED";
    }

    @PreDestroy
    void close() {
        heartbeatExecutor.shutdownNow();
        modelExecutor.shutdownNow();
    }
}

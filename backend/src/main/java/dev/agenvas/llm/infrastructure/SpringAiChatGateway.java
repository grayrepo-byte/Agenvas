package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmStreamLogCollector;
import dev.agenvas.audit.domain.LlmStreamLog;
import dev.agenvas.llm.application.PublicAssistantResponse;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.shared.http.DebugHttpCapture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 每个业务回合只调用一次 Spring AI，并关闭自动工具循环，由持久化 Runtime 管理工具执行。 */
public class SpringAiChatGateway implements ChatGateway {

    private static final int MAX_PUBLIC_BATCH_CHUNKS = 64;
    private static final long MAX_AGGREGATED_PROTOCOL_BYTES = 1024L * 1024;
    private static final int PROTOCOL_ENVELOPE_OVERHEAD_BYTES = 1024;
    private final ObjectMapper streamMapper = new ObjectMapper();
    private final LlmProtocolCodec streamCodec = new LlmProtocolCodec(streamMapper);
    private static final Duration PUBLIC_BATCH_INTERVAL = Duration.ofMillis(150);

    /** 不注册自动工具执行顾问的聊天客户端。 */
    private final ChatClient client;
    /** 提供模型能力选项和 Spring AI 请求执行。 */
    private final ChatModel model;
    /** 本次请求固定使用的 LLM 配置版本。 */
    private final int configVersion;
    /** Enabled only when the model's transport removes the internal capture header. */
    private final boolean captureHttp;

    /** 创建禁用自动工具循环的客户端，并拒绝无效配置版本。 */
    public SpringAiChatGateway(ChatModel model, int configVersion) {
        this(model, configVersion, false);
    }

    /** The supplied OpenAI model must install DebugHttpCapture.interceptor() in its transport. */
    static SpringAiChatGateway withDebugCapture(OpenAiChatModel model, int configVersion) {
        return new SpringAiChatGateway(model, configVersion, true);
    }

    private SpringAiChatGateway(ChatModel model, int configVersion, boolean captureHttp) {
        if (configVersion < 1) {
            throw new IllegalArgumentException("LLM configVersion must be positive");
        }
        this.model = model;
        this.client = ChatClient.builder(model)
                .defaultAdvisors(advisors -> advisors.param(
                        ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey(), false))
                .build();
        this.configVersion = configVersion;
        this.captureHttp = captureHttp;
    }

    private ChatClient.ChatClientRequestSpec prompt() {
        var request = client.prompt();
        if (captureHttp) {
            Map<String, String> headers = DebugHttpCapture.requestHeaders();
            if (!headers.isEmpty()) request.options(OpenAiChatOptions.builder().customHeaders(headers));
        }
        return request;
    }

    /** 提交一个有界消息回合并返回模型原始响应，不在此处执行工具调用。 */
    @Override
    public Exchange call(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext) {
        if (messages == null || messages.isEmpty() || messages.size() > 80
                || tools == null || toolContext == null) {
            throw new IllegalArgumentException("Invalid bounded ChatGateway round");
        }
        if (!tools.isEmpty() && !(model.getOptions() instanceof ToolCallingChatOptions)) {
            throw new IllegalStateException("Configured ChatModel does not support tool calling");
        }
        ChatResponse response = prompt()
                .messages(List.copyOf(messages))
                .tools(tools.toArray(ToolCallback[]::new))
                .toolContext(Map.copyOf(toolContext))
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("LLM returned no assistant response");
        }
        return new Exchange(configVersion, response);
    }

    /** Emits only sanitized public content and waits for the same stream's complete response. */
    @Override
    public Exchange callStreaming(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext, ConfigIdentity expected, Consumer<String> publicDelta) {
        return callStreaming(messages, tools, toolContext, expected, publicDelta, false, ignored -> {});
    }

    @Override
    public Exchange callStreaming(List<Message> messages, List<ToolCallback> tools,
            Map<String, Object> toolContext, ConfigIdentity expected, Consumer<String> publicDelta,
            boolean captureContent, Consumer<LlmStreamLog> streamLog) {
        if (!configIdentity().equals(expected)) {
            throw new IllegalStateException("ChatGateway configuration changed before model stream");
        }
        if (messages == null || messages.isEmpty() || messages.size() > 80
                || tools == null || toolContext == null || publicDelta == null || streamLog == null) {
            throw new IllegalArgumentException("Invalid bounded ChatGateway stream");
        }
        if (!tools.isEmpty() && !(model.getOptions() instanceof ToolCallingChatOptions)) {
            throw new IllegalStateException("Configured ChatModel does not support tool calling");
        }
        AtomicReference<LlmStreamLogCollector> collector = new AtomicReference<>();
        AtomicReference<SignalType> terminal = new AtomicReference<>();
        AtomicReference<ChatResponse> result = new AtomicReference<>();
        Throwable failure = null;
        // Bind HTTP capture on the caller before defer/SDK scheduling, never inside a common-pool continuation.
        var request = prompt().messages(List.copyOf(messages)).tools(tools.toArray(ToolCallback[]::new))
                .toolContext(Map.copyOf(toolContext));
        try {
            Flux.defer(() -> {
                LlmStreamLogCollector log = new LlmStreamLogCollector(captureContent);
                collector.set(log);
                StreamBudget budget = new StreamBudget();
                AtomicReference<Usage> reportedUsage = new AtomicReference<>(new EmptyUsage());
                Map<String, Object> responseAttributes = new LinkedHashMap<>();
                var chunks = request.stream().chatResponse().map(PublicAssistantResponse::sanitize).doOnNext(chunk -> {
                    budget.accept(chunk);
                    log.chunk(chunk);
                    Usage usage = chunk.getMetadata().getUsage();
                    if (hasPositiveUsage(usage)) reportedUsage.set(usage);
                    chunk.getMetadata().entrySet().forEach(entry -> responseAttributes.put(entry.getKey(), entry.getValue()));
                });
                AtomicReference<ChatResponse> completed = new AtomicReference<>();
                return new MessageAggregator().aggregate(chunks, completed::set)
                        .bufferTimeout(MAX_PUBLIC_BATCH_CHUNKS, PUBLIC_BATCH_INTERVAL).doOnNext(batch -> {
                            StringBuilder text = new StringBuilder();
                            batch.forEach(chunk -> text.append(PublicAssistantResponse.text(chunk)));
                            if (!text.isEmpty()) {
                                String delta = text.toString();
                                publicDelta.accept(delta);
                            }
                        }).doOnComplete(() -> {
                            if (completed.get() == null || completed.get().getResult() == null) {
                                throw new IllegalStateException("LLM stream returned no complete assistant response");
                            }
                            ChatResponse aggregated = completed.get();
                            ChatResponseMetadata metadata = ChatResponseMetadata.builder().metadata(responseAttributes)
                                    .id(aggregated.getMetadata().getId()).model(aggregated.getMetadata().getModel())
                                    .usage(reportedUsage.get()).rateLimit(aggregated.getMetadata().getRateLimit())
                                    .promptMetadata(aggregated.getMetadata().getPromptMetadata()).build();
                            ChatResponse response = PublicAssistantResponse.sanitize(new ChatResponse(aggregated.getResults(), metadata));
                            result.set(response);
                            log.complete(response);
                        }).doFinally(terminal::set);
            }).blockLast();
            return new Exchange(configVersion, result.get());
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            // doFinally can run after blockLast wakes. The method boundary owns exactly one snapshot,
            // after callbacks finish; an interrupted block is classified independently of that race.
            LlmStreamLogCollector log = collector.get();
            if (log != null) {
                LlmStreamLog.EndStatus status = failure == null ? LlmStreamLog.EndStatus.COMPLETED
                        : isCancellation(failure) || terminal.get() == SignalType.CANCEL
                        ? LlmStreamLog.EndStatus.CANCELED : LlmStreamLog.EndStatus.FAILED;
                try { streamLog.accept(log.snapshot(status, failure == null ? null :
                        status == LlmStreamLog.EndStatus.CANCELED ? "CALL_STREAM_CANCELED" : "CALL_STREAM_FAILED")); }
                catch (RuntimeException logFailure) {
                    // Semantic logging must never replace the model result or its original failure.
                    org.slf4j.LoggerFactory.getLogger(SpringAiChatGateway.class)
                            .error("LLM stream snapshot failed code=CALL_STREAM_SNAPSHOT_FAILED");
                }
            }
        }
    }

    private boolean isCancellation(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException) return true;
        }
        return false;
    }

    private boolean hasPositiveUsage(Usage usage) {
        return usage != null && (positive(usage.getPromptTokens()) || positive(usage.getCompletionTokens())
                || positive(usage.getTotalTokens()));
    }

    private boolean positive(Integer value) { return value != null && value > 0; }

    /** Counts retained text/tools and effective metadata, without charging repeated frame envelopes. */
    private final class StreamBudget {
        private long textBytes;
        private long toolBytes;
        private final ObjectNode assistantMetadata = streamMapper.createObjectNode();
        private final ObjectNode responseAttributes = streamMapper.createObjectNode();

        private void accept(ChatResponse chunk) {
            JsonNode encoded = streamCodec.response(chunk);
            JsonNode generation = encoded.path("generations").path(0);
            JsonNode assistant = generation.path("assistant");
            textBytes = Math.addExact(textBytes, assistant.path("text").asText("").getBytes(StandardCharsets.UTF_8).length);
            toolBytes = Math.addExact(toolBytes, size(assistant.path("toolCalls")));
            JsonNode attributes = encoded.path("metadata").path("attributes");
            // MessageAggregator also appends tool calls carried in response metadata.
            if (attributes.has("toolCalls")) toolBytes = Math.addExact(toolBytes, size(attributes.path("toolCalls")));
            merge(assistantMetadata, assistant.path("metadata"));
            merge(responseAttributes, attributes);
            long retainedBytes = textBytes + toolBytes + size(assistantMetadata) + size(responseAttributes)
                    + size(generation.path("metadata")) + PROTOCOL_ENVELOPE_OVERHEAD_BYTES;
            if (retainedBytes > MAX_AGGREGATED_PROTOCOL_BYTES) {
                throw new IllegalStateException("LLM public stream protocol exceeds size limit");
            }
        }

        private void merge(ObjectNode target, JsonNode values) {
            if (values.isObject()) values.propertyNames().forEach(name -> target.set(name, values.get(name)));
        }

        private int size(JsonNode value) {
            return value.toString().getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /** 根据 ChatModel 选项报告工具调用能力，视觉和原生结构化输出保持关闭。 */
    @Override
    public Capabilities capabilities() {
        return new Capabilities(model.getOptions() instanceof ToolCallingChatOptions,
                false, false);
    }

    /** 返回创建网关时固定的配置版本，供 LlmTurn 持久化来源身份。 */
    @Override
    public int configVersion() {
        return configVersion;
    }

    /** 标识响应来自 Spring AI 配置 Provider。 */
    @Override
    public String configSource() {
        return "spring-ai";
    }

    /** 提供管理员诊断使用的模型类名和可用模型 ID，不返回客户端凭证。 */
    @Override
    public ModelDetails modelDetails() {
        String modelId = model.getOptions() == null ? null : model.getOptions().getModel();
        return new ModelDetails(true, model.getClass().getSimpleName(), modelId,
                capabilities().toolCalling());
    }
}

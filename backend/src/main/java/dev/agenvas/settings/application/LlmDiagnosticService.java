package dev.agenvas.settings.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProtocolCodec;
import dev.agenvas.llm.application.LlmStreamIncompleteException;
import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import java.util.function.Consumer;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 经用户确认后执行最多两次模型调用，验证工具请求、结果回填和后续回复的完整往返。 */
@Service
public class LlmDiagnosticService {

    /** 诊断专用工具名，不访问项目数据，也不调用业务工具。 */
    private static final String PROBE_TOOL = "agenvas_connection_probe";
    /** 获取当前活动配置并在成功后写回能力验证状态。 */
    private final LlmProviderConfigRepository repository;
    /** 返回不含密钥的配置状态给调用方。 */
    private final LlmProviderConfigService configs;
    /** 使用指定配置创建本次诊断专用聊天网关。 */
    private final LlmDiagnosticGateway gatewayFactory;
    /** 验证模型返回的 nonce 参数是否为合法 JSON。 */
    private final ObjectMapper mapper;
    private final CallLogService callLogs;
    /** 进程内单飞闸门，避免并发触发多组可能计费的诊断请求。 */
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /** 注入配置读写与 Provider 网关工厂；诊断调用本身在方法内事务之外执行。 */
    public LlmDiagnosticService(LlmProviderConfigRepository repository,
            LlmProviderConfigService configs, LlmDiagnosticGateway gatewayFactory,
            ObjectMapper mapper, CallLogService callLogs) {
        this.repository = repository;
        this.configs = configs;
        this.gatewayFactory = gatewayFactory;
        this.mapper = mapper;
        this.callLogs = callLogs;
    }

    /** 网络诊断不占用数据库事务；结束时按配置版本条件更新能力状态。 */
    public LlmProviderConfigService.Status diagnose(int expectedVersion, boolean acknowledgeCost) {
        if (!acknowledgeCost || expectedVersion < 1) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST,
                    "PROVIDER_DIAGNOSTIC_CONFIRMATION_REQUIRED",
                    ApiMessage.of("api.llm-diagnostic-service.need-to-confirm-diagnostic-call"),
                    ApiMessage.of(
                            "api.llm-diagnostic-service.please-confirm-that-this-diagnostic-makes-up-to-two-potentially"),
                    false);
        }
        if (!inFlight.compareAndSet(false, true)) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "PROVIDER_DIAGNOSTIC_IN_PROGRESS",
                    ApiMessage.of("api.llm-diagnostic-service.model-diagnostics-running"),
                    ApiMessage.of(
                            "api.llm-diagnostic-service.please-wait-until-the-current-diagnosis-is-complete-and-try"),
                    true);
        }
        try {
            LlmProviderConfig config = repository.active().orElseThrow(this::changed);
            if (config.version() != expectedVersion)
                throw changed();
            if (config.toolCallingVerified())
                return configs.status();
            probe(config);
            if (!repository.markToolCallingVerified(expectedVersion))
                throw changed();
            return configs.status();
        } finally {
            inFlight.set(false);
        }
    }

    /** Diagnose a selected saved connection without switching the service used by new work. */
    public LlmProviderConfigService.ConnectionStatus diagnoseConnection(UUID id, int expectedVersion,
            boolean acknowledgeCost) {
        if (!acknowledgeCost || expectedVersion < 1) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "PROVIDER_DIAGNOSTIC_CONFIRMATION_REQUIRED",
                    ApiMessage.of("api.llm-diagnostic-service.need-to-confirm-diagnostic-call"),
                    ApiMessage.of(
                            "api.llm-diagnostic-service.please-confirm-that-this-diagnostic-makes-up-to-two-potentially"),
                    false);
        }
        if (!inFlight.compareAndSet(false, true)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "PROVIDER_DIAGNOSTIC_IN_PROGRESS",
                    ApiMessage.of("api.llm-diagnostic-service.model-diagnostics-running"),
                    ApiMessage.of(
                            "api.llm-diagnostic-service.please-wait-until-the-current-diagnosis-is-complete-and-try"),
                    true);
        }
        try {
            var head = repository.findConnection(id).orElseThrow(this::changed);
            if (head.currentConfigVersion() != expectedVersion)
                throw changed();
            var config = repository.findVersion(expectedVersion).orElseThrow(this::changed);
            if (!config.toolCallingVerified()) {
                probe(config);
                if (!repository.markConnectionToolCallingVerified(id, expectedVersion))
                    throw changed();
            }
            var result = configs.connection(id);
            if (result.version() != expectedVersion)
                throw changed();
            return result;
        } finally {
            inFlight.set(false);
        }
    }

    /** 要求模型发起唯一探针调用，再将工具结果回填并验证第二轮文本响应。 */
    private void probe(LlmProviderConfig config) {
        String nonce = UUID.randomUUID().toString();
        ToolCallback tool = new ProbeTool();
        ChatGateway gateway;
        try {
            gateway = gatewayFactory.open(config);
            List<Message> initial = List.of(
                    new SystemMessage("This is a connection diagnostic. Call the supplied probe tool "
                            + "exactly once. After its result, reply with only the proof value "
                            + "from the tool result."),
                    new UserMessage("Call agenvas_connection_probe with nonce " + nonce));
            var first = recordRound(config, gateway, initial, tool, 1, exchange -> {
                AssistantMessage assistant = output(exchange);
                if (assistant.getToolCalls().size() != 1) {
                    throw protocolFailure("PROVIDER_DIAGNOSTIC_NO_TOOL", "first-response-no-tool");
                }
                AssistantMessage.ToolCall call = assistant.getToolCalls().getFirst();
                if (!"function".equals(call.type()) || !PROBE_TOOL.equals(call.name())
                        || call.id() == null || call.id().isBlank()
                        || call.id().length() > 200 || !matchesArguments(call.arguments(), nonce)) {
                    throw protocolFailure("PROVIDER_DIAGNOSTIC_INVALID_TOOL", "first-response-invalid-tool");
                }
            });
            AssistantMessage assistant = output(first);
            AssistantMessage.ToolCall call = assistant.getToolCalls().getFirst();
            String resultProof = UUID.randomUUID().toString();
            ToolResponseMessage result = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(),
                            PROBE_TOOL, "{\"proof\":\"" + resultProof + "\"}")))
                    .build();
            recordRound(config, gateway, List.of(initial.get(0), initial.get(1), assistant, result), tool, 2,
                    exchange -> {
                        AssistantMessage finalMessage = output(exchange);
                        if (!finalMessage.getToolCalls().isEmpty() || finalMessage.getText() == null
                                || !resultProof.equals(finalMessage.getText().strip())) {
                            throw protocolFailure("PROVIDER_DIAGNOSTIC_PROOF_MISMATCH",
                                    "second-response-invalid-proof");
                        }
                    });
        } catch (ApiProblemException known) {
            // Preserve the public API category while each durable call retains its precise stage code.
            if (known.code().equals("PROVIDER_DIAGNOSTIC_NO_TOOL")
                    || known.code().equals("PROVIDER_DIAGNOSTIC_INVALID_TOOL")
                    || known.code().equals("PROVIDER_DIAGNOSTIC_PROOF_MISMATCH")
                    || known.code().equals("PROVIDER_DIAGNOSTIC_EMPTY_RESPONSE")) {
                throw new ApiProblemException(known.status(), "PROVIDER_TOOL_PROTOCOL_UNVERIFIED",
                        known.title(), known.detail(), known.retryable());
            }
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY,
                    "PROVIDER_DIAGNOSTIC_UNAVAILABLE",
                    ApiMessage.of("api.llm-diagnostic-service.model-diagnostic-call-failed"),
                    ApiMessage.of(
                            "api.llm-diagnostic-service.the-endpoint-did-not-complete-diagnostics-please-check-the-service"),
                    true);
        }
    }

    /** Log one actual request, including protocol failure, without giving it a fictitious project. */
    private ChatGateway.Exchange recordRound(LlmProviderConfig config, ChatGateway gateway,
            List<Message> messages, ToolCallback tool, int round, Consumer<ChatGateway.Exchange> validate) {
        var descriptor = new CallLogService.CallDescriptor(null, null, null, null,
                CallLog.Kind.LLM, CallLog.Operation.DIAGNOSTIC, "openai-compatible", config.modelId(), false);
        return callLogs.record(descriptor, () -> {
            ChatGateway.Exchange exchange;
            try {
                exchange = gateway.call(messages, List.of(tool), Map.of());
            } catch (ApiProblemException known) {
                if (LlmStreamIncompleteException.ERROR_CODE.equals(known.code())) {
                    throw new ApiProblemException(known.status(), known.code(), known.title(),
                            ApiMessage.of("api.llm-diagnostic-service.incomplete-stream-stage", round), false);
                }
                throw known;
            } catch (RuntimeException unavailable) {
                throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "PROVIDER_DIAGNOSTIC_UNAVAILABLE",
                        ApiMessage.of("api.llm-diagnostic-service.model-diagnostic-call-failed"),
                        ApiMessage.of("api.llm-diagnostic-service.call-failed-stage", round), true);
            }
            validate.accept(exchange);
            return exchange;
        }, exchange -> CallLogService.CallOutcome.succeeded(exchange.response().getMetadata().getId()));
    }

    private AssistantMessage output(ChatGateway.Exchange exchange) {
        if (exchange == null || exchange.response() == null || exchange.response().getResult() == null
                || exchange.response().getResult().getOutput() == null) {
            throw protocolFailure("PROVIDER_DIAGNOSTIC_EMPTY_RESPONSE", "empty-response");
        }
        if ("length".equalsIgnoreCase(exchange.response().getResult().getMetadata().getFinishReason())) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, LlmProtocolCodec.OUTPUT_TRUNCATED,
                    ApiMessage.of("api.llm-diagnostic-service.tool-agreement-failed"),
                    ApiMessage.of("api.llm-diagnostic-service.output-truncated"), false);
        }
        return exchange.response().getResult().getOutput();
    }

    private ApiProblemException protocolFailure(String code, String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, code,
                ApiMessage.of("api.llm-diagnostic-service.tool-agreement-failed"),
                ApiMessage.of("api.llm-diagnostic-service." + detail), false);
    }

    /** 限制参数长度并检查模型工具参数中的 nonce 与本次随机值完全一致。 */
    private boolean matchesArguments(String arguments, String nonce) {
        if (arguments == null || arguments.length() > 4_096)
            return false;
        try {
            var value = mapper.readTree(arguments);
            return value != null && value.isObject() && value.size() == 1
                    && value.path("nonce").isString() && nonce.equals(value.path("nonce").asText());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** 构造诊断期间配置版本被修改时使用的冲突响应。 */
    private ApiProblemException changed() {
        return new ApiProblemException(HttpStatus.CONFLICT,
                "PROVIDER_CONFIG_VERSION_CONFLICT",
                ApiMessage.of("api.llm-provider-config-service.model-configuration-has-changed"),
                ApiMessage.of("api.llm-diagnostic-service.please-re-read-the-model-configuration-before-diagnosing"),
                false);
    }

    /** Spring AI 回调若被自动执行即视为失败，确保工具结果只由诊断流程回填。 */
    private static final class ProbeTool implements ToolCallback {
        /** 描述仅接受 nonce 的诊断工具，拒绝额外参数或业务数据。 */
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(PROBE_TOOL)
                    .description("Return the diagnostic nonce; no project data is accessed")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"nonce\":{\"type\":\"string\"}},"
                            + "\"required\":[\"nonce\"],\"additionalProperties\":false}")
                    .build();
        }

        /** 禁止框架直接执行回调；诊断服务必须显式检查调用后自行回填结果。 */
        @Override
        public String call(String input) {
            throw new IllegalStateException("Diagnostic tool must be executed by the caller");
        }
    }

}

package dev.agenvas.settings.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.llm.application.ChatGateway;
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
    /** 进程内单飞闸门，避免并发触发多组可能计费的诊断请求。 */
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /** 注入配置读写与 Provider 网关工厂；诊断调用本身在方法内事务之外执行。 */
    public LlmDiagnosticService(LlmProviderConfigRepository repository,
            LlmProviderConfigService configs, LlmDiagnosticGateway gatewayFactory,
            ObjectMapper mapper) {
        this.repository = repository;
        this.configs = configs;
        this.gatewayFactory = gatewayFactory;
        this.mapper = mapper;
    }

    /** 网络诊断不占用数据库事务；结束时按配置版本条件更新能力状态。 */
    public LlmProviderConfigService.Status diagnose(int expectedVersion, boolean acknowledgeCost) {
        if (!acknowledgeCost || expectedVersion < 1) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST,
                    "PROVIDER_DIAGNOSTIC_CONFIRMATION_REQUIRED", ApiMessage.of("api.llm-diagnostic-service.need-to-confirm-diagnostic-call"),
                    ApiMessage.of("api.llm-diagnostic-service.please-confirm-that-this-diagnostic-makes-up-to-two-potentially"), false);
        }
        if (!inFlight.compareAndSet(false, true)) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "PROVIDER_DIAGNOSTIC_IN_PROGRESS", ApiMessage.of("api.llm-diagnostic-service.model-diagnostics-running"),
                    ApiMessage.of("api.llm-diagnostic-service.please-wait-until-the-current-diagnosis-is-complete-and-try"), true);
        }
        try {
            LlmProviderConfig config = repository.active().orElseThrow(this::changed);
            if (config.version() != expectedVersion) throw changed();
            if (config.toolCallingVerified()) return configs.status();
            probe(config);
            if (!repository.markToolCallingVerified(expectedVersion)) throw changed();
            return configs.status();
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
            var first = gateway.call(initial, List.of(tool), Map.of());
            AssistantMessage assistant = first.response().getResult().getOutput();
            if (assistant.getToolCalls().size() != 1) throw protocolFailure();
            AssistantMessage.ToolCall call = assistant.getToolCalls().getFirst();
            if (!PROBE_TOOL.equals(call.name()) || call.id() == null || call.id().isBlank()
                    || call.id().length() > 200
                    || !matchesArguments(call.arguments(), nonce)) {
                throw protocolFailure();
            }
            String resultProof = UUID.randomUUID().toString();
            ToolResponseMessage result = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(),
                            PROBE_TOOL, "{\"proof\":\"" + resultProof + "\"}")))
                    .build();
            var second = gateway.call(List.of(initial.get(0), initial.get(1), assistant, result),
                    List.of(tool), Map.of());
            AssistantMessage finalMessage = second.response().getResult().getOutput();
            if (!finalMessage.getToolCalls().isEmpty() || finalMessage.getText() == null
                    || !finalMessage.getText().contains(resultProof)) throw protocolFailure();
        } catch (DiagnosticProtocolFailure mismatch) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "PROVIDER_TOOL_PROTOCOL_UNVERIFIED", ApiMessage.of("api.llm-diagnostic-service.tool-agreement-failed"),
                    ApiMessage.of("api.llm-diagnostic-service.the-model-does-not-complete-complete-diagnostics-of-tool-requests"), false);
        } catch (ApiProblemException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY,
                    "PROVIDER_DIAGNOSTIC_UNAVAILABLE", ApiMessage.of("api.llm-diagnostic-service.model-diagnostic-call-failed"),
                    ApiMessage.of("api.llm-diagnostic-service.the-endpoint-did-not-complete-diagnostics-please-check-the-service"), true);
        }
    }

    /** 创建协议不匹配异常，以便和端点或网络故障分别映射错误码。 */
    private DiagnosticProtocolFailure protocolFailure() {
        return new DiagnosticProtocolFailure();
    }

    /** 限制参数长度并检查模型工具参数中的 nonce 与本次随机值完全一致。 */
    private boolean matchesArguments(String arguments, String nonce) {
        if (arguments == null || arguments.length() > 4_096) return false;
        try {
            return nonce.equals(mapper.readTree(arguments).path("nonce").asText());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** 构造诊断期间配置版本被修改时使用的冲突响应。 */
    private ApiProblemException changed() {
        return new ApiProblemException(HttpStatus.CONFLICT,
                "PROVIDER_CONFIG_VERSION_CONFLICT", ApiMessage.of("api.llm-provider-config-service.model-configuration-has-changed"),
                ApiMessage.of("api.llm-diagnostic-service.please-re-read-the-model-configuration-before-diagnosing"), false);
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

    /** 标记模型没有遵守工具往返协议，与传输或 Provider 错误区分。 */
    private static final class DiagnosticProtocolFailure extends RuntimeException {}
}

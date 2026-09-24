package dev.agenvas.settings.application;

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

/** Explicit billable probe that verifies tool request, tool result and second model reply. */
@Service
public class LlmDiagnosticService {

    private static final String PROBE_TOOL = "agenvas_connection_probe";
    private final LlmProviderConfigRepository repository;
    private final LlmProviderConfigService configs;
    private final LlmDiagnosticGateway gatewayFactory;
    private final ObjectMapper mapper;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public LlmDiagnosticService(LlmProviderConfigRepository repository,
            LlmProviderConfigService configs, LlmDiagnosticGateway gatewayFactory,
            ObjectMapper mapper) {
        this.repository = repository;
        this.configs = configs;
        this.gatewayFactory = gatewayFactory;
        this.mapper = mapper;
    }

    /** Never runs in a database transaction; the final capability update is version-guarded. */
    public LlmProviderConfigService.Status diagnose(int expectedVersion, boolean acknowledgeCost) {
        if (!acknowledgeCost || expectedVersion < 1) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST,
                    "PROVIDER_DIAGNOSTIC_CONFIRMATION_REQUIRED", "需要确认诊断调用",
                    "请确认此诊断会向模型端点发起最多两次可能计费的请求。", false);
        }
        if (!inFlight.compareAndSet(false, true)) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "PROVIDER_DIAGNOSTIC_IN_PROGRESS", "模型诊断正在运行",
                    "请等待当前诊断结束后再试。", true);
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
                    "PROVIDER_TOOL_PROTOCOL_UNVERIFIED", "工具协议未通过",
                    "模型未完成工具请求、结果回填与下一轮响应的完整诊断。", false);
        } catch (ApiProblemException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY,
                    "PROVIDER_DIAGNOSTIC_UNAVAILABLE", "模型诊断调用失败",
                    "端点未完成诊断；请检查服务地址、凭证、模型和网络后重试。", true);
        }
    }

    private DiagnosticProtocolFailure protocolFailure() {
        return new DiagnosticProtocolFailure();
    }

    private boolean matchesArguments(String arguments, String nonce) {
        if (arguments == null || arguments.length() > 4_096) return false;
        try {
            return nonce.equals(mapper.readTree(arguments).path("nonce").asText());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private ApiProblemException changed() {
        return new ApiProblemException(HttpStatus.CONFLICT,
                "PROVIDER_CONFIG_VERSION_CONFLICT", "模型配置已变化",
                "请重新读取模型配置后再诊断。", false);
    }

    /** Any accidental Spring AI auto tool execution is a diagnostic failure. */
    private static final class ProbeTool implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(PROBE_TOOL)
                    .description("Return the diagnostic nonce; no project data is accessed")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"nonce\":{\"type\":\"string\"}},"
                            + "\"required\":[\"nonce\"],\"additionalProperties\":false}")
                    .build();
        }

        @Override
        public String call(String input) {
            throw new IllegalStateException("Diagnostic tool must be executed by the caller");
        }
    }

    /** Distinguishes a model-protocol mismatch from a transport failure. */
    private static final class DiagnosticProtocolFailure extends RuntimeException {}
}

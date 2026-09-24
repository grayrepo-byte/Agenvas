package dev.agenvas.llm.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** One caller-driven model round; the only network call occurs between committed checkpoints. */
@Service
public class LlmRoundService {

    private final ChatGateway gateway;
    private final LlmProtocolCodec codec;
    private final LlmTurnCheckpointService checkpoints;
    private final AgentRunRepository runs;

    public LlmRoundService(ChatGateway gateway,
            LlmProtocolCodec codec, LlmTurnCheckpointService checkpoints,
            AgentRunRepository runs) {
        this.gateway = gateway;
        this.codec = codec;
        this.checkpoints = checkpoints;
        this.runs = runs;
    }

    /** Returns only a durably recorded response; tool execution belongs to the later Runtime. */
    public JsonNode call(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            List<Message> messages, List<ToolCallback> tools, Map<String, Object> trustedContext) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Model calls must not hold a database transaction");
        }
        JsonNode request = codec.request(messages, tools);
        AgentRun run = runs.find(ownerId, projectId, runId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RUN_NOT_FOUND", "运行不存在", "无法访问该项目的运行。", false));
        ChatGateway.ConfigIdentity selected = new ChatGateway.ConfigIdentity(
                run.policySnapshot().path("modelConfigSource").asText(""),
                run.policySnapshot().path("modelConfigVersion").asInt(-1));
        gateway.requireToolCalling(selected);
        int configVersion = selected.version();
        String configSource = selected.source();
        LlmTurn turn = checkpoints.reserve(ownerId, projectId, runId, stepIndex,
                configVersion, configSource, request);
        if (turn.status() == LlmTurn.Status.RESPONDED) {
            return turn.response();
        }
        ChatGateway.Exchange exchange = gateway.call(messages, tools, trustedContext, selected);
        if (exchange.configVersion() != turn.modelConfigVersion()) {
            throw new IllegalStateException("ChatGateway configuration changed during model call");
        }
        JsonNode response = codec.response(exchange.response());
        return checkpoints.saveResponse(ownerId, projectId, runId, stepIndex,
                exchange.configVersion(), response).response();
    }
}

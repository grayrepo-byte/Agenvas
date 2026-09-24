package dev.agenvas.llm.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Run 与步骤唯一的模型请求、响应检查点持久化边界。 */
public interface LlmTurnRepository {

    /** 每个步骤只插入一次请求；检查点已存在时返回 false。 */
    boolean insertRequested(UUID projectId, UUID runId, int stepIndex,
            int modelConfigVersion, JsonNode request, Instant now);

    /** 在认证用户可访问的 Run 项目内读取单个步骤检查点。 */
    Optional<LlmTurn> find(UUID projectId, UUID runId, int stepIndex);

    /** 完整响应仅保存一次；已有竞争写入成功时返回 false。 */
    boolean saveResponse(UUID projectId, UUID runId, int stepIndex,
            JsonNode response, Instant now);
}

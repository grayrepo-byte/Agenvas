package dev.agenvas.agent.api;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creator 项目助手配置的 REST 边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class AgentInstanceController {

    /** 执行项目权限、字段校验和配置版本控制。 */
    private final AgentInstanceService agents;

    /** 注入负责项目授权和配置版本控制的用例服务。
     * @param agents 项目助手应用服务
     */
    public AgentInstanceController(AgentInstanceService agents) {
        this.agents = agents;
    }

    /** 无需预设选择或命令键即可恢复项目唯一导演；初始化不启动 Run。 */
    @PutMapping("/director")
    public AgentResponse ensureDirector(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestBody(required = false) String requestBody) {
        if (requestBody != null && !requestBody.isBlank()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.agent-instance-service.agent-configuration-is-invalid"),
                    ApiMessage.of("api.agent-instance-service.agent-configuration-is-invalid"), false);
        }
        return AgentResponse.from(agents.ensureDirector(principal.userId(), projectId));
    }

    /** 读取项目路径下的单个 项目助手。
     * @param principal 当前认证用户
     * @param projectId 助手所属项目
     * @param agentId 助手 UUID
     * @return 助手配置
     */
    @GetMapping("/agents/{agentId}")
    public AgentResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID agentId) {
        return AgentResponse.from(agents.get(principal.userId(), projectId, agentId));
    }

    /** 整体替换助手名称和指令；版本冲突由应用服务拒绝。
     * @param principal 当前认证用户
     * @param projectId 助手所属项目
     * @param agentId 要更新的助手
     * @param request 新完整配置及客户端读取到的版本
     * @return 更新后的助手快照
     */
    @PatchMapping("/agents/{agentId}")
    public AgentResponse update(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID agentId,
            @Valid @RequestBody UpdateAgentRequest request) {
        return AgentResponse.from(agents.update(
                principal.userId(),
                projectId,
                agentId,
                request.expectedVersion(),
                request.name(),
                request.instruction()));
    }

    /**
     * 配置整体替换请求；expectedVersion 用于拒绝覆盖并发编辑。
     *
     * @param expectedVersion 客户端最近读取到的 Agent 配置版本
     * @param name 替换后的展示名称
     * @param instruction 替换后的 Agent 指令
     */
    public record UpdateAgentRequest(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 8000) String instruction) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknownField(String field, tools.jackson.databind.JsonNode value) {
            throw new IllegalArgumentException("Unknown assistant settings field");
        }
    }

    /** 项目助手公开投影，不包含某次运行的模型对话或 Worker 状态。
     * @param id 助手 UUID
     * @param projectId 所属项目 UUID
     * @param profileKey 内置 Agent 能力配置键
     * @param profileVersion 能力配置版本
     * @param name 用户设置的助手名称
     * @param instruction 运行时提供给 Agent 的指令
     * @param version 助手配置并发版本
     * @param createdAt 创建时间
     * @param updatedAt 最近配置更新时间
     */
    public record AgentResponse(
            UUID id,
            UUID projectId,
            String profileKey,
            int profileVersion,
            String name,
            String instruction,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        /** 将领域助手配置映射为 API 响应。
         * @param instance 已完成项目范围校验的领域助手
         * @return 公开配置投影
         */
        public static AgentResponse from(AgentInstance instance) {
            return new AgentResponse(
                    instance.id(),
                    instance.projectId(),
                    instance.profileKey(),
                    instance.profileVersion(),
                    instance.name(),
                    instance.instruction(),
                    instance.version(),
                    instance.createdAt(),
                    instance.updatedAt());
        }
    }

}

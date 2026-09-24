package dev.agenvas.run.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Run 预检、幂等创建、状态读取和取消的 REST 边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class AgentRunController {

    /** 执行项目权限、幂等、版本核对和 Run 状态转换。 */
    private final AgentRunService runs;

    /** 注入 Run 预检、持久化创建和状态转换服务。
     * @param runs Agent Run 应用服务
     */
    public AgentRunController(AgentRunService runs) {
        this.runs = runs;
    }

    /** 用户确认前展示模型可用性、预算策略和 Agent 固定输入。 */
    @GetMapping("/preflight")
    public AgentRunService.RunPreflight preflight(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam UUID agentId) {
        return runs.preflight(principal.userId(), projectId, agentId);
    }

    /** 按一个 Agent 的所有者范围返回有界历史页和不透明续页游标。 */
    @GetMapping
    public RunListResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam UUID agentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        AgentRunService.RunPage page = runs.list(principal.userId(), projectId,
                agentId, cursor, limit);
        return new RunListResponse(page.items().stream().map(RunSummary::from).toList(),
                page.nextCursor());
    }

    /** 固定请求键与用户预览版本，原子占用项目活动槽位并返回持久化 Run。 */
    @PostMapping
    public ResponseEntity<RunResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRunRequest request) {
        AgentRunService.CreateResult result = runs.create(
                principal.userId(),
                projectId,
                request.agentId(),
                request.instruction(),
                idempotencyKey,
                request.expectedAgentVersion(),
                request.redoShotArtifactId(),
                request.selectedItemIds(),
                request.expectedModelConfigSource(),
                request.expectedModelConfigVersion(),
                request.expectedSystemPromptVersion());
        return ResponseEntity.accepted()
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(RunResponse.from(result.run()));
    }

    /** 返回当前持久化状态及创建时固定的上下文、策略快照。 */
    @GetMapping("/{runId}")
    public RunResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return RunResponse.from(runs.get(principal.userId(), projectId, runId));
    }

    /** 停止后续本地编排并释放项目活动槽位；不声称外部 Provider 已停止。 */
    @PostMapping("/{runId}/cancel")
    public RunResponse cancel(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return RunResponse.from(runs.cancel(principal.userId(), projectId, runId));
    }

    /**
     * 创建 Run 时客户端可提交的指令与预览版本。
     * 身份、执行策略、预算和授权上下文均由服务端构造，不接受客户端提供。
     *
     * @param agentId 用户选择的 Agent 卡片
     * @param instruction 本次 Run 指令，最多 20,000 字符
     * @param expectedAgentVersion 用户预览时的 Agent 配置版本
     * @param redoShotArtifactId 可选局部重做目标镜头
     * @param selectedItemIds 可选画布选择，只作为意图
     * @param expectedModelConfigSource 用户预览的模型配置来源
     * @param expectedModelConfigVersion 用户预览的模型配置版本
     * @param expectedSystemPromptVersion 用户预览的系统规则版本
     */
    public record CreateRunRequest(
            @NotNull UUID agentId,
            @NotBlank @Size(max = 20_000) String instruction,
            @jakarta.validation.constraints.PositiveOrZero Long expectedAgentVersion,
            UUID redoShotArtifactId,
            @Size(max = 20) List<@NotNull UUID> selectedItemIds,
            @Size(max = 80) String expectedModelConfigSource,
            @jakarta.validation.constraints.Positive Integer expectedModelConfigVersion,
            @jakarta.validation.constraints.Positive Integer expectedSystemPromptVersion) {}

    /** 列表摘要省略完整上下文、策略细节和模型原始消息。
     * @param id Run UUID
     * @param agentInstanceId 发起 Run 的 Agent 卡片
     * @param status 当前运行状态
     * @param instruction 本次固定用户指令
     * @param createdAt Run 创建时间
     * @param updatedAt 最近状态更新时间
     * @param completedAt 进入终态的时间；仍在运行时为空
     */
    public record RunSummary(UUID id, UUID agentInstanceId, AgentRun.Status status,
            String instruction, Instant createdAt, Instant updatedAt, Instant completedAt) {
        /** 提取列表展示所需字段，不复制模型消息或策略快照。
         * @param run 已授权的领域 Run
         * @return 列表摘要
         */
        public static RunSummary from(AgentRun run) {
            return new RunSummary(run.id(), run.agentInstanceId(), run.status(),
                    run.instruction(), run.createdAt(), run.updatedAt(), run.completedAt());
        }
    }

    /**
     * 一页按创建时间倒序排列的 Run 摘要。
     *
     * @param items 当前页摘要
     * @param nextCursor 后续历史游标；无下一页时为空
     */
    public record RunListResponse(List<RunSummary> items, String nextCursor) {}

    /**
     * 对外 Run 状态；不含模型私有推理和凭证。
     *
     * @param id Run ID
     * @param projectId 所属项目
     * @param agentInstanceId 发起运行的 Agent
     * @param status 当前编排状态
     * @param instruction 固定用户指令
     * @param contextSnapshot 创建时固定的项目、绑定和选择快照
     * @param policySnapshot 创建时固定的模型配置和预算策略
     * @param profileVersion 使用的 Agent 档案版本
     * @param nextStepIndex 下一模型回合序号
     * @param version Run 状态乐观锁版本
     * @param createdAt 创建时间
     * @param updatedAt 最近更新时间
     * @param completedAt 终态完成时间；运行中为空
     */
    public record RunResponse(
            UUID id,
            UUID projectId,
            UUID agentInstanceId,
            AgentRun.Status status,
            String instruction,
            JsonNode contextSnapshot,
            JsonNode policySnapshot,
            int profileVersion,
            int nextStepIndex,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        /** 将持久化 Run 快照映射为 API 表示。 */
        public static RunResponse from(AgentRun run) {
            return new RunResponse(
                    run.id(),
                    run.projectId(),
                    run.agentInstanceId(),
                    run.status(),
                    run.instruction(),
                    run.contextSnapshot(),
                    run.policySnapshot(),
                    run.profileVersion(),
                    run.nextStepIndex(),
                    run.version(),
                    run.createdAt(),
                    run.updatedAt(),
                    run.completedAt());
        }
    }
}

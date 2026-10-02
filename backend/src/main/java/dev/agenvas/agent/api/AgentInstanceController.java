package dev.agenvas.agent.api;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creator Agent 卡片配置与精确输入版本绑定的 REST 边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agents")
public class AgentInstanceController {

    /** 执行项目权限、字段校验和配置版本控制。 */
    private final AgentInstanceService agents;

    /** 注入负责项目授权、绑定校验和配置版本控制的用例服务。
     * @param agents Agent 卡片应用服务
     */
    public AgentInstanceController(AgentInstanceService agents) {
        this.agents = agents;
    }

    /** 创建 Creator Agent 卡片；只有请求中明确绑定的版本才会进入其输入范围。
     * @param principal 当前认证用户
     * @param projectId 卡片所属项目
     * @param request 卡片名称、指令及明确选择的输入绑定
     * @return 新建卡片和已固定版本的绑定
     */
    @PostMapping
    public ResponseEntity<AgentResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateAgentRequest request) {
        AgentInstance instance = agents.create(
                principal.userId(),
                projectId,
                request.name(),
                request.instruction(),
                toInputs(request.bindings()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentResponse.from(instance));
    }

    /** 列出项目内的 Agent 卡片及各自固定的输入版本。
     * @param principal 当前认证用户
     * @param projectId 项目 UUID
     * @return 按服务结果投影的卡片列表
     */
    @GetMapping
    public AgentListResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return new AgentListResponse(agents.list(principal.userId(), projectId).stream()
                .map(AgentResponse::from)
                .toList());
    }

    /** 读取项目路径下的单个 Agent 卡片。
     * @param principal 当前认证用户
     * @param projectId 卡片所属项目
     * @param agentId 卡片 UUID
     * @return 卡片配置及输入绑定
     */
    @GetMapping("/{agentId}")
    public AgentResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID agentId) {
        return AgentResponse.from(agents.get(principal.userId(), projectId, agentId));
    }

    /** 整体替换卡片名称、指令和输入绑定；版本冲突由应用服务拒绝。
     * @param principal 当前认证用户
     * @param projectId 卡片所属项目
     * @param agentId 要更新的卡片
     * @param request 新完整配置及客户端读取到的版本
     * @return 更新后的卡片快照
     */
    @PatchMapping("/{agentId}")
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
                request.instruction(),
                toInputs(request.bindings())));
    }

    /** 将 HTTP DTO 转为应用服务输入；此处不做资源授权。
     * @param bindings 请求中明确提交的产物与版本
     * @return 应用服务使用的绑定输入列表
     */
    private List<AgentInstanceService.BindingInput> toInputs(List<AgentBindingRequest> bindings) {
        return bindings.stream()
                .map(binding -> new AgentInstanceService.BindingInput(
                        binding.artifactId(), binding.selectedVersionId()))
                .toList();
    }

    /**
     * 创建内置 Creator Agent 的完整请求；bindings 不允许省略或超过 40 项。
     *
     * @param name 展示名称，长度为 1 至 120 字符
     * @param instruction 每次运行可见的 Agent 指令，长度不超过 8,000 字符
     * @param bindings 用户显式选择的输入产物版本列表
     */
    public record CreateAgentRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 8000) String instruction,
            @NotNull @Size(max = 40) List<@Valid AgentBindingRequest> bindings) {}

    /**
     * 配置整体替换请求；expectedVersion 用于拒绝覆盖并发编辑。
     *
     * @param expectedVersion 客户端最近读取到的 Agent 配置版本
     * @param name 替换后的展示名称
     * @param instruction 替换后的 Agent 指令
     * @param bindings 替换后的完整输入绑定集合，不会与旧集合合并
     */
    public record UpdateAgentRequest(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 8000) String instruction,
            @NotNull @Size(max = 40) List<@Valid AgentBindingRequest> bindings) {}

    /**
     * 绑定时选定的不可变产物版本，服务层还会重新校验其项目归属。
     *
     * @param artifactId 被引用产物 ID
     * @param selectedVersionId 被选中的不可变版本 ID
     */
    public record AgentBindingRequest(
            @NotNull UUID artifactId, @NotNull UUID selectedVersionId) {}

    /** Agent 卡片公开投影，不包含某次运行的模型对话或 Worker 状态。
     * @param id 卡片 UUID
     * @param projectId 所属项目 UUID
     * @param profileKey 内置 Agent 能力配置键
     * @param profileVersion 能力配置版本
     * @param name 用户设置的卡片名称
     * @param instruction 运行时提供给 Agent 的指令
     * @param outputGroupId Agent 新产物写入的产物组
     * @param version 卡片配置并发版本
     * @param createdAt 创建时间
     * @param updatedAt 最近配置更新时间
     * @param bindings 明确选择的输入版本
     */
    public record AgentResponse(
            UUID id,
            UUID projectId,
            String profileKey,
            int profileVersion,
            String name,
            String instruction,
            UUID outputGroupId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<BindingResponse> bindings) {

        /** 将领域卡片和精确输入绑定映射为 API 响应。
         * @param instance 已完成项目范围校验的领域卡片
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
                    instance.outputGroupId(),
                    instance.version(),
                    instance.createdAt(),
                    instance.updatedAt(),
                    instance.bindings().stream().map(BindingResponse::from).toList());
        }
    }

    /** Agent 输入绑定的公开表示。
     * @param id 绑定关系 UUID
     * @param artifactId 被引用产物
     * @param selectedVersionId 固定的不可变产物版本
     */
    public record BindingResponse(
            UUID id,
            UUID artifactId,
            UUID selectedVersionId) {

        /** 复制绑定身份与精确版本。
         * @param binding 领域绑定关系
         * @return 公开绑定投影
         */
        static BindingResponse from(AgentInstance.Binding binding) {
            return new BindingResponse(
                    binding.id(),
                    binding.artifactId(),
                    binding.selectedVersionId());
        }
    }

    /** 项目 Agent 列表响应。
     * @param items 当前项目的卡片
     */
    public record AgentListResponse(List<AgentResponse> items) {}
}

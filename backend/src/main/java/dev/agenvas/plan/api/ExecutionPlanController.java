package dev.agenvas.plan.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.plan.application.ExecutionPlan;
import dev.agenvas.plan.application.ExecutionPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 执行计划的人审入口；模型只能提出计划，审批只能由已认证用户完成。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ExecutionPlanController {

    /** 负责项目授权、计划读取及审批状态转换。 */
    private final ExecutionPlanService plans;

    /** 注入计划应用服务。
     * @param plans 执行计划查询与人工审批服务
     */
    public ExecutionPlanController(ExecutionPlanService plans) {
        this.plans = plans;
    }

    /** 列出 Run 的计划修订，支持客户端重连后恢复待审提案。
     * @param principal 当前认证用户
     * @param projectId 计划所属项目
     * @param runId 产生计划的 Run
     * @return 按修订顺序排列的计划
     */
    @GetMapping("/runs/{runId}/plans")
    public List<ExecutionPlan> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return plans.listByRun(principal.userId(), projectId, runId);
    }

    /** 读取冻结的计划提案、摘要和服务端估算。
     * @param principal 当前认证用户
     * @param projectId 计划所属项目
     * @param planId 计划 UUID
     * @return 可供用户审核的不可变计划
     */
    @GetMapping("/plans/{planId}")
    public ExecutionPlan get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID planId) {
        return plans.get(principal.userId(), projectId, planId);
    }

    /** 仅批准与用户所见摘要完全一致的计划。
     * @param principal 作出审批的认证用户
     * @param projectId 计划所属项目
     * @param planId 待审计划
     * @param request UI 展示给用户的计划摘要
     * @return 审批结果及服务端创建的执行任务
     */
    @PostMapping("/plans/{planId}/approve")
    public ExecutionPlanService.ApprovalResult approve(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID planId,
            @Valid @RequestBody ApproveRequest request) {
        return plans.approve(principal.userId(), projectId, planId, request.planHash());
    }

    /** 拒绝待审计划，不创建媒体任务。
     * @param principal 作出拒绝决定的认证用户
     * @param projectId 计划所属项目
     * @param planId 待拒绝计划
     * @return 拒绝后的计划状态
     */
    @PostMapping("/plans/{planId}/reject")
    public ExecutionPlan reject(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID planId) {
        return plans.reject(principal.userId(), projectId, planId);
    }

    /** 审批命令；计划摘要用于阻止用户确认旧页面内容。
     * @param planHash 当前展示计划的 SHA-256 十六进制摘要
     */
    public record ApproveRequest(@NotBlank @Pattern(regexp = "[0-9a-f]{64}") String planHash) {}
}

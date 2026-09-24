package dev.agenvas.export.api;

import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.task.api.TaskController.TaskResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Agent 导出提案的人工作出决定入口；Agent 本身没有批准或拒绝提案的工具。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/export-proposals")
public class ExportProposalController {

    /** 负责项目授权、提案状态转换和任务幂等创建。 */
    private final ExportProposalService proposals;

    /** 注入导出提案用例服务。
     * @param proposals 提案查询、审批和拒绝服务
     */
    public ExportProposalController(ExportProposalService proposals) {
        this.proposals = proposals;
    }

    /** 查询项目的近期导出提案。
     * @param principal 当前认证用户
     * @param projectId 要查询的项目
     * @return 项目提案列表
     */
    @GetMapping
    public List<ProposalResponse> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return proposals.list(principal.userId(), projectId).stream()
                .map(ProposalResponse::from).toList();
    }

    /** 读取用户即将审批或拒绝的不可变提案快照。
     * @param principal 当前认证用户
     * @param projectId 提案所属项目
     * @param proposalId 提案 UUID
     * @return 精确提案内容及其审批摘要
     */
    @GetMapping("/{proposalId}")
    public ProposalResponse get(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID proposalId) {
        return ProposalResponse.from(proposals.get(principal.userId(), projectId, proposalId));
    }

    /** 仅批准与用户当前看到的摘要匹配的提案，并返回持久化导出任务。
     * @param principal 作出批准决定的认证用户
     * @param projectId 提案所属项目
     * @param proposalId 要批准的提案
     * @param request UI 展示给用户的提案摘要
     * @return 提案决定、对应任务及是否为幂等重放
     */
    @PostMapping("/{proposalId}/approve")
    public ApprovalResponse approve(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID proposalId,
            @Valid @RequestBody ApproveRequest request) {
        ExportProposalService.Approval approved = proposals.approve(principal.userId(),
                projectId, proposalId, request.proposalHash());
        return new ApprovalResponse(ProposalResponse.from(approved.proposal()),
                TaskResponse.from(approved.task()), approved.replayed());
    }

    /** 拒绝待审提案；拒绝路径不会创建执行任务。
     * @param principal 作出拒绝决定的认证用户
     * @param projectId 提案所属项目
     * @param proposalId 要拒绝的提案
     * @return 已拒绝提案快照
     */
    @PostMapping("/{proposalId}/reject")
    public ProposalResponse reject(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID proposalId) {
        return ProposalResponse.from(proposals.reject(principal.userId(),
                projectId, proposalId));
    }

    /** 提案批准命令；摘要将决定绑定到实际展示给用户的内容。
     * @param proposalHash 规范化提案内容的 64 位十六进制摘要
     */
    public record ApproveRequest(@NotBlank @Pattern(regexp = "[0-9a-f]{64}")
            String proposalHash) {}

    /** 对外提案内容，不暴露内部 CAS 固定项和审批用户标识。
     * @param id 提案 UUID
     * @param runId 生成提案的 Agent Run
     * @param status 当前审核状态
     * @param input 有序且受范围限制的导出媒体与时间区间
     * @param proposalHash 当前展示内容的审批摘要
     * @param projectVersion 提案创建时项目版本
     * @param approvedTaskId 批准后产生的导出任务；未批准时为空
     * @param createdAt 提案创建时间
     * @param decidedAt 作出审批决定的时间；待审时为空
     */
    public record ProposalResponse(UUID id, UUID runId, ExportProposal.Status status,
            JsonNode input, String proposalHash, long projectVersion, UUID approvedTaskId,
            Instant createdAt, Instant decidedAt) {
        /** 将领域提案映射为仅含 UI 所需字段的投影。
         * @param proposal 领域提案快照
         * @return 不暴露内部固定项的响应对象
         */
        public static ProposalResponse from(ExportProposal proposal) {
            return new ProposalResponse(proposal.id(), proposal.runId(), proposal.status(),
                    proposal.input(), proposal.proposalHash(), proposal.projectVersion(),
                    proposal.approvedTaskId(), proposal.createdAt(), proposal.decidedAt());
        }
    }

    /** 提案审批结果及其幂等信息。
     * @param proposal 决定后的提案
     * @param task 唯一对应的持久化导出任务
     * @param replayed 本次请求是否复用了先前创建的任务
     */
    public record ApprovalResponse(ProposalResponse proposal, TaskResponse task,
            boolean replayed) {}
}

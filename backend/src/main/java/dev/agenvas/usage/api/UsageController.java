package dev.agenvas.usage.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.usage.application.UsageService;
import dev.agenvas.usage.domain.UsageEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 项目用量账本查询入口；金额用十进制字符串返回，未知金额保持为空。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/usage")
public class UsageController {

    /** 按所有者和项目范围读取用量账本。 */
    private final UsageService usage;

    /** 注入账本查询服务。
     * @param usage 执行项目授权并读取账本的应用服务
     */
    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    /** 查询项目内的预留、结算和冲正记录。
     * @param principal 当前认证用户
     * @param projectId 要查询的项目；服务会同时校验所有者范围
     * @return 按账本记录返回的用量条目
     */
    @GetMapping
    public List<UsageResponse> list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return usage.listProject(principal.userId(), projectId).stream()
                .map(UsageResponse::from).toList();
    }

    /** 对外账本条目，不包含 Provider 凭据或内部配置。
     * @param id 账本条目 UUID
     * @param runId 关联的 Agent Run；系统级条目可为空
     * @param taskId 关联的执行任务；尚未对应任务时为空
     * @param operationKey 可用于关联同一逻辑操作各账本记录的键
     * @param entryType 预留、结算或调整等记录类型
     * @param quantity Provider 用量明细；结构依用量类型而定
     * @param estimatedCost 预估金额；未知时为空
     * @param actualCost Provider 报告的实际金额；未知时为空
     * @param currency 金额币种；未知时为空
     * @param costStatus 金额处于预估、确认或未知等状态
     * @param costSource 金额来源说明
     * @param providerConfigVersion 模型配置版本或媒体任务固定的连接版本
     * @param workflowVersion 执行时使用的工作流模板版本
     * @param modelId 执行时记录的模型标识
     * @param createdAt 账本记录创建时间
     */
    public record UsageResponse(UUID id, UUID runId, UUID taskId, String operationKey,
            UsageEntry.EntryType entryType, JsonNode quantity,
            String estimatedCost, String actualCost, String currency,
            UsageEntry.CostStatus costStatus, String costSource,
            Integer providerConfigVersion, String workflowVersion, String modelId,
            Instant createdAt) {

        /** 映射账本领域对象，并将 BigDecimal 金额转换为无精度损失的字符串。
         * @param entry 已授权项目中的账本记录
         * @return 稳定的公开响应对象
         */
        static UsageResponse from(UsageEntry entry) {
            return new UsageResponse(entry.id(), entry.runId(), entry.taskId(),
                    entry.operationKey(), entry.entryType(), entry.quantity(),
                    entry.estimatedCost() == null ? null
                            : entry.estimatedCost().toPlainString(),
                    entry.actualCost() == null ? null : entry.actualCost().toPlainString(),
                    entry.currency(), entry.costStatus(), entry.costSource(),
                    entry.providerConfigVersion(), entry.workflowVersion(),
                    entry.modelId(), entry.createdAt());
        }
    }
}

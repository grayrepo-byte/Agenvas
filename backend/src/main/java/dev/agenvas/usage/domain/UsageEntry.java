package dev.agenvas.usage.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 不可变的用量账目；费用字段为空表示未知，不等同于零费用。
 * @param id 账目 ID
 * @param projectId 归属项目 ID
 * @param runId 关联 Agent Run；用户直连请求时为空
 * @param taskId 关联持久任务；模型回合账目时为空
 * @param operationKey 幂等键，同一逻辑账目不可重复写入
 * @param entryType 预留、结算或释放动作
 * @param quantity 按媒体类型和模型请求记录的用量数量
 * @param estimatedCost 估算费用；未知时为空
 * @param actualCost 实际费用；Provider 未报告时为空
 * @param currency 费用币种；费用未知时为空
 * @param costStatus 费用信息可信度状态
 * @param costSource 用量来源或价格来源标记
 * @param providerConfigVersion 模型请求使用的配置版本，或媒体任务固定的连接版本
 * @param workflowVersion 生成时使用的固定工作流版本；不适用时为空
 * @param modelId 已报告的模型 ID；Provider 未报告时为空
 * @param createdAt 账目写入时间
 */
public record UsageEntry(UUID id, UUID projectId, UUID runId, UUID taskId,
        String operationKey, EntryType entryType, JsonNode quantity,
        BigDecimal estimatedCost, BigDecimal actualCost, String currency,
        CostStatus costStatus, String costSource, Integer providerConfigVersion,
        String workflowVersion, String modelId, Instant createdAt) {

    /** 只追加的账本动作类型。 */
    public enum EntryType {
        /** 预留后续任务或请求的计划用量。 */
        RESERVATION,
        /** 持久模型响应或媒体结果确认后结算用量。 */
        SETTLEMENT,
        /** 已确认未提交或未产出时释放先前预留。 */
        RELEASE
    }

    /** 费用已知程度，与是否记录了实际用量相互独立。 */
    public enum CostStatus {
        /** Provider 报告或账本已确认准确费用。 */
        KNOWN,
        /** 依据公开费率计算的估算费用。 */
        ESTIMATED,
        /** 没有可信价格数据，金额字段保持为空。 */
        UNKNOWN
    }
}

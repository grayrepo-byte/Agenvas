package dev.agenvas.export.application;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 导出提案的不可变输入快照；审核状态与 Agent Run 分离，批准后才允许创建导出任务。
 * @param id 提案 UUID
 * @param projectId 所属项目 UUID
 * @param runId 创建提案的 Agent Run
 * @param status 等待审核、已批准或已拒绝
 * @param input 规范化后的导出请求内容
 * @param inputPins 创建提案时固定的输入版本引用
 * @param proposalHash 输入快照摘要，用于审批时确认内容未变
 * @param projectVersion 创建时的项目版本
 * @param approvedTaskId 批准后创建的导出任务；未批准时为空
 * @param decidedByUserId 作出审批决定的用户；尚未决定时为空
 * @param createdAt 提案创建时间
 * @param decidedAt 审批决定时间；尚未决定时为空
 */
public record ExportProposal(UUID id, UUID projectId, UUID runId, Status status,
        JsonNode input, JsonNode inputPins, String proposalHash, long projectVersion,
        UUID approvedTaskId, UUID decidedByUserId, Instant createdAt, Instant decidedAt) {

    /** 提案审核状态；只有经过认证的用户审批才能使提案进入可执行状态。 */
    public enum Status {
        /** 等待用户审核；此状态不能直接创建导出任务。 */
        PENDING,
        /** 用户批准，且已关联唯一导出任务。 */
        APPROVED,
        /** 用户拒绝，不能再转换为可执行提案。 */
        REJECTED,
        /** 升级后的旧毫秒提案已失效，必须重新生成并审批。 */
        STALE
    }
}

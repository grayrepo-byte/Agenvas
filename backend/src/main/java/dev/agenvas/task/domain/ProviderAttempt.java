package dev.agenvas.task.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次外部提交尝试的持久化检查点，用原请求键及返回标识核对是否已产生副作用。
 *
 * @param id 本次提交尝试的 ID
 * @param taskId 所属任务 ID
 * @param status 本次尝试的提交状态，不等同于生成任务的最终状态
 * @param requestKey 网络调用前保存的稳定请求键
 * @param candidateRequestId 适配器可提供的候选外部请求 ID，用于提交结果丢失时核对
 * @param candidateOriginSha256 候选请求 ID 所属 Provider origin 的摘要，防止跨端点误核对
 * @param providerRequestId Provider 已确认返回的原请求 ID
 * @param createdAt 提交尝试检查点的创建时间
 * @param updatedAt 最近一次核对或状态变化时间
 */
public record ProviderAttempt(UUID id, UUID taskId, Status status, UUID requestKey,
        UUID candidateRequestId, String candidateOriginSha256, String providerRequestId,
        Instant createdAt, Instant updatedAt) {

    /** 提交尝试状态；未知仅表示需核对，不代表可以重新生成。 */
    public enum Status {
        /** 提交前检查点已保存，尚无可确认的受理结果。 */
        SUBMITTING,
        /** Provider 已明确受理并返回原请求 ID。 */
        ACCEPTED,
        /** 受理结果不明，必须先查询原请求或人工处理。 */
        UNKNOWN,
        /** Provider 已明确拒绝本次提交。 */
        REJECTED
    }
}

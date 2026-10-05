package dev.agenvas.provider.domain;

/** Provider 适配器返回的受限生成状态，不包含媒体字节或任意 Provider 正文。
 * @param status 同步结果或异步受理状态
 * @param providerRequestId Provider 为已受理请求分配的原始 ID
 * @param demoOutput 结果是否为明确标识的演示媒体
 * @param errorCode 失败状态的稳定错误码；无错误时为空
 */
public record GenerationResult(
        Status status, String providerRequestId, boolean demoOutput, String errorCode) {

    /** 提交或生成请求的受限终态分类。 */
    public enum Status {
        /** Provider 已完成生成并提供可归档结果。 */
        COMPLETED,
        /** Provider 已受理异步请求，后续须按原 ID 查询。 */
        ACCEPTED,
        /** Provider 明确拒绝或报告终止失败。 */
        FAILED,
        /** 提交结果存在歧义，不能据此自动再次提交。 */
        UNKNOWN
    }
}

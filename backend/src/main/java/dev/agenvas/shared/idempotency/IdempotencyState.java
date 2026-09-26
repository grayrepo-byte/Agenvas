package dev.agenvas.shared.idempotency;

/** `idempotency_record.state` 列取值；Run 与 Artifact 的幂等键共用同一张表与同一套状态。 */
public enum IdempotencyState {
    /** 首次命令已占用键，业务结果尚未提交。 */
    IN_PROGRESS,
    /** 原结果已提交，同键同载荷可直接重放。 */
    COMPLETED
}

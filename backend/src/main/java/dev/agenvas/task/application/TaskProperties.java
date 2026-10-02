package dev.agenvas.task.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据库任务调度的有界配置。
 *
 * @param leaseDuration 单次 Worker 租约时长；续租失败后旧 Worker 必须放弃写回
 * @param maxClaimBatch 单次扫描可认领的任务上限，限制同一轮负载
 */
@ConfigurationProperties(prefix = "agenvas.task")
public record TaskProperties(Duration leaseDuration, int maxClaimBatch) {

    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_CLAIM_BATCH = 16;
    private static final int MAX_CLAIM_BATCH = 100;

    /** 缺省租约为 30 秒、批量上限为 16，并拒绝非正租约或超过 100 的批量配置。 */
    public TaskProperties {
        leaseDuration = leaseDuration == null ? DEFAULT_LEASE_DURATION : leaseDuration;
        maxClaimBatch = maxClaimBatch == 0 ? DEFAULT_MAX_CLAIM_BATCH : maxClaimBatch;
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || maxClaimBatch < 1
                || maxClaimBatch > MAX_CLAIM_BATCH) {
            throw new IllegalArgumentException("Invalid task lease or claim batch configuration");
        }
    }
}

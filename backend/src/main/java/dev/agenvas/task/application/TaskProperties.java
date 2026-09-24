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

    /** 缺省租约为 30 秒、批量上限为 16，并拒绝非正租约或超过 100 的批量配置。 */
    public TaskProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        maxClaimBatch = maxClaimBatch == 0 ? 16 : maxClaimBatch;
        if (leaseDuration.isNegative()
                || leaseDuration.isZero()
                || maxClaimBatch < 1
                || maxClaimBatch > 100) {
            throw new IllegalArgumentException("Invalid task lease or claim batch configuration");
        }
    }
}

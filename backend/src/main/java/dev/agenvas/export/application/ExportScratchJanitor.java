package dev.agenvas.export.application;

import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 清理停止的导出任务遗留的过期工作目录，并跳过仍被文件锁占用的目录。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.export", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ExportScratchJanitor {

    /** 清理只记录任务结果和异常类别，不泄漏文件内容。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ExportScratchJanitor.class);
    /** 工作目录至少保留一天，给迟到的 Worker 和排障留出时间。 */
    private static final Duration RETENTION = Duration.ofHours(24);
    /** 枚举过期候选并尊重仍持有的 OS 文件锁。 */
    private final LocalAssetStorage storage;
    /** 使用可注入时钟计算截止时间，避免依赖系统时区。 */
    private final Clock clock;

    /** 注入本地归档清理操作和统一时钟。
     * @param storage 负责安全枚举和删除闲置导出目录的存储组件
     * @param clock 计算保留期限的时钟
     */
    public ExportScratchJanitor(LocalAssetStorage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
    }

    /** 每小时限量扫描；OS 文件锁仍有效的目录不会被清理。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void tick() {
        try {
            int removed = storage.cleanupStaleExportWorkDirectories(
                    clock.instant().minus(RETENTION), 100);
            if (removed > 0) LOGGER.info("Removed {} stale export workspaces", removed);
        } catch (RuntimeException failure) {
            LOGGER.error("Export scratch cleanup failed: {}", failure.getClass().getSimpleName());
        }
    }
}

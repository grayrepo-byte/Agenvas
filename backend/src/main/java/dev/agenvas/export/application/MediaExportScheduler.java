package dev.agenvas.export.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 浏览器断开后仍由后台 Worker 继续执行已持久化的本地导出。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.export", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MediaExportScheduler {

    /** 记录单轮失败类别，具体异常由任务状态和诊断记录表达。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaExportScheduler.class);
    /** 执行项目级导出任务的 Worker。 */
    private final MediaExportWorker worker;
    /** 每个应用实例独有，供数据库租约识别 Worker 身份。 */
    private final String workerId = "media-export-" + UUID.randomUUID();

    /** 注入单次导出任务处理器。
     * @param worker 执行数据库任务的媒体导出 Worker
     */
    public MediaExportScheduler(MediaExportWorker worker) {
        this.worker = worker;
    }

    /** 每轮至多认领一个项目级导出任务，不占用 HTTP 请求线程。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Media export scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}

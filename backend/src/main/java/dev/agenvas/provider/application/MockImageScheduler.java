package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在服务端独立处理已批准的持久图片任务，不要求浏览器保持连接。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnBean(MockImageWorker.class)
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "mock", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas.provider.mock", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MockImageScheduler {

    /** 记录调度故障类型，不包含任务参数或图像内容。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(MockImageScheduler.class);
    /** 认领并生成演示图片，然后把结果归档到本地资产卷。 */
    private final MockImageWorker worker;
    private final LegacyMediaImportService importer;
    /** 此调度器专用的图片任务租约身份。 */
    private final String workerId = "mock-image-" + UUID.randomUUID();

    /** 注入 Mock 图片 Worker 并分配唯一租约身份。 */
    public MockImageScheduler(MockImageWorker worker, LegacyMediaImportService importer) {
        this.worker = worker;
        this.importer = importer;
    }

    /** 处理 Worker 限定的任务批次；提交结果不明确时保留 UNKNOWN 等待人工重试。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        if (!importer.ready()) return;
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Mock image scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}

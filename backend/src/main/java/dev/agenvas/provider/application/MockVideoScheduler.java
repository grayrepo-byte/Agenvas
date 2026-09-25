package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立推进已批准的 Mock 视频任务，不依赖浏览器连接或图片调度器。 */
@Component
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "mock", matchIfMissing = true)
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnBean(MockVideoWorker.class)
@ConditionalOnProperty(prefix = "agenvas.provider.mock", name = "video-scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MockVideoScheduler {

    /** 记录调度故障类型，不输出媒体输入或生成内容。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(MockVideoScheduler.class);
    /** 认领并执行一个已批准的演示视频任务。 */
    private final MockVideoWorker worker;
    /** 此调度器专用的持久任务租约身份。 */
    private final String workerId = "mock-video-" + UUID.randomUUID();

    /** 注入 Mock Worker 并为此调度器建立唯一认领身份。 */
    public MockVideoScheduler(MockVideoWorker worker) {
        this.worker = worker;
    }

    /** 每轮最多认领一个视频任务，Worker 以租约 epoch 隔离过期执行者。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        try {
            worker.runOnce(workerId);
        } catch (RuntimeException failure) {
            LOGGER.error("Mock video scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}

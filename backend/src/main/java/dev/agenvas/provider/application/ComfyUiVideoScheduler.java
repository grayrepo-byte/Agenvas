package dev.agenvas.provider.application;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立推进已审批的固定模板 ComfyUI 视频任务，先轮询旧请求再尝试新提交。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui.video", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ComfyUiVideoScheduler {

    /** 仅记录调度轮次故障类型，不写任务输入或 Provider 响应正文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ComfyUiVideoScheduler.class);
    /** 按已受理请求 ID 查询视频生成状态。 */
    private final ComfyUiVideoPoller poller;
    /** 可选提交器；视频模板禁用时不创建提交 Worker。 */
    private final ObjectProvider<ComfyUiVideoWorker> submitter;
    private final LegacyMediaImportService importer;
    /** 本实例轮询认领任务时使用的唯一 Worker ID。 */
    private final String pollerId = "comfy-video-poll-" + UUID.randomUUID();
    /** 本实例提交认领任务时使用的独立 Worker ID。 */
    private final String submitterId = "comfy-video-submit-" + UUID.randomUUID();

    /** 注入轮询器和按需提供的提交器，使历史核对与新提交使用不同认领身份。 */
    public ComfyUiVideoScheduler(ComfyUiVideoPoller poller,
            ObjectProvider<ComfyUiVideoWorker> submitter,
            LegacyMediaImportService importer) {
        this.poller = poller;
        this.submitter = submitter;
        this.importer = importer;
    }

    /** 先查询已保存请求，再尝试占用共享提交槽处理新的已批准任务。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        if (!importer.ready()) return;
        try {
            poller.pollOnce(pollerId);
            ComfyUiVideoWorker enabledSubmitter = submitter.getIfAvailable();
            if (enabledSubmitter != null) enabledSubmitter.submitOnce(submitterId);
        } catch (RuntimeException failure) {
            LOGGER.error("ComfyUI video scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}

package dev.agenvas.provider.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立于浏览器连接和模型回合推进已审批的 ComfyUI 图片任务。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(name = "agenvas.provider.mode", havingValue = "comfyui")
@ConditionalOnProperty(prefix = "agenvas.provider.comfyui", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ComfyUiImageScheduler {

    /** 记录单轮调度错误类别，不输出可能包含请求内容的异常消息。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ComfyUiImageScheduler.class);
    /** 执行单次图片提交或轮询，不持有调度器级数据库事务。 */
    private final ComfyUiImageWorker worker;
    private final LegacyMediaImportService importer;
    /** 本实例轮询租约使用的唯一 Worker 身份。 */
    private final String pollerId = "comfy-image-poll-" + UUID.randomUUID();
    /** 本实例新提交租约使用的唯一 Worker 身份。 */
    private final String submitterId = "comfy-image-submit-" + UUID.randomUUID();

    /** 注入独立处理图片提交和查询的 Worker。
     * @param worker 图片 Provider 工作单元
     */
    public ComfyUiImageScheduler(ComfyUiImageWorker worker,
            LegacyMediaImportService importer) {
        this.worker = worker;
        this.importer = importer;
    }

    /** 先轮询已保存的原请求，再尝试认领一个新任务；不同请求没有共享容量门禁。 */
    @Scheduled(initialDelay = 1_000, fixedDelay = 5_000)
    public void tick() {
        if (!importer.ready()) return;
        try {
            worker.pollOnce(pollerId);
            worker.submitOnce(submitterId);
        } catch (RuntimeException failure) {
            LOGGER.error("ComfyUI image scheduler pass failed: {}",
                    failure.getClass().getSimpleName());
        }
    }
}

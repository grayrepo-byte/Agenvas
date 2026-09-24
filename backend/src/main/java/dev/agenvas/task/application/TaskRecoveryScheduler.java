package dev.agenvas.task.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期扫描过期提交与取消租约；将不确定外部提交归为 UNKNOWN，不重新请求 Provider。 */
@Component
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
public class TaskRecoveryScheduler {

    /** 记录恢复数量或本轮扫描错误，不输出任务输入。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRecoveryScheduler.class);
    /** 按项目短事务执行状态恢复。 */
    private final TaskService tasks;
    /** 将每轮扫描限制在配置的认领批量内。 */
    private final TaskProperties properties;

    /** 注入短事务状态恢复服务及每轮处理上限。
     * @param tasks 扫描并条件恢复过期任务
     * @param properties 提供有界扫描批次大小
     */
    /** 注入短事务恢复操作和每轮扫描上限。
     * @param tasks 查询并条件修复过期任务状态
     * @param properties 限制每轮数据库恢复数量
     */
    public TaskRecoveryScheduler(TaskService tasks, TaskProperties properties) {
        this.tasks = tasks;
        this.properties = properties;
    }

    /** 每五秒执行一轮有界扫描；本轮失败只记录日志，下轮再核对仍符合条件的记录。 */
    @Scheduled(fixedDelay = 5_000)
    public void scan() {
        try {
            int recovered = tasks.recoverExpiredSubmissions(properties.maxClaimBatch());
            int canceled = tasks.recoverExpiredCancellations(properties.maxClaimBatch());
            if (recovered > 0) {
                LOGGER.warn("Classified {} expired provider submissions as UNKNOWN", recovered);
            }
            if (canceled > 0) {
                LOGGER.info("Closed {} expired canceled local task leases", canceled);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Provider submission recovery scan failed", failure);
        }
    }
}

package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;
import java.util.UUID;

/** Provider 专属的只读原请求核对入口；有充分证据时恢复原任务的轮询。 */
public interface UnknownTaskReconciler {

    /**
     * 查询原提交记录及 Provider 状态，不发送新的生成请求；证据不足时保持任务不变。
     *
     * @param ownerId 经认证的任务所有者
     * @param projectId 原 UNKNOWN 任务所属项目
     * @param taskId 需要核对的原任务 ID
     * @return 是否已恢复原请求，以及核对后的任务快照
     */
    Result reconcile(UUID ownerId, UUID projectId, UUID taskId);

    /** 面向 API 的最小核对结果，不携带 Provider 原始历史或私有任务输入。
     * @param outcome 表示无证据或已恢复同一原请求
     * @param task 经重新读取的持久化任务状态
     */
    record Result(Outcome outcome, Task task) {}

    /** 原请求核对结论。 */
    enum Outcome {
        /** Provider 未提供足够证据；不能据此认定原请求不存在。 */
        NO_EVIDENCE,
        /** 已找到原请求并恢复同一任务的后续轮询。 */
        RESUMED
    }
}

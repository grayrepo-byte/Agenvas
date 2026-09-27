package dev.agenvas.task.application;

import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.ProviderAttempt;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 任务持久化边界；依赖关系、租约和外部提交检查点均在数据库中约束。 */
public interface TaskRepository {

    /** 原子写入任务及已由应用层校验的同项目依赖。 */
    void create(Task task, List<UUID> dependencyIds);

    /** Pin a newly created media Task to the exact approved connection and capability versions. */
    void bindMediaTask(UUID taskId, MediaCapabilityBinding binding);

    /** Read the exact approved identity; null indicates a pre-migration legacy task. */
    Optional<MediaCapabilityBinding> mediaBinding(UUID taskId);

    /** Serialize the final pre-submission check with admin updates of both catalog rows. */
    boolean lockCurrentMediaBinding(MediaCapabilityBinding binding);

    /** 保存媒体任务创建时所见的产物选择，供结果归档时做并发前提检查。 */
    void createArtifactTarget(ArtifactTarget target);

    /** 读取提交前固定的输出目标；Worker 不得自行选择当前产物。 */
    Optional<ArtifactTarget> findArtifactTarget(UUID taskId);

    /** A queued, executing, or unresolved request still owns this card's execution slot. */
    Optional<Task> findOccupyingMediaTask(UUID projectId, UUID artifactId);

    /** Direct requests use a project-scoped immutable command key. */
    Optional<Task> findDirectByStepKey(UUID ownerId, UUID projectId, String stepKey);

    /** Recent direct requests for one stable media Artifact, newest first. */
    List<Task> listDirectForArtifact(UUID ownerId, UUID projectId, UUID artifactId);

    /** All nonterminal direct work, independent of the project's AgentRun slot. */
    List<Task> listActiveDirect(UUID ownerId, UUID projectId);

    /** Advisory queue view; counts may change between refreshes. */
    QueueStatus queueStatus(UUID taskId);

    /** Cancel an unsubmitted direct task; submitted work requires provider reconciliation. */
    boolean cancelQueuedDirect(UUID projectId, UUID taskId, Instant now);

    /** 按所有者和项目共同限定任务读取。 */
    Optional<Task> find(UUID ownerId, UUID projectId, UUID taskId);

    /** 在所有者作用域内读取原任务的外部提交尝试。 */
    List<ProviderAttempt> listProviderAttempts(UUID ownerId, UUID projectId, UUID taskId);

    /** 查询用户明确承担重复请求风险后建立的替代任务关系。 */
    Optional<ManualReplacement> findManualReplacement(UUID projectId, UUID originalTaskId);

    /** 写入新任务或用量预留前检查 HTTP 幂等键是否已被使用。 */
    Optional<ManualReplacement> findManualReplacementByKey(UUID projectId, UUID ownerId,
            String idempotencyKey);

    /** 在新任务插入的同一事务中记录原 UNKNOWN 任务与替代任务的不可变关联。 */
    void createManualReplacement(ManualReplacement replacement);

    /** 读取原任务固定的执行依赖，供替代任务沿用。 */
    List<UUID> dependencyIds(UUID projectId, UUID taskId);

    /** 查找尚未执行、需要改为依赖替代任务的下游任务。 */
    List<Task> dependentTasks(UUID projectId, UUID taskId);

    /** 仅重连 PENDING 下游任务，并增加版本以支持事件重放。 */
    List<Task> rewirePendingDependents(UUID projectId, UUID originalTaskId,
            UUID replacementTaskId, Instant now);

    /** 供带租约条件的内部状态转换读取任务，不作为对外鉴权入口。 */
    Optional<Task> findById(UUID taskId);

    /** 为 Worker 的事件性状态变更读取数据库中的权威项目所有者。 */
    Optional<UUID> ownerId(UUID taskId);

    /** 按固定顺序返回已鉴权 Run 的任务列表。 */
    List<Task> listByRun(UUID ownerId, UUID projectId, UUID runId);

    /** 即使 Run 已释放活动槽位，也保留并查询未解决的 UNKNOWN 任务。 */
    List<Task> listUnknown(UUID ownerId, UUID projectId);

    /** 在调用方已锁定的 Run 下统计媒体尝试次数，供预算上限校验。 */
    long countByRunAndKind(UUID projectId, UUID runId, Task.Kind kind);

    /** 使用 SKIP LOCKED 原子认领到期的非 Agent 任务，避免多个 Worker 重复领取。 */
    List<Task> claimDue(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Claim only new media tasks pinned to a published fixed adapter. */
    List<Task> claimDueBoundMedia(String workerId, int limit, Instant now, Instant leaseUntil);

    /** Poll only accepted requests from bound media tasks. */
    List<Task> claimDueBoundMediaPolls(String workerId, int limit, Instant now, Instant leaseUntil);

    /** 仅认领 Mock 图片适配器能处理的到期图片任务。 */
    List<Task> claimDueImages(String workerId, int limit, Instant now, Instant leaseUntil);

    /** 经数据库持久化的单外部任务槽位串行化 ComfyUI 图片认领。 */
    List<Task> claimDueComfyImage(String workerId, Instant now, Instant leaseUntil);

    /** ComfyUI 视频与图片提交共用同一个持久化槽位。 */
    List<Task> claimDueComfyVideo(String workerId, Instant now, Instant leaseUntil);

    /** 按客户端固定命令键查找项目导出，供幂等重放。 */
    Optional<Task> findExportByStepKey(UUID ownerId, UUID projectId, String stepKey);

    /** 查询项目导出历史，不依赖活动 Agent Run。 */
    List<Task> listExports(UUID ownerId, UUID projectId);

    /** 对 READY 导出直接取消，对运行中导出原子记录取消请求。 */
    boolean requestExportCancellation(UUID projectId, UUID taskId, Instant now);

    /** 只认领到期的项目级导出任务。 */
    List<Task> claimDueExports(String workerId, int limit, Instant now, Instant leaseUntil);

    /** 只认领已批准且当前视频适配器可处理的任务。 */
    List<Task> claimDueVideos(String workerId, int limit, Instant now, Instant leaseUntil);

    /** 只认领已保存外部请求 ID 的轮询任务，不认领新提交。 */
    List<Task> claimDueProviderPolls(String workerId, int limit, Instant now, Instant leaseUntil);

    /** 将 ComfyUI 图片轮询限制为图片任务，不消费视频或其他 Provider 请求。 */
    List<Task> claimDueComfyImagePolls(String workerId, int limit, Instant now,
            Instant leaseUntil);

    /** 只为视频适配器认领已受理的 ComfyUI 视频请求。 */
    List<Task> claimDueComfyVideoPolls(String workerId, int limit, Instant now,
            Instant leaseUntil);

    /** 独立认领持久化模型回合，避免媒体 Worker 消费 LLM 工作。 */
    List<Task> claimDueAgentTurns(String workerId, int limit, Instant now, Instant leaseUntil);

    /** 业务副作用前锁定并核验活动模型回合租约；旧 epoch、过期或取消均返回 false。 */
    boolean lockActiveAgentTurnLease(UUID projectId, UUID runId, UUID taskId,
            String workerId, long leaseEpoch, Instant now);

    /** 仅当前 Worker、epoch 和未过期租约同时匹配时续租。 */
    boolean heartbeat(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Instant now,
            Instant leaseUntil);

    /** 仅当前未过期租约可写入终态输出或错误码。 */
    boolean finish(
            UUID taskId,
            String workerId,
            long leaseEpoch,
            Task.Status terminalStatus,
            JsonNode output,
            String errorCode,
            Instant now);

    /** 对尚未提交的过期媒体输入记 BLOCKED，而非误记为 Provider 执行失败。 */
    boolean blockStaleInput(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now);

    /** 仅所有前置任务都成功时将 PENDING 下游任务推进 READY。 */
    int promoteReady(UUID projectId, UUID runId, Instant now);

    /** 仅当前未取消租约可在网络调用前保存 SUBMITTING 和请求键。 */
    boolean beginSubmission(UUID taskId, String workerId, long leaseEpoch, UUID attemptId,
            UUID requestKey, String candidateOriginSha256, Instant now);

    /** 保存 Provider 已受理的原请求 ID，并将任务转为等待轮询；不再次提交。 */
    boolean acknowledgeSubmission(UUID taskId, String workerId, long leaseEpoch,
            String providerRequestId, Instant nextActionAt, Instant now);

    /** 释放当前轮询租约并保留原请求 ID，安排下一次查询。 */
    boolean deferProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            Instant nextActionAt, Instant now);

    /** 查询同一已受理原请求的连续技术失败次数。 */
    int providerPollFailureCount(UUID taskId);

    /** 在带租约条件的任务转换事务内记录轮询重试诊断。 */
    void recordProviderPollFailure(UUID taskId, int count, String errorCode, Instant now);

    /** Provider 查询成功或最终归档后清除连续失败历史。 */
    void clearProviderPollFailures(UUID taskId);

    /** 配置漂移导致无法安全查询时阻断轮询，但保留已受理请求。 */
    boolean blockProviderPoll(UUID taskId, String workerId, long leaseEpoch,
            String errorCode, Instant now);

    /** 无锁扫描过期提交检查点，再逐项目执行带事件的条件恢复。 */
    List<ExpiredSubmission> findExpiredSubmissions(Instant now, int limit);

    /** 仅检查点仍过期且状态未变化时将其归为 UNKNOWN。 */
    boolean recoverExpiredSubmission(UUID taskId, Instant now);

    /** 找出取消后 Worker 消失、未确认停止的过期本地任务。 */
    List<ExpiredSubmission> findExpiredCanceledRunning(Instant now, int limit);

    /** 仅对取消请求已持久化且提交前租约已过期的任务执行本地关闭。 */
    boolean finishExpiredCanceledRunning(UUID taskId, Instant now);

    /** 保存取消后晚到结果供历史核查，不改写任务当前输出。 */
    boolean recordLateResult(Task lease, JsonNode output, Instant now);

    /** 晚到结果已留档后，使用原租约将取消中的任务置为终态。 */
    boolean finishCanceled(Task lease, String workerId, Instant now);

    /** 带租约条件地提交同步媒体生成的成功结果。 */
    boolean finishSubmitting(Task lease, String workerId, JsonNode output, Instant now);

    /** 查询已受理原请求后，以当前轮询租约提交最终结果。 */
    boolean finishProviderResult(Task lease, String workerId, JsonNode output, Instant now);

    /** 只有 Provider 明确拒绝时，才以当前租约写入提交终态失败。 */
    boolean rejectSubmission(Task lease, String workerId, String errorCode, Instant now);

    /**
     * 无法确认外部是否受理或完成时，以当前租约立即写入 UNKNOWN 与具体原因码。
     *
     * <p>与 {@code recoverExpiredSubmission} 互补：这里要求租约仍然有效，由 Worker 在拿到
     * 确定结论的那一刻写入；那边只处理租约已过期（进程被杀）的兜底。两者靠租约是否过期
     * 互斥，任一方先成功，另一方条件不再成立。
     *
     * @param errorCode 可区分原因码，直接展示给用户，不得是笼统的提交未知
     */
    boolean markSubmissionUnknown(Task lease, String workerId, String errorCode, Instant now);

    /** 任务创建时固定的目标产物与版本前提；归档不得越过用户后续编辑。
     * @param taskId 产生该输出的任务
     * @param projectId 目标产物所属项目
     * @param artifactId 任务被批准修改的产物
     * @param expectedCurrentVersionId 创建任务时选中的当前版本；为空表示当时尚无版本
     * @param expectedArtifactVersion 创建任务时产物的版本号，用于归档 CAS
     * @param outputSlotKey 产物内部输出槽位，区分同一产物的可并行内容位置
     */
    record ArtifactTarget(UUID taskId, UUID projectId, UUID artifactId,
            UUID expectedCurrentVersionId, long expectedArtifactVersion,
            String outputSlotKey) {}

    /** Queue position is advisory; a different capability or cancellation can change it. */
    record QueueStatus(long waitingAhead, String reason) {}

    /** 用户明确接受一次重复费用风险后形成的不可变原任务与替代任务关联。
     * @param projectId 关系所在项目
     * @param originalTaskId 状态为 UNKNOWN 的原任务
     * @param replacementTaskId 用户手动重提后创建的替代任务
     * @param approvedByUserId 明确接受重复费用风险的用户
     * @param originalTaskVersion 执行替代时原任务的版本
     * @param idempotencyKey 防止同一确认请求重复创建替代任务的键
     * @param createdAt 关系写入时间
     */
    record ManualReplacement(UUID projectId, UUID originalTaskId, UUID replacementTaskId,
            UUID approvedByUserId, long originalTaskVersion, String idempotencyKey,
            Instant createdAt) {}

    /** 过期提交恢复所需的任务、项目和所有者作用域。
     * @param taskId 提交检查点过期的任务
     * @param projectId 任务所属项目
     * @param ownerId 项目所有者，用于恢复事件的归属与授权边界
     */
    record ExpiredSubmission(UUID taskId, UUID projectId, UUID ownerId) {}
}

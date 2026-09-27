package dev.agenvas.task.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 持久化执行单元。输入在创建时固定，Worker 只凭当前租约和递增的 fencing epoch 写回状态。
 *
 * @param id 任务 ID，用于定位同一次业务执行
 * @param projectId 所属项目 ID，查询和状态写入均受项目边界约束
 * @param runId 所属 Agent Run ID；项目级导出任务可为空
 * @param planId 产生任务的已审批计划 ID；非计划任务可为空
 * @param stepKey 计划或项目命令中的稳定步骤键，参与任务幂等约束
 * @param kind 执行类型，决定可使用的 Worker 和状态路径
 * @param status 数据库中的当前任务状态
 * @param cancelRequested 是否已请求停止本系统后续编排，不表示外部请求已取消
 * @param input 创建时固定的 JSON 输入，Worker 不读取之后的用户草稿
 * @param inputHash 固定输入的摘要，用于识别同键异参
 * @param output 已确认的执行结果；未完成时为空
 * @param providerId 创建任务时选定的 Provider 配置 ID；本地任务可为空
 * @param providerRequestId Provider 已确认受理的原请求 ID，轮询只能使用该值
 * @param attemptNo 同一业务步骤的尝试序号
 * @param nextActionAt 下次允许认领或查询原请求的时间
 * @param leaseOwner 当前 Worker 标识；没有活动租约时为空
 * @param leaseUntil 当前租约的到期时间
 * @param leaseEpoch 每次接管递增的 fencing epoch，旧 Worker 不得凭旧值回写
 * @param version 任务行的乐观锁版本
 * @param errorCode 失败或阻断时的稳定错误码
 * @param createdAt 创建时间
 * @param updatedAt 最近一次状态变化时间
 * @param completedAt 进入终态的时间；非终态时为空
 */
public record Task(
        UUID id,
        UUID projectId,
        UUID runId,
        UUID planId,
        String stepKey,
        Kind kind,
        Status status,
        boolean cancelRequested,
        JsonNode input,
        String inputHash,
        JsonNode output,
        UUID providerId,
        String providerRequestId,
        int attemptNo,
        Instant nextActionAt,
        String leaseOwner,
        Instant leaseUntil,
        long leaseEpoch,
        long version,
        String errorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    /** Worker 可以认领的受限任务类别。 */
    public enum Kind {
        /** 调用模型并恢复受控工具回合。 */
        AGENT_TURN,
        /** 用户从文字卡片直接调用模型并生成一个新的文字版本。 */
        TEXT_GENERATION,
        /** 生成并归档图片。 */
        IMAGE_GENERATION,
        /** 根据已选关键帧生成并归档视频。 */
        VIDEO_GENERATION,
        /** 根据已批准片段顺序导出无声视频。 */
        MEDIA_EXPORT,
        /** 对本地媒体执行受控归档。 */
        ASSET_INGEST
    }

    /** 数据库任务状态，区分本地执行与外部副作用的核对边界。 */
    public enum Status {
        /** 等待前置任务完成。 */
        PENDING,
        /** 满足依赖，等待 Worker 认领。 */
        READY,
        /** Worker 持有短期租约并执行本地阶段。 */
        RUNNING,
        /** 外部请求提交前检查点已持久化，结果可能需要核对。 */
        SUBMITTING,
        /** 外部请求 ID 已保存，等待查询原请求。 */
        WAITING_PROVIDER,
        /** 无法确认外部是否受理或完成，不能盲目重新提交。 */
        UNKNOWN,
        /** 因输入、配置或安全条件变化而暂停，等待人工处理。 */
        BLOCKED,
        /** 本地结果或外部结果已确认成功。 */
        SUCCEEDED,
        /** 已确认失败，而非单纯查询超时。 */
        FAILED,
        /** 已停止本系统后续编排；外部工作可能仍继续。 */
        CANCELED
    }
}

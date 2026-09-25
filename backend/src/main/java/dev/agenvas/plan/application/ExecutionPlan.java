package dev.agenvas.plan.application;

import dev.agenvas.task.domain.Task;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 不可变的执行计划提案正文；审批生命周期状态单独迁移。
 * @param id 计划 ID
 * @param projectId 归属项目 ID
 * @param runId 提出计划的 Agent Run
 * @param revision 同一 Run 与阶段内递增的计划修订号
 * @param stage 图片生成或视频生成阶段
 * @param status 当前审批生命周期状态
 * @param objective 用户可审阅的目标说明
 * @param plan 规范化后的计划 JSON
 * @param inputSnapshot 审批时核对内容选择所需的快照
 * @param inputSnapshotHash 固定输入快照的摘要
 * @param planHash 供用户确认精确计划的摘要
 * @param providerConfigVersion 计划固定的 Provider 配置版本
 * @param workflowVersion 计划固定的服务端工作流版本
 * @param estimate 不含伪造价格的任务数量和时长估算
 * @param createdAt 提案创建时刻
 * @param updatedAt 最近状态更新时间
 * @param steps 已排序且固定输入的 DAG 步骤
 */
public record ExecutionPlan(UUID id, UUID projectId, UUID runId, int revision,
        Stage stage, Status status, String objective, JsonNode plan,
        JsonNode inputSnapshot, String inputSnapshotHash, String planHash,
        int providerConfigVersion, String workflowVersion, JsonNode estimate,
        Instant createdAt, Instant updatedAt, List<Step> steps) {

    /** 首版分开审批图片关键帧生成与基于已选关键帧的视频生成。 */
    public enum Stage {
        /** 为镜头生成候选图片关键帧。 */
        IMAGE,
        /** 基于用户已选关键帧生成视频。 */
        VIDEO
    }

    /** 审批状态变化不会修改提案正文或其摘要。 */
    public enum Status {
        /** Waiting for the user to select a required source image before approval. */
        NEEDS_INPUT,
        /** 等待用户审阅并决定。 */
        PENDING,
        /** 用户已批准，媒体任务与审批凭据已创建。 */
        APPROVED,
        /** 用户拒绝计划，不创建计划中的媒体任务。 */
        REJECTED,
        /** 固定输入或 Provider 配置已变化，计划不再可审批。 */
        STALE
    }

    /** 执行图中的一个不可变节点及命名输出槽。
     * @param stepKey 在计划内唯一的步骤键
     * @param ordinal 依赖拓扑排序后的稳定顺序
     * @param kind 此步骤创建的任务类别
     * @param shotArtifactId 输入镜头产物
     * @param shotVersionId 固定的镜头内容版本
     * @param imageArtifactId 视频阶段使用的关键帧产物；图片阶段为空
     * @param imageVersionId 视频阶段固定的关键帧版本；图片阶段为空
     * @param outputSlotKey 本步骤产物写入的目标槽位
     * @param input 经校验且固定版本的任务输入
     * @param dependencyKeys 必须先完成的步骤键
     */
    public record Step(String stepKey, int ordinal, Task.Kind kind,
            UUID shotArtifactId, UUID shotVersionId,
            UUID imageArtifactId, UUID imageVersionId,
            String outputSlotKey, JsonNode input, List<String> dependencyKeys,
            MediaCapabilityBinding binding) {}
}

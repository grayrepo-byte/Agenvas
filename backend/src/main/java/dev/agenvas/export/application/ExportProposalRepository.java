package dev.agenvas.export.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 导出提案的项目范围持久化边界；审批状态以条件更新确保只决定一次。 */
public interface ExportProposalRepository {

    /** 插入已完成领域校验且正文不可变的待审提案。 */
    void create(ExportProposal proposal);

    /** 按提案所属项目读取记录，不返回其他项目的提案。 */
    Optional<ExportProposal> find(UUID projectId, UUID proposalId);

    /** 人工审批期间锁定目标提案，串行化并发决定。 */
    Optional<ExportProposal> findForUpdate(UUID projectId, UUID proposalId);

    /** 按时间倒序列出项目提案，包含已作出决定的记录。 */
    List<ExportProposal> list(UUID projectId);

    /** 全部审批校验及导出任务创建成功后，条件提交一次状态变化。 */
    boolean decide(UUID projectId, UUID proposalId, ExportProposal.Status target,
            UUID taskId, UUID userId, Instant now);
}

package dev.agenvas.export.infrastructure;

import static dev.agenvas.db.Tables.EXPORT_PROPOSAL;

import dev.agenvas.db.tables.records.ExportProposalRecord;
import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 导出提案仓储；待处理状态条件更新保证用户决定只提交一次。 */
@Repository
public class JooqExportProposalRepository implements ExportProposalRepository {

    /** 执行提案及审批决定的 jOOQ 查询。 */
    private final DSLContext dsl;
    /** 将提案 JSON 正文和输入固定项还原为 JSON 树。 */
    private final ObjectMapper mapper;

    /** 注入 jOOQ 查询上下文和 JSON 映射器。 */
    public JooqExportProposalRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /** 保存提案正文、素材固定项和项目版本摘要；审批字段初始为空。 */
    @Override
    public void create(ExportProposal proposal) {
        int changed = dsl.insertInto(EXPORT_PROPOSAL)
                .set(EXPORT_PROPOSAL.ID, proposal.id())
                .set(EXPORT_PROPOSAL.PROJECT_ID, proposal.projectId())
                .set(EXPORT_PROPOSAL.RUN_ID, proposal.runId())
                .set(EXPORT_PROPOSAL.STATUS, proposal.status().name())
                .set(EXPORT_PROPOSAL.INPUT_JSON, JSONB.valueOf(proposal.input().toString()))
                .set(EXPORT_PROPOSAL.INPUT_PINS_JSON, JSONB.valueOf(proposal.inputPins().toString()))
                .set(EXPORT_PROPOSAL.PROPOSAL_HASH, proposal.proposalHash())
                .set(EXPORT_PROPOSAL.PROJECT_VERSION, proposal.projectVersion())
                .set(EXPORT_PROPOSAL.APPROVED_TASK_ID, (UUID) null)
                .set(EXPORT_PROPOSAL.DECIDED_BY_USER_ID, (UUID) null)
                .set(EXPORT_PROPOSAL.CREATED_AT, utc(proposal.createdAt()))
                .set(EXPORT_PROPOSAL.DECIDED_AT, (OffsetDateTime) null)
                .execute();
        if (changed != 1) throw new IllegalStateException("Export proposal insert failed");
    }

    /** 按项目边界读取提案，不获取行锁。 */
    @Override
    public Optional<ExportProposal> find(UUID projectId, UUID proposalId) {
        return dsl.selectFrom(EXPORT_PROPOSAL)
                .where(EXPORT_PROPOSAL.PROJECT_ID.eq(projectId))
                .and(EXPORT_PROPOSAL.ID.eq(proposalId))
                .fetchOptional(this::map);
    }

    /** 锁定提案行，串行执行同一提案的审批或拒绝决定。 */
    @Override
    public Optional<ExportProposal> findForUpdate(UUID projectId, UUID proposalId) {
        return dsl.selectFrom(EXPORT_PROPOSAL)
                .where(EXPORT_PROPOSAL.PROJECT_ID.eq(projectId))
                .and(EXPORT_PROPOSAL.ID.eq(proposalId))
                .forUpdate()
                .fetchOptional(this::map);
    }

    /** 返回项目最近 100 条提案，按创建时间和 ID 稳定倒序排列。 */
    @Override
    public List<ExportProposal> list(UUID projectId) {
        return dsl.selectFrom(EXPORT_PROPOSAL)
                .where(EXPORT_PROPOSAL.PROJECT_ID.eq(projectId))
                .orderBy(EXPORT_PROPOSAL.CREATED_AT.desc(), EXPORT_PROPOSAL.ID.desc())
                .limit(100)
                .fetch(this::map);
    }

    /** 仅把 PENDING 提案变成决定状态；返回 false 表示已有并发决定。 */
    @Override
    public boolean decide(UUID projectId, UUID proposalId, ExportProposal.Status target,
            UUID taskId, UUID userId, Instant now) {
        return dsl.update(EXPORT_PROPOSAL)
                .set(EXPORT_PROPOSAL.STATUS, target.name())
                .set(EXPORT_PROPOSAL.APPROVED_TASK_ID, taskId)
                .set(EXPORT_PROPOSAL.DECIDED_BY_USER_ID, userId)
                .set(EXPORT_PROPOSAL.DECIDED_AT, utc(now))
                .where(EXPORT_PROPOSAL.PROJECT_ID.eq(projectId))
                .and(EXPORT_PROPOSAL.ID.eq(proposalId))
                .and(EXPORT_PROPOSAL.STATUS.eq(ExportProposal.Status.PENDING.name()))
                .execute() == 1;
    }

    /** 还原提案 JSON、审批身份及可空决定时间；各查询统一读取整行，避免遗漏审批凭据字段。 */
    private ExportProposal map(ExportProposalRecord row) {
        OffsetDateTime decided = row.getDecidedAt();
        return new ExportProposal(row.getId(),
                row.getProjectId(),
                row.getRunId(),
                ExportProposal.Status.valueOf(row.getStatus()),
                mapper.readTree(row.getInputJson().data()),
                mapper.readTree(row.getInputPinsJson().data()),
                row.getProposalHash(), row.getProjectVersion(),
                row.getApprovedTaskId(),
                row.getDecidedByUserId(),
                row.getCreatedAt().toInstant(),
                decided == null ? null : decided.toInstant());
    }

    /** 将绝对时间转换为 PostgreSQL 使用的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

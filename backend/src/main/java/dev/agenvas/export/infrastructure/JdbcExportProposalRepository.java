package dev.agenvas.export.infrastructure;

import dev.agenvas.export.application.ExportProposal;
import dev.agenvas.export.application.ExportProposalRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 导出提案仓储；待处理状态条件更新保证用户决定只提交一次。 */
@Repository
public class JdbcExportProposalRepository implements ExportProposalRepository {

    /** 单次读取映射提案所需的列，避免不同查询遗漏审批凭据字段。 */
    private static final String COLUMNS = """
            id, project_id, run_id, status, input_json, input_pins_json,
            proposal_hash, project_version, approved_task_id, decided_by_user_id,
            created_at, decided_at
            """;
    /** 执行提案及审批决定的参数化 SQL。 */
    private final JdbcClient jdbc;
    /** 将提案 JSON 正文和输入固定项还原为 JSON 树。 */
    private final ObjectMapper mapper;

    /** 注入 SQL 执行器和 JSON 映射器。 */
    public JdbcExportProposalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 保存提案正文、素材固定项和项目版本摘要；审批字段初始为空。 */
    @Override
    public void create(ExportProposal proposal) {
        int changed = jdbc.sql("""
                        insert into export_proposal (id, project_id, run_id, status,
                            input_json, input_pins_json, proposal_hash, project_version,
                            approved_task_id, decided_by_user_id, created_at, decided_at)
                        values (:id, :projectId, :runId, :status, cast(:input as jsonb),
                            cast(:pins as jsonb), :hash, :projectVersion, null, null,
                            :createdAt, null)
                        """)
                .param("id", proposal.id()).param("projectId", proposal.projectId())
                .param("runId", proposal.runId()).param("status", proposal.status().name())
                .param("input", proposal.input().toString())
                .param("pins", proposal.inputPins().toString())
                .param("hash", proposal.proposalHash())
                .param("projectVersion", proposal.projectVersion())
                .param("createdAt", utc(proposal.createdAt())).update();
        if (changed != 1) throw new IllegalStateException("Export proposal insert failed");
    }

    /** 按项目边界读取提案，不获取行锁。 */
    @Override
    public Optional<ExportProposal> find(UUID projectId, UUID proposalId) {
        return jdbc.sql("select " + COLUMNS + " from export_proposal "
                        + "where project_id = :projectId and id = :id")
                .param("projectId", projectId).param("id", proposalId)
                .query(this::map).optional();
    }

    /** 锁定提案行，串行执行同一提案的审批或拒绝决定。 */
    @Override
    public Optional<ExportProposal> findForUpdate(UUID projectId, UUID proposalId) {
        return jdbc.sql("select " + COLUMNS + " from export_proposal "
                        + "where project_id = :projectId and id = :id for update")
                .param("projectId", projectId).param("id", proposalId)
                .query(this::map).optional();
    }

    /** 返回项目最近 100 条提案，按创建时间和 ID 稳定倒序排列。 */
    @Override
    public List<ExportProposal> list(UUID projectId) {
        return jdbc.sql("select " + COLUMNS + " from export_proposal "
                        + "where project_id = :projectId order by created_at desc, id desc "
                        + "limit 100")
                .param("projectId", projectId).query(this::map).list();
    }

    /** 仅把 PENDING 提案变成决定状态；返回 false 表示已有并发决定。 */
    @Override
    public boolean decide(UUID projectId, UUID proposalId, ExportProposal.Status target,
            UUID taskId, UUID userId, Instant now) {
        return jdbc.sql("""
                        update export_proposal set status = :status,
                            approved_task_id = :taskId, decided_by_user_id = :userId,
                            decided_at = :decidedAt
                        where project_id = :projectId and id = :id and status = 'PENDING'
                        """)
                .param("status", target.name())
                .param("taskId", taskId, java.sql.Types.OTHER)
                .param("userId", userId).param("decidedAt", utc(now))
                .param("projectId", projectId).param("id", proposalId)
                .update() == 1;
    }

    /** 还原提案 JSON、审批身份及可空决定时间。 */
    private ExportProposal map(ResultSet row, int number) throws SQLException {
        OffsetDateTime decided = row.getObject("decided_at", OffsetDateTime.class);
        return new ExportProposal(row.getObject("id", UUID.class),
                row.getObject("project_id", UUID.class),
                row.getObject("run_id", UUID.class),
                ExportProposal.Status.valueOf(row.getString("status")),
                mapper.readTree(row.getString("input_json")),
                mapper.readTree(row.getString("input_pins_json")),
                row.getString("proposal_hash"), row.getLong("project_version"),
                row.getObject("approved_task_id", UUID.class),
                row.getObject("decided_by_user_id", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                decided == null ? null : decided.toInstant());
    }

    /** 将绝对时间转换为 PostgreSQL 使用的 UTC 偏移时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

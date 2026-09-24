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

/** PostgreSQL-backed proposal body and authenticated decision CAS. */
@Repository
public class JdbcExportProposalRepository implements ExportProposalRepository {

    private static final String COLUMNS = """
            id, project_id, run_id, status, input_json, input_pins_json,
            proposal_hash, project_version, approved_task_id, decided_by_user_id,
            created_at, decided_at
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcExportProposalRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

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

    @Override
    public Optional<ExportProposal> find(UUID projectId, UUID proposalId) {
        return jdbc.sql("select " + COLUMNS + " from export_proposal "
                        + "where project_id = :projectId and id = :id")
                .param("projectId", projectId).param("id", proposalId)
                .query(this::map).optional();
    }

    @Override
    public Optional<ExportProposal> findForUpdate(UUID projectId, UUID proposalId) {
        return jdbc.sql("select " + COLUMNS + " from export_proposal "
                        + "where project_id = :projectId and id = :id for update")
                .param("projectId", projectId).param("id", proposalId)
                .query(this::map).optional();
    }

    @Override
    public List<ExportProposal> list(UUID projectId) {
        return jdbc.sql("select " + COLUMNS + " from export_proposal "
                        + "where project_id = :projectId order by created_at desc, id desc "
                        + "limit 100")
                .param("projectId", projectId).query(this::map).list();
    }

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

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

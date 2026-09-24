package dev.agenvas.usage.infrastructure;

import dev.agenvas.usage.application.UsageRepository;
import dev.agenvas.usage.domain.UsageEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL append-only ledger with a unique operation key. */
@Repository
public class JdbcUsageRepository implements UsageRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcUsageRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean insertOnce(UsageEntry entry) {
        return jdbc.sql("""
                        insert into usage_ledger (id, project_id, run_id, task_id,
                            operation_key, entry_type, quantity_json, estimated_cost,
                            actual_cost, currency, cost_status, cost_source,
                            provider_config_version, workflow_version, model_id, created_at)
                        values (:id, :projectId, :runId, :taskId, :operationKey,
                            :entryType, cast(:quantity as jsonb), :estimatedCost,
                            :actualCost, :currency, :costStatus, :costSource,
                            :configVersion, :workflowVersion, :modelId, :createdAt)
                        on conflict (operation_key) do nothing
                        """)
                .param("id", entry.id()).param("projectId", entry.projectId())
                .param("runId", entry.runId(), java.sql.Types.OTHER)
                .param("taskId", entry.taskId(), java.sql.Types.OTHER)
                .param("operationKey", entry.operationKey())
                .param("entryType", entry.entryType().name())
                .param("quantity", entry.quantity().toString())
                .param("estimatedCost", entry.estimatedCost(), java.sql.Types.NUMERIC)
                .param("actualCost", entry.actualCost(), java.sql.Types.NUMERIC)
                .param("currency", entry.currency(), java.sql.Types.CHAR)
                .param("costStatus", entry.costStatus().name())
                .param("costSource", entry.costSource())
                .param("configVersion", entry.providerConfigVersion(), java.sql.Types.INTEGER)
                .param("workflowVersion", entry.workflowVersion(), java.sql.Types.VARCHAR)
                .param("modelId", entry.modelId(), java.sql.Types.VARCHAR)
                .param("createdAt", OffsetDateTime.ofInstant(entry.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    @Override
    public Optional<UsageEntry> findByOperationKey(String operationKey) {
        return jdbc.sql("""
                        select * from usage_ledger where operation_key = :operationKey
                        """)
                .param("operationKey", operationKey).query(this::map).optional();
    }

    @Override
    public List<UsageEntry> listProject(UUID projectId) {
        return jdbc.sql("""
                        select * from usage_ledger where project_id = :projectId
                        order by created_at, id
                        """)
                .param("projectId", projectId).query(this::map).list();
    }

    private UsageEntry map(ResultSet rs, int row) throws SQLException {
        return new UsageEntry(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getString("operation_key"),
                UsageEntry.EntryType.valueOf(rs.getString("entry_type")),
                mapper.readTree(rs.getString("quantity_json")),
                rs.getBigDecimal("estimated_cost"), rs.getBigDecimal("actual_cost"),
                rs.getString("currency"),
                UsageEntry.CostStatus.valueOf(rs.getString("cost_status")),
                rs.getString("cost_source"),
                rs.getObject("provider_config_version", Integer.class),
                rs.getString("workflow_version"), rs.getString("model_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}

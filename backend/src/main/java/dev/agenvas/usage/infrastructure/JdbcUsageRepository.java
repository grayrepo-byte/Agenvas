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

/** PostgreSQL 只追加用量账本；唯一 operationKey 防止任务或模型回合重复计量。 */
@Repository
public class JdbcUsageRepository implements UsageRepository {

    /** 执行账本插入、幂等查询和项目历史读取 SQL。 */
    private final JdbcClient jdbc;
    /** 将数量 JSONB 还原为账本领域值。 */
    private final ObjectMapper mapper;

    /** 注入用量账本 SQL 执行器与 JSON 映射器。 */
    public JdbcUsageRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 以唯一操作键插入账目；相同操作再次写入时返回 false，不覆盖原记录。 */
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

    /** 按全局唯一 operationKey 查找既有账目，供服务层核对幂等重放载荷。 */
    @Override
    public Optional<UsageEntry> findByOperationKey(String operationKey) {
        return jdbc.sql("""
                        select * from usage_ledger where operation_key = :operationKey
                        """)
                .param("operationKey", operationKey).query(this::map).optional();
    }

    /** 按项目和时间顺序读取账目，访问权限由应用服务预先校验。 */
    @Override
    public List<UsageEntry> listProject(UUID projectId) {
        return jdbc.sql("""
                        select * from usage_ledger where project_id = :projectId
                        order by created_at, id
                        """)
                .param("projectId", projectId).query(this::map).list();
    }

    /** 将数据库行中的可空价格、版本来源及数量 JSON 还原为账目实体。 */
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

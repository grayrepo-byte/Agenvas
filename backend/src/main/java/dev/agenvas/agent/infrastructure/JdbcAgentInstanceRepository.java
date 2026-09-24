package dev.agenvas.agent.infrastructure;

import dev.agenvas.agent.application.AgentInstanceRepository;
import dev.agenvas.agent.domain.AgentInstance;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL AgentInstance 仓储；每次查询显式传入所有者与项目，不在单例中保存请求身份。 */
@Repository
public class JdbcAgentInstanceRepository implements AgentInstanceRepository {

    /** 还原 Agent 配置主行；绑定关系由 withBindings 按需单独查询。 */
    private static final RowMapper<AgentInstance> INSTANCE_MAPPER = (resultSet, rowNumber) ->
            new AgentInstance(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("project_id", UUID.class),
                    resultSet.getString("profile_key"),
                    resultSet.getInt("profile_version"),
                    resultSet.getString("name"),
                    resultSet.getString("instruction"),
                    resultSet.getObject("output_group_id", UUID.class),
                    resultSet.getLong("version"),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    List.of());

    /** 执行 Agent 配置和绑定关系的参数化 SQL。 */
    private final JdbcClient jdbcClient;

    /** 注入 Agent 仓储使用的 JDBC 客户端。 */
    public JdbcAgentInstanceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 插入 Agent 配置行；绑定关系由应用服务在同一事务内另行替换。 */
    @Override
    public void create(AgentInstance instance) {
        jdbcClient.sql("""
                        insert into agent_instance (
                            id, project_id, profile_key, profile_version, name, instruction,
                            output_group_id, version, created_at, updated_at
                        ) values (
                            :id, :projectId, :profileKey, :profileVersion, :name, :instruction,
                            :outputGroupId, :version, :createdAt, :updatedAt
                        )
                        """)
                .param("id", instance.id())
                .param("projectId", instance.projectId())
                .param("profileKey", instance.profileKey())
                .param("profileVersion", instance.profileVersion())
                .param("name", instance.name())
                .param("instruction", instance.instruction())
                .param("outputGroupId", instance.outputGroupId())
                .param("version", instance.version())
                .param("createdAt", utc(instance.createdAt()))
                .param("updatedAt", utc(instance.updatedAt()))
                .update();
    }

    /** 按创建顺序列出项目 Agent，并加载各自的输入绑定。 */
    @Override
    public List<AgentInstance> list(UUID ownerId, UUID projectId) {
        return jdbcClient.sql("""
                        select ai.id, ai.project_id, ai.profile_key, ai.profile_version,
                               ai.name, ai.instruction, ai.output_group_id, ai.version,
                               ai.created_at, ai.updated_at
                        from agent_instance ai
                        join project p on p.id = ai.project_id
                        where ai.project_id = :projectId and p.owner_id = :ownerId
                        order by ai.created_at, ai.id
                        """)
                .param("ownerId", ownerId)
                .param("projectId", projectId)
                .query(INSTANCE_MAPPER)
                .list()
                .stream()
                .map(this::withBindings)
                .toList();
    }

    /** 按项目和所有者读取 Agent 及绑定，不对配置行加锁。 */
    @Override
    public Optional<AgentInstance> find(UUID ownerId, UUID projectId, UUID agentId) {
        return find(ownerId, projectId, agentId, false);
    }

    /** 锁定 Agent 配置行，供配置版本检查与绑定替换事务使用。 */
    @Override
    public Optional<AgentInstance> findForUpdate(
            UUID ownerId, UUID projectId, UUID agentId) {
        return find(ownerId, projectId, agentId, true);
    }

    /** 普通读取与加锁读取共用项目所有者约束和绑定加载逻辑。 */
    private Optional<AgentInstance> find(
            UUID ownerId, UUID projectId, UUID agentId, boolean forUpdate) {
        String lockClause = forUpdate ? " for update of ai" : "";
        return jdbcClient.sql("""
                        select ai.id, ai.project_id, ai.profile_key, ai.profile_version,
                               ai.name, ai.instruction, ai.output_group_id, ai.version,
                               ai.created_at, ai.updated_at
                        from agent_instance ai
                        join project p on p.id = ai.project_id
                        where ai.id = :agentId and ai.project_id = :projectId
                          and p.owner_id = :ownerId
                        """ + lockClause)
                .param("agentId", agentId)
                .param("projectId", projectId)
                .param("ownerId", ownerId)
                .query(INSTANCE_MAPPER)
                .optional()
                .map(this::withBindings);
    }

    /** 以预期配置版本更新名称和指令；过期编辑返回 false。 */
    @Override
    public boolean update(
            UUID ownerId,
            AgentInstance instance,
            long expectedVersion,
            Instant updatedAt) {
        return jdbcClient.sql("""
                        update agent_instance ai
                        set name = :name,
                            instruction = :instruction,
                            version = ai.version + 1,
                            updated_at = :updatedAt
                        from project p
                        where ai.id = :agentId and ai.project_id = :projectId
                          and p.id = ai.project_id and p.owner_id = :ownerId
                          and ai.version = :expectedVersion
                        """)
                .param("name", instance.name())
                .param("instruction", instance.instruction())
                .param("updatedAt", utc(updatedAt))
                .param("agentId", instance.id())
                .param("projectId", instance.projectId())
                .param("ownerId", ownerId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** 在调用方事务内整组替换输入绑定，避免出现部分旧、部分新关系。 */
    @Override
    public void replaceBindings(
            UUID projectId, UUID agentId, List<AgentInstance.Binding> bindings) {
        jdbcClient.sql("""
                        delete from agent_binding
                        where project_id = :projectId and agent_instance_id = :agentId
                        """)
                .param("projectId", projectId)
                .param("agentId", agentId)
                .update();
        for (AgentInstance.Binding binding : bindings) {
            jdbcClient.sql("""
                            insert into agent_binding (
                                id, project_id, agent_instance_id, artifact_id,
                                selected_version_id, binding_type, created_at
                            ) values (
                                :id, :projectId, :agentId, :artifactId,
                                :selectedVersionId, :bindingType, :createdAt
                            )
                            """)
                    .param("id", binding.id())
                    .param("projectId", projectId)
                    .param("agentId", agentId)
                    .param("artifactId", binding.artifactId())
                    .param("selectedVersionId", binding.selectedVersionId())
                    .param("bindingType", binding.bindingType().name())
                    .param("createdAt", utc(binding.createdAt()))
                    .update();
        }
    }

    /** 按创建时间恢复绑定顺序，并返回包含完整绑定集合的 Agent 值对象。 */
    private AgentInstance withBindings(AgentInstance instance) {
        List<AgentInstance.Binding> bindings = jdbcClient.sql("""
                        select id, artifact_id, selected_version_id, binding_type, created_at
                        from agent_binding
                        where project_id = :projectId and agent_instance_id = :agentId
                        order by created_at, id
                        """)
                .param("projectId", instance.projectId())
                .param("agentId", instance.id())
                .query((resultSet, rowNumber) -> new AgentInstance.Binding(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("artifact_id", UUID.class),
                        resultSet.getObject("selected_version_id", UUID.class),
                        AgentInstance.BindingType.valueOf(resultSet.getString("binding_type")),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new AgentInstance(
                instance.id(),
                instance.projectId(),
                instance.profileKey(),
                instance.profileVersion(),
                instance.name(),
                instance.instruction(),
                instance.outputGroupId(),
                instance.version(),
                instance.createdAt(),
                instance.updatedAt(),
                bindings);
    }

    /** 将 Instant 转成 PostgreSQL JDBC 参数所需的 UTC 时间。 */
    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

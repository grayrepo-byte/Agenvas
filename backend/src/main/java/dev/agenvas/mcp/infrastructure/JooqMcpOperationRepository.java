package dev.agenvas.mcp.infrastructure;

import static dev.agenvas.db.Tables.MCP_OPERATION;
import static dev.agenvas.db.Tables.MCP_OPERATION_TASK;
import static dev.agenvas.db.Tables.MCP_OPERATION_VERSION;

import dev.agenvas.mcp.application.McpOperationRepository;
import dev.agenvas.mcp.domain.McpOperation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Uses unique user/command keys to serialize even concurrent commands across projects. */
@Repository
public class JooqMcpOperationRepository implements McpOperationRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public JooqMcpOperationRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }

    @Override public boolean reserve(McpOperation op) {
        return dsl.insertInto(MCP_OPERATION).set(MCP_OPERATION.ID, op.id())
                .set(MCP_OPERATION.USER_ID, op.userId()).set(MCP_OPERATION.TOKEN_ID, op.tokenId())
                .set(MCP_OPERATION.PROJECT_ID, op.projectId()).set(MCP_OPERATION.COMMAND_ID, op.commandId())
                .set(MCP_OPERATION.TOOL_NAME, op.toolName()).set(MCP_OPERATION.REQUEST_HASH, op.requestHash())
                .set(MCP_OPERATION.RESULT, JSONB.valueOf(op.result().toString()))
                .set(MCP_OPERATION.CREATED_AT, OffsetDateTime.ofInstant(op.createdAt(), ZoneOffset.UTC))
                .onConflict(MCP_OPERATION.USER_ID, MCP_OPERATION.COMMAND_ID).doNothing().execute() == 1;
    }
    @Override public Optional<McpOperation> find(UUID userId, String commandId) {
        return dsl.selectFrom(MCP_OPERATION).where(MCP_OPERATION.USER_ID.eq(userId))
                .and(MCP_OPERATION.COMMAND_ID.eq(commandId)).fetchOptional().map(r -> new McpOperation(
                        r.getId(), r.getUserId(), r.getTokenId(), r.getProjectId(), r.getCommandId(),
                        r.getToolName(), r.getRequestHash(), mapper.readTree(r.getResult().data()), r.getCreatedAt().toInstant()));
    }
    @Override public void complete(UUID id, JsonNode result) {
        if (dsl.update(MCP_OPERATION).set(MCP_OPERATION.RESULT, JSONB.valueOf(result.toString()))
                .where(MCP_OPERATION.ID.eq(id)).execute() != 1) throw new IllegalStateException("MCP operation disappeared");
    }
    @Override public void linkTask(UUID op, UUID task, int index) {
        dsl.insertInto(MCP_OPERATION_TASK).set(MCP_OPERATION_TASK.OPERATION_ID, op)
                .set(MCP_OPERATION_TASK.TASK_ID, task).set(MCP_OPERATION_TASK.OUTPUT_INDEX, index).execute();
    }
    @Override public void linkVersion(UUID op, UUID version) {
        dsl.insertInto(MCP_OPERATION_VERSION).set(MCP_OPERATION_VERSION.OPERATION_ID, op)
                .set(MCP_OPERATION_VERSION.ARTIFACT_VERSION_ID, version)
                .onConflict(MCP_OPERATION_VERSION.OPERATION_ID, MCP_OPERATION_VERSION.ARTIFACT_VERSION_ID)
                .doNothing().execute();
    }
    @Override public Optional<UUID> operationForTask(UUID task) {
        return dsl.select(MCP_OPERATION_TASK.OPERATION_ID).from(MCP_OPERATION_TASK)
                .where(MCP_OPERATION_TASK.TASK_ID.eq(task)).fetchOptional(MCP_OPERATION_TASK.OPERATION_ID);
    }
}

package dev.agenvas.llm.infrastructure;

import static dev.agenvas.db.Tables.AGENT_MEDIA_APPROVAL;

import dev.agenvas.db.tables.records.AgentMediaApprovalRecord;
import dev.agenvas.llm.application.AgentMediaApprovalRepository;
import dev.agenvas.llm.domain.AgentMediaApproval;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL owns batch identity, decision CAS, and restart recovery for outstanding approvals. */
@Repository
public class JooqAgentMediaApprovalRepository implements AgentMediaApprovalRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public JooqAgentMediaApprovalRepository(DSLContext dsl, ObjectMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentMediaApproval> find(UUID projectId, UUID runId, UUID approvalId) {
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(AGENT_MEDIA_APPROVAL.RUN_ID.eq(runId))
                .and(AGENT_MEDIA_APPROVAL.ID.eq(approvalId)).fetchOptional(this::map);
    }

    @Override
    public Optional<AgentMediaApproval> findForUpdate(UUID projectId, UUID runId,
            UUID approvalId) {
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(AGENT_MEDIA_APPROVAL.RUN_ID.eq(runId))
                .and(AGENT_MEDIA_APPROVAL.ID.eq(approvalId)).forUpdate().fetchOptional(this::map);
    }

    @Override
    public Optional<AgentMediaApproval> findByToolCall(UUID projectId, UUID runId,
            int stepIndex, String toolCallId) {
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(AGENT_MEDIA_APPROVAL.RUN_ID.eq(runId))
                .and(AGENT_MEDIA_APPROVAL.STEP_INDEX.eq(stepIndex))
                .and(AGENT_MEDIA_APPROVAL.TOOL_CALL_ID.eq(toolCallId)).fetchOptional(this::map);
    }

    @Override
    public List<AgentMediaApproval> listByStoryboardTargets(UUID ownerId, UUID projectId,
            java.util.Set<UUID> itemIds, java.util.Set<UUID> shotIds, int limit) {
        if (itemIds.isEmpty() && shotIds.isEmpty()) return List.of();
        List<org.jooq.Condition> matches = new ArrayList<>(itemIds.stream().map(id -> {
            var target = mapper.createObjectNode();
            target.putArray("outputs").addObject().put("canvasItemId", id.toString());
            return DSL.condition("{0} @> {1}", AGENT_MEDIA_APPROVAL.TARGET_JSON, JSONB.valueOf(target.toString()));
        }).toList());
        for (UUID id : shotIds) {
            var target = mapper.createObjectNode();
            target.putArray("outputs").addObject().putObject("storyboard").put("shotId", id.toString());
            matches.add(DSL.condition("{0} @> {1}", AGENT_MEDIA_APPROVAL.TARGET_JSON, JSONB.valueOf(target.toString())));
        }
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(AGENT_MEDIA_APPROVAL.OWNER_ID.eq(ownerId))
                .and(DSL.or(matches))
                .orderBy(DSL.when(AGENT_MEDIA_APPROVAL.STATUS.eq(AgentMediaApproval.Status.PENDING.name()), 0)
                                .when(AGENT_MEDIA_APPROVAL.STATUS.eq(AgentMediaApproval.Status.APPROVED.name()), 1)
                                .otherwise(2),
                        AGENT_MEDIA_APPROVAL.CREATED_AT.desc(), AGENT_MEDIA_APPROVAL.ID.desc())
                .limit(limit).fetch(this::map);
    }

    @Override
    public Optional<Integer> findStoryboardResultRevision(UUID ownerId, UUID projectId,
            UUID shotId, UUID canvasItemId, UUID versionId) {
        // Query exact result provenance, including heterogeneous additional output cards.
        var primary = mapper.createObjectNode();
        var task = primary.putArray("tasks").addObject();
        task.putObject("storyboard").put("shotId", shotId.toString());
        task.put("canvasItemId", canvasItemId.toString()).put("artifactVersionId", versionId.toString());
        var additional = mapper.createObjectNode();
        var extraTask = additional.putArray("tasks").addObject();
        extraTask.putObject("storyboard").put("shotId", shotId.toString());
        extraTask.putArray("additionalResults").addObject().put("canvasItemId", canvasItemId.toString())
                .put("artifactVersionId", versionId.toString());
        return dsl.select(AGENT_MEDIA_APPROVAL.RESULT_JSON).from(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.OWNER_ID.eq(ownerId))
                .and(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(DSL.condition("({0} @> {1} or {0} @> {2})", AGENT_MEDIA_APPROVAL.RESULT_JSON,
                        JSONB.valueOf(primary.toString()), JSONB.valueOf(additional.toString())))
                .fetch(AGENT_MEDIA_APPROVAL.RESULT_JSON).stream()
                .map(json -> mapper.readTree(json.data()))
                .flatMap(result -> java.util.stream.StreamSupport.stream(result.path("tasks").spliterator(), false))
                .filter(item -> shotId.toString().equals(item.at("/storyboard/shotId").asText()))
                .filter(item -> canvasItemId.toString().equals(item.path("canvasItemId").asText())
                        && versionId.toString().equals(item.path("artifactVersionId").asText())
                        || java.util.stream.StreamSupport.stream(item.path("additionalResults").spliterator(), false)
                                .anyMatch(extra -> canvasItemId.toString().equals(extra.path("canvasItemId").asText())
                                        && versionId.toString().equals(extra.path("artifactVersionId").asText())))
                .map(item -> item.at("/storyboard/shotRevision").asInt()).filter(revision -> revision > 0).min(Integer::compare);
    }

    @Override
    public List<AgentMediaApproval> listByRun(UUID projectId, UUID runId) {
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(AGENT_MEDIA_APPROVAL.RUN_ID.eq(runId))
                .orderBy(AGENT_MEDIA_APPROVAL.CREATED_AT, AGENT_MEDIA_APPROVAL.ID).fetch(this::map);
    }

    @Override
    public boolean hasCanvasOwnership(UUID ownerId, UUID projectId, UUID canvasItemId) {
        var path = "$.**.canvasItemId ? (@ == $item)";
        return dsl.fetchExists(dsl.selectOne().from(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.OWNER_ID.eq(ownerId))
                .and(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(DSL.condition("jsonb_path_exists({0}, {3}::jsonpath, jsonb_build_object('item', {4}))"
                                + " or jsonb_path_exists({1}, {3}::jsonpath, jsonb_build_object('item', {4}))"
                                + " or jsonb_path_exists({2}, {3}::jsonpath, jsonb_build_object('item', {4}))",
                        AGENT_MEDIA_APPROVAL.REQUEST_JSON, AGENT_MEDIA_APPROVAL.TARGET_JSON,
                        AGENT_MEDIA_APPROVAL.RESULT_JSON, DSL.val(path), DSL.val(canvasItemId.toString()))));
    }

    @Override
    public Optional<AgentMediaApproval> findByTaskId(UUID projectId, UUID taskId) {
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(DSL.condition("{0} @> {1}", AGENT_MEDIA_APPROVAL.TASK_IDS_JSON,
                        JSONB.valueOf(mapper.createArrayNode().add(taskId.toString()).toString())))
                .fetchOptional(this::map);
    }

    @Override
    public List<AgentMediaApproval> listOutstanding(int limit) {
        return listOutstandingAfter(null, limit);
    }

    @Override
    public List<AgentMediaApproval> listOutstandingAfter(UUID afterId, int limit) {
        return dsl.selectFrom(AGENT_MEDIA_APPROVAL)
                .where(AGENT_MEDIA_APPROVAL.STATUS.in(
                        AgentMediaApproval.Status.PENDING.name(),
                        AgentMediaApproval.Status.APPROVED.name())
                        .or(AGENT_MEDIA_APPROVAL.NOTIFICATION_PENDING.isTrue()))
                .and(afterId == null ? DSL.noCondition() : AGENT_MEDIA_APPROVAL.ID.gt(afterId))
                .orderBy(AGENT_MEDIA_APPROVAL.ID)
                .limit(limit).fetch(this::map);
    }

    @Override
    public void markNotified(UUID projectId, UUID runId, int stepIndex) {
        dsl.update(AGENT_MEDIA_APPROVAL)
                .set(AGENT_MEDIA_APPROVAL.NOTIFICATION_PENDING, false)
                .where(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(projectId))
                .and(AGENT_MEDIA_APPROVAL.RUN_ID.eq(runId))
                .and(AGENT_MEDIA_APPROVAL.STEP_INDEX.eq(stepIndex))
                .and(AGENT_MEDIA_APPROVAL.STATUS.notIn(
                        AgentMediaApproval.Status.PENDING.name(),
                        AgentMediaApproval.Status.APPROVED.name())).execute();
    }

    @Override
    public boolean insert(AgentMediaApproval approval) {
        return dsl.insertInto(AGENT_MEDIA_APPROVAL)
                .set(AGENT_MEDIA_APPROVAL.ID, approval.id())
                .set(AGENT_MEDIA_APPROVAL.OWNER_ID, approval.ownerId())
                .set(AGENT_MEDIA_APPROVAL.PROJECT_ID, approval.projectId())
                .set(AGENT_MEDIA_APPROVAL.RUN_ID, approval.runId())
                .set(AGENT_MEDIA_APPROVAL.STEP_INDEX, approval.stepIndex())
                .set(AGENT_MEDIA_APPROVAL.TOOL_CALL_ID, approval.toolCallId())
                .set(AGENT_MEDIA_APPROVAL.OPERATION_ID, approval.operationId())
                .set(AGENT_MEDIA_APPROVAL.REQUEST_JSON, JSONB.valueOf(approval.request().toString()))
                .set(AGENT_MEDIA_APPROVAL.TARGET_JSON, JSONB.valueOf(approval.targets().toString()))
                .set(AGENT_MEDIA_APPROVAL.TASK_IDS_JSON, jsonTasks(approval.taskIds()))
                .set(AGENT_MEDIA_APPROVAL.RESULT_JSON, json(approval.result()))
                .set(AGENT_MEDIA_APPROVAL.STATUS, approval.status().name())
                .set(AGENT_MEDIA_APPROVAL.VERSION, approval.version())
                .set(AGENT_MEDIA_APPROVAL.CREATED_AT, utc(approval.createdAt()))
                .set(AGENT_MEDIA_APPROVAL.EXPIRES_AT, utc(approval.expiresAt()))
                .set(AGENT_MEDIA_APPROVAL.EXECUTION_DEADLINE, utc(approval.executionDeadline()))
                .set(AGENT_MEDIA_APPROVAL.DECISION_KEY, approval.decisionKey())
                .set(AGENT_MEDIA_APPROVAL.DECISION_HASH, approval.decisionHash())
                .onConflict(AGENT_MEDIA_APPROVAL.RUN_ID, AGENT_MEDIA_APPROVAL.STEP_INDEX,
                        AGENT_MEDIA_APPROVAL.TOOL_CALL_ID).doNothing().execute() == 1;
    }

    @Override
    public boolean update(AgentMediaApproval approval, long expectedVersion) {
        return dsl.update(AGENT_MEDIA_APPROVAL)
                .set(AGENT_MEDIA_APPROVAL.STATUS, approval.status().name())
                .set(AGENT_MEDIA_APPROVAL.VERSION, approval.version())
                .set(AGENT_MEDIA_APPROVAL.TASK_IDS_JSON, jsonTasks(approval.taskIds()))
                .set(AGENT_MEDIA_APPROVAL.RESULT_JSON, json(approval.result()))
                .set(AGENT_MEDIA_APPROVAL.EXECUTION_DEADLINE, utc(approval.executionDeadline()))
                .set(AGENT_MEDIA_APPROVAL.DECISION_KEY, approval.decisionKey())
                .set(AGENT_MEDIA_APPROVAL.DECISION_HASH, approval.decisionHash())
                .where(AGENT_MEDIA_APPROVAL.ID.eq(approval.id()))
                .and(AGENT_MEDIA_APPROVAL.PROJECT_ID.eq(approval.projectId()))
                .and(AGENT_MEDIA_APPROVAL.RUN_ID.eq(approval.runId()))
                .and(AGENT_MEDIA_APPROVAL.VERSION.eq(expectedVersion)).execute() == 1;
    }

    private AgentMediaApproval map(AgentMediaApprovalRecord row) {
        List<UUID> taskIds = new ArrayList<>();
        mapper.readTree(row.getTaskIdsJson().data())
                .forEach(value -> taskIds.add(UUID.fromString(value.asText())));
        return new AgentMediaApproval(row.getId(), row.getOwnerId(), row.getProjectId(),
                row.getRunId(), row.getStepIndex(), row.getToolCallId(), row.getOperationId(),
                mapper.readTree(row.getRequestJson().data()),
                mapper.readTree(row.getTargetJson().data()), taskIds,
                row.getResultJson() == null ? null : mapper.readTree(row.getResultJson().data()),
                AgentMediaApproval.Status.valueOf(row.getStatus()), row.getVersion(),
                row.getCreatedAt().toInstant(), row.getExpiresAt().toInstant(),
                row.getExecutionDeadline() == null ? null : row.getExecutionDeadline().toInstant(),
                row.getDecisionKey(), row.getDecisionHash());
    }

    private JSONB jsonTasks(List<UUID> ids) {
        var json = mapper.createArrayNode();
        ids.forEach(id -> json.add(id.toString()));
        return JSONB.valueOf(json.toString());
    }

    private JSONB json(tools.jackson.databind.JsonNode value) {
        return value == null ? null : JSONB.valueOf(value.toString());
    }

    private OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}

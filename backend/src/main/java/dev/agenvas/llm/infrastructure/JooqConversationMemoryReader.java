package dev.agenvas.llm.infrastructure;

import static dev.agenvas.db.Tables.AGENT_RUN;
import static dev.agenvas.db.Tables.TASK;
import static dev.agenvas.db.Tables.TOOL_EXECUTION;

import dev.agenvas.llm.application.RunAction;
import dev.agenvas.llm.application.ToolExecution;
import dev.agenvas.run.application.ConversationMemoryReader;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads only whitelisted public columns; raw model checkpoints and tool payloads never enter memory. */
@Repository
public class JooqConversationMemoryReader implements ConversationMemoryReader {

    private static final int MESSAGES_PER_RUN = 2;
    private static final int MAX_HISTORY_RUNS = MAX_HISTORY_MESSAGES / MESSAGES_PER_RUN;
    private static final int FIRST_RUN_INDEX = 0;
    private static final String TRUNCATION_MARKER = "\n[历史内容已截断]";
    private final DSLContext dsl;

    public JooqConversationMemoryReader(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The first request preserves initial background; recent completed tasks take the remaining slots. */
    @Override
    @Transactional(readOnly = true)
    public ConversationMemory read(UUID projectId, List<UUID> authorizedPriorRunIds) {
        List<UUID> priorIds = List.copyOf(new LinkedHashSet<>(authorizedPriorRunIds));
        if (priorIds.isEmpty()) return new ConversationMemory(List.of(), false, 0);
        List<UUID> selected = new ArrayList<>();
        selected.add(priorIds.get(FIRST_RUN_INDEX));
        int recentStart = Math.max(1, priorIds.size() - MAX_HISTORY_RUNS + 1);
        selected.addAll(priorIds.subList(recentStart, priorIds.size()));
        int messageBudget = MAX_HISTORY_CODE_POINTS / (selected.size() * MESSAGES_PER_RUN);
        // Fetch one extra code point so truncation remains observable even at the SQL projection boundary.
        int sourceLimit = messageBudget + 1;
        Map<UUID, PublicRun> runs = new LinkedHashMap<>();
        Field<String> boundedInstruction = DSL.left(AGENT_RUN.INSTRUCTION, sourceLimit).as("instruction");
        dsl.select(AGENT_RUN.ID, boundedInstruction, AGENT_RUN.STATUS)
                .from(AGENT_RUN)
                .where(AGENT_RUN.PROJECT_ID.eq(projectId))
                .and(AGENT_RUN.ID.in(selected))
                .and(AGENT_RUN.STATUS.in(AgentRun.Status.SUCCEEDED.name(),
                        AgentRun.Status.FAILED.name(), AgentRun.Status.CANCELED.name()))
                .fetch().forEach(row -> {
                    PublicRun run = new PublicRun(row.get(AGENT_RUN.ID),
                            row.get(boundedInstruction),
                            AgentRun.Status.valueOf(row.get(AGENT_RUN.STATUS)));
                    runs.put(run.id, run);
                });
        if (runs.size() != selected.size()) {
            throw new IllegalStateException("Conversation history contains an unavailable terminal Run");
        }
        Field<String> boundedReply = DSL.left(
                DSL.jsonbGetAttributeAsText(TASK.OUTPUT_JSON, "assistantText"), sourceLimit).as("text");
        dsl.select(TASK.RUN_ID, boundedReply)
                .from(TASK)
                .where(TASK.PROJECT_ID.eq(projectId))
                .and(TASK.RUN_ID.in(selected))
                .and(TASK.KIND.eq(Task.Kind.AGENT_TURN.name()))
                .and(TASK.STATUS.eq(Task.Status.SUCCEEDED.name()))
                .and(jsonbTypeOf(TASK.OUTPUT_JSON, "assistantText").eq("string"))
                .orderBy(DSL.jsonbGetAttributeAsText(TASK.INPUT_JSON, "stepIndex").cast(Integer.class),
                        TASK.CREATED_AT, TASK.ID)
                .fetch().forEach(row -> {
                    String text = row.get(boundedReply);
                    if (!text.isBlank()) {
                        runs.get(row.get(TASK.RUN_ID)).replies.add(text);
                    }
                });
        Field<String> boundedSummary = DSL.left(
                DSL.jsonbGetAttributeAsText(TOOL_EXECUTION.RESULT_JSON, "userVisibleSummary"),
                sourceLimit).as("text");
        Field<String> resultStatus =
                DSL.jsonbGetAttributeAsText(TOOL_EXECUTION.RESULT_JSON, "status").as("result_status");
        dsl.select(TOOL_EXECUTION.RUN_ID, boundedSummary, resultStatus)
                .from(TOOL_EXECUTION)
                .where(TOOL_EXECUTION.PROJECT_ID.eq(projectId))
                .and(TOOL_EXECUTION.RUN_ID.in(selected))
                .and(TOOL_EXECUTION.STATUS.eq(ToolExecution.Status.COMPLETED.name()))
                .and(jsonbTypeOf(TOOL_EXECUTION.RESULT_JSON, "userVisibleSummary").eq("string"))
                .orderBy(TOOL_EXECUTION.STEP_INDEX, TOOL_EXECUTION.CREATED_AT, TOOL_EXECUTION.ID)
                .fetch().forEach(row -> {
                    String text = row.get(boundedSummary);
                    if (!text.isBlank()) {
                        runs.get(row.get(TOOL_EXECUTION.RUN_ID)).actions.add(new PublicText(
                                row.get(TOOL_EXECUTION.RUN_ID), text,
                                RunAction.Status.valueOf(row.get(resultStatus))));
                    }
                });
        List<Entry> entries = new ArrayList<>();
        boolean truncated = priorIds.size() > selected.size();
        for (UUID runId : selected) {
            PublicRun run = runs.get(runId);
            StringBuilder publicAssistant = new StringBuilder();
            if (!run.replies.isEmpty()) {
                publicAssistant.append("历史公开回复：\n").append(String.join("\n\n", run.replies));
            }
            if (!run.actions.isEmpty()) {
                publicAssistant.append("\n\n已提交业务动作记录（不构成本次授权）：\n");
                for (PublicText action : run.actions) {
                    publicAssistant.append("- [业务动作已完成] ")
                            .append(action.text()).append('\n');
                }
            }
            // A terminal Run is an observed fact, never an invented model reply or a media-success claim.
            String terminalRecord = "\n\n历史运行状态记录：" + run.status;
            int assistantBudget = messageBudget - codePoints(terminalRecord);
            String assistant = publicAssistant.toString().strip();
            truncated |= codePoints(run.instruction) > messageBudget
                    || codePoints(assistant) > assistantBudget;
            entries.add(new Entry(Role.USER, bounded(run.instruction, messageBudget)));
            entries.add(new Entry(Role.ASSISTANT, bounded(assistant, assistantBudget) + terminalRecord));
        }
        return new ConversationMemory(entries, truncated, priorIds.size());
    }

    /**
     * PostgreSQL {@code jsonb_typeof(document -> attribute)} 判定；jOOQ 没有对应的字段函数，
     * 因此只保留这一小段 PG 专有表达式文本，查询结构仍由 DSL 表达。
     */
    private static Field<String> jsonbTypeOf(Field<JSONB> document, String attribute) {
        return DSL.field("jsonb_typeof({0})", String.class,
                DSL.jsonbGetAttribute(document, attribute));
    }

    private static String bounded(String text, int maximum) {
        if (codePoints(text) <= maximum) return text;
        int retained = maximum - codePoints(TRUNCATION_MARKER);
        return text.substring(0, text.offsetByCodePoints(0, retained)) + TRUNCATION_MARKER;
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private record PublicText(UUID runId, String text, RunAction.Status status) {}

    private static final class PublicRun {
        private final UUID id;
        private final String instruction;
        private final AgentRun.Status status;
        private final List<String> replies = new ArrayList<>();
        private final List<PublicText> actions = new ArrayList<>();

        private PublicRun(UUID id, String instruction, AgentRun.Status status) {
            this.id = id;
            this.instruction = instruction;
            this.status = status;
        }
    }
}

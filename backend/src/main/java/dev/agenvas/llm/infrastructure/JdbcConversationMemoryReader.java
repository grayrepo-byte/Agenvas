package dev.agenvas.llm.infrastructure;

import dev.agenvas.run.application.ConversationMemoryReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads only whitelisted public columns; raw model checkpoints and tool payloads never enter memory. */
@Repository
public class JdbcConversationMemoryReader implements ConversationMemoryReader {

    private static final int MESSAGES_PER_RUN = 2;
    private static final int MAX_HISTORY_RUNS = MAX_HISTORY_MESSAGES / MESSAGES_PER_RUN;
    private static final int FIRST_RUN_INDEX = 0;
    private static final String TRUNCATION_MARKER = "\n[历史内容已截断]";
    private final JdbcClient jdbc;

    public JdbcConversationMemoryReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
        jdbc.sql("""
                        select id, left(instruction, :sourceLimit) as instruction, status
                        from agent_run
                        where project_id = :projectId and id in (:runIds)
                          and status in ('SUCCEEDED', 'FAILED', 'CANCELED')
                        """)
                .param("projectId", projectId).param("runIds", selected)
                .param("sourceLimit", sourceLimit)
                .query((rs, row) -> new PublicRun(rs.getObject("id", UUID.class),
                        rs.getString("instruction"), rs.getString("status")))
                .list().forEach(run -> runs.put(run.id, run));
        if (runs.size() != selected.size()) {
            throw new IllegalStateException("Conversation history contains an unavailable terminal Run");
        }
        jdbc.sql("""
                        select run_id, left(output_json ->> 'assistantText', :sourceLimit) as text
                        from task
                        where project_id = :projectId and run_id in (:runIds)
                          and kind = 'AGENT_TURN' and status = 'SUCCEEDED'
                          and jsonb_typeof(output_json -> 'assistantText') = 'string'
                        order by (input_json ->> 'stepIndex')::integer, created_at, id
                        """)
                .param("projectId", projectId).param("runIds", selected)
                .param("sourceLimit", sourceLimit)
                .query((rs, row) -> new PublicText(rs.getObject("run_id", UUID.class),
                        rs.getString("text"), null))
                .list().forEach(reply -> {
                    if (!reply.text().isBlank()) runs.get(reply.runId()).replies.add(reply.text());
                });
        jdbc.sql("""
                        select run_id, left(result_json ->> 'userVisibleSummary', :sourceLimit) as text,
                               result_json ->> 'status' as result_status
                        from tool_execution
                        where project_id = :projectId and run_id in (:runIds) and status = 'COMPLETED'
                          and jsonb_typeof(result_json -> 'userVisibleSummary') = 'string'
                        order by step_index, created_at, id
                        """)
                .param("projectId", projectId).param("runIds", selected)
                .param("sourceLimit", sourceLimit)
                .query((rs, row) -> new PublicText(rs.getObject("run_id", UUID.class),
                        rs.getString("text"), rs.getString("result_status")))
                .list().forEach(action -> {
                    if (!action.text().isBlank()) runs.get(action.runId()).actions.add(action);
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
                    String status = "WAITING_APPROVAL".equals(action.status()) ? "待审批提案"
                            : "SUCCEEDED".equals(action.status()) ? "业务动作已完成" : "已提交";
                    publicAssistant.append("- [").append(status).append("] ")
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

    private static String bounded(String text, int maximum) {
        if (codePoints(text) <= maximum) return text;
        int retained = maximum - codePoints(TRUNCATION_MARKER);
        return text.substring(0, text.offsetByCodePoints(0, retained)) + TRUNCATION_MARKER;
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private record PublicText(UUID runId, String text, String status) {}

    private static final class PublicRun {
        private final UUID id;
        private final String instruction;
        private final String status;
        private final List<String> replies = new ArrayList<>();
        private final List<PublicText> actions = new ArrayList<>();

        private PublicRun(UUID id, String instruction, String status) {
            this.id = id;
            this.instruction = instruction;
            this.status = status;
        }
    }
}

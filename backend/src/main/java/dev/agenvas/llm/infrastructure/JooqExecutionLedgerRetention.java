package dev.agenvas.llm.infrastructure;

import static dev.agenvas.db.Tables.TOOL_EXECUTION;
import static dev.agenvas.db.Tables.LLM_TURN;

import dev.agenvas.llm.application.ExecutionLedgerRetention;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqExecutionLedgerRetention implements ExecutionLedgerRetention {
    private final DSLContext dsl;
    public JooqExecutionLedgerRetention(DSLContext dsl) { this.dsl = dsl; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void deleteFor(List<UUID> runIds) {
        dsl.deleteFrom(TOOL_EXECUTION).where(TOOL_EXECUTION.RUN_ID.in(runIds)).execute();
        dsl.deleteFrom(LLM_TURN).where(LLM_TURN.RUN_ID.in(runIds)).execute();
    }
}

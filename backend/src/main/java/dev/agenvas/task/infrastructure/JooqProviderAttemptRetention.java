package dev.agenvas.task.infrastructure;

import static dev.agenvas.db.Tables.PROVIDER_ATTEMPT;

import dev.agenvas.task.application.ProviderAttemptRetention;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JooqProviderAttemptRetention implements ProviderAttemptRetention {
    private final DSLContext dsl;
    public JooqProviderAttemptRetention(DSLContext dsl) { this.dsl = dsl; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void deleteFor(List<UUID> taskIds) {
        dsl.deleteFrom(PROVIDER_ATTEMPT).where(PROVIDER_ATTEMPT.TASK_ID.in(taskIds)).execute();
    }
}

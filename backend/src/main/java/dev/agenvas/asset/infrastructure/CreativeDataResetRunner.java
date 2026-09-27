package dev.agenvas.asset.infrastructure;

import static dev.agenvas.db.Tables.CREATIVE_DATA_RESET_MARKER;

import java.time.Clock;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Completes V52's one-time local-development reset in the private asset volume. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CreativeDataResetRunner implements ApplicationRunner {
    private static final short RESET_MARKER_ID = 1;

    private final DSLContext dsl;
    private final LocalAssetStorage storage;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean recoveryMode;

    public CreativeDataResetRunner(DSLContext dsl, LocalAssetStorage storage,
            TransactionTemplate transactions, Clock clock,
            @Value("${agenvas.recovery-mode:false}") boolean recoveryMode) {
        this.dsl = dsl;
        this.storage = storage;
        this.transactions = transactions;
        this.clock = clock;
        this.recoveryMode = recoveryMode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!recoveryMode) resetBeforeWorkers();
    }

    /** A row lock ensures only one application instance cleans a shared private volume. */
    void resetBeforeWorkers() {
        transactions.executeWithoutResult(ignored -> {
            boolean completed = dsl.select(CREATIVE_DATA_RESET_MARKER.COMPLETED_AT)
                    .from(CREATIVE_DATA_RESET_MARKER)
                    .where(CREATIVE_DATA_RESET_MARKER.ID.eq(RESET_MARKER_ID))
                    .forUpdate()
                    .fetchSingle(CREATIVE_DATA_RESET_MARKER.COMPLETED_AT) != null;
            if (completed) return;
            storage.deleteCreativeProjectDirectories();
            int changed = dsl.update(CREATIVE_DATA_RESET_MARKER)
                    .set(CREATIVE_DATA_RESET_MARKER.COMPLETED_AT,
                            clock.instant().atOffset(ZoneOffset.UTC))
                    .where(CREATIVE_DATA_RESET_MARKER.ID.eq(RESET_MARKER_ID))
                    .execute();
            if (changed != 1) {
                throw new IllegalStateException("Creative data reset marker is missing");
            }
        });
    }
}

package dev.agenvas.llm.application;

import static dev.agenvas.db.Tables.DIRECTOR_SETTINGS;

import dev.agenvas.db.tables.records.DirectorSettingsRecord;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Database is authoritative; each new plan reads one coherent snapshot, without a process cache. */
@Service
public class DirectorSettingsService {
    private static final int SINGLETON_ID = 1;
    private final DSLContext dsl;

    public DirectorSettingsService(DSLContext dsl) { this.dsl = dsl; }

    @Transactional(readOnly = true)
    public DirectorSettings get() {
        return from(dsl.selectFrom(DIRECTOR_SETTINGS).where(DIRECTOR_SETTINGS.ID.eq(SINGLETON_ID)).fetchSingle());
    }

    @Transactional
    public DirectorSettings update(DirectorSettings settings) {
        var row = dsl.update(DIRECTOR_SETTINGS)
                .set(DIRECTOR_SETTINGS.CONTEXT_WINDOW_TOKENS, settings.contextWindowTokens())
                .set(DIRECTOR_SETTINGS.COMPACTION_THRESHOLD_PERCENT, settings.compactionThresholdPercent())
                .set(DIRECTOR_SETTINGS.RESPONSE_RESERVE_TOKENS, settings.responseReserveTokens())
                .set(DIRECTOR_SETTINGS.SUMMARY_TOKENS, settings.summaryTokens())
                .set(DIRECTOR_SETTINGS.RECENT_TOOL_ROUNDS, settings.recentToolRounds())
                .set(DIRECTOR_SETTINGS.RECOVERY_WINDOW_MINUTES, settings.recoveryWindowMinutes())
                .set(DIRECTOR_SETTINGS.MAX_MODEL_RETRIES, settings.maxModelRetries())
                .set(DIRECTOR_SETTINGS.RETRY_MAX_DELAY_SECONDS, settings.retryMaxDelaySeconds())
                .set(DIRECTOR_SETTINGS.MAX_REPAIR_ATTEMPTS, settings.maxRepairAttempts())
                .set(DIRECTOR_SETTINGS.VERSION, DIRECTOR_SETTINGS.VERSION.plus(1))
                .where(DIRECTOR_SETTINGS.ID.eq(SINGLETON_ID)).and(DIRECTOR_SETTINGS.VERSION.eq(settings.version()))
                .returning().fetchOne();
        if (row == null) throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                ApiMessage.of("api.director-settings.title"), ApiMessage.of("api.director-settings.conflict"), false);
        return from(row);
    }

    private DirectorSettings from(DirectorSettingsRecord row) {
        return new DirectorSettings(row.getContextWindowTokens(), row.getCompactionThresholdPercent(),
                row.getResponseReserveTokens(), row.getSummaryTokens(), row.getRecentToolRounds(),
                row.getRecoveryWindowMinutes(), row.getMaxModelRetries(), row.getRetryMaxDelaySeconds(),
                row.getMaxRepairAttempts(), row.getVersion());
    }
}

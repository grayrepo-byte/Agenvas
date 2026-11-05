package dev.agenvas.mediatemplate.application;

import static dev.agenvas.db.Tables.THIRD_PARTY_PROMPT_SYNC_SETTINGS;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** No process cache: disabling consent is observed before the next scheduled source starts. */
@Service
public class ThirdPartyPromptSyncSettingsService {
    private static final short SINGLETON_ID = 1;
    private final DSLContext db;
    private final boolean deploymentEnabled;
    private final boolean recovery;

    public ThirdPartyPromptSyncSettingsService(DSLContext db,
            @Value("${agenvas.media-templates.sync-enabled:true}") boolean deploymentEnabled,
            @Value("${agenvas.recovery-mode:false}") boolean recovery) {
        this.db = db; this.deploymentEnabled = deploymentEnabled; this.recovery = recovery;
    }

    @Transactional(readOnly = true)
    public ThirdPartyPromptSyncSettings get() {
        return db.selectFrom(THIRD_PARTY_PROMPT_SYNC_SETTINGS)
                .where(THIRD_PARTY_PROMPT_SYNC_SETTINGS.ID.eq(SINGLETON_ID))
                .fetchSingle(row -> new ThirdPartyPromptSyncSettings(row.getEnabled(), deploymentEnabled, row.getVersion()));
    }

    /**
     * Hold a shared consent lock until the caller's source-claim transaction commits.
     * Saving disabled waits for already accepted claims, and later claims see false.
     * No network work may occur while this lock is held.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean allowScheduledClaim() {
        return db.selectFrom(THIRD_PARTY_PROMPT_SYNC_SETTINGS)
                .where(THIRD_PARTY_PROMPT_SYNC_SETTINGS.ID.eq(SINGLETON_ID)).forShare()
                .fetchSingle().getEnabled();
    }

    @Transactional
    public ThirdPartyPromptSyncSettings update(boolean enabled, long expectedVersion) {
        if (recovery) throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "RECOVERY_MODE_READ_ONLY",
                ApiMessage.of("api.third-party.readOnly"), ApiMessage.of("api.third-party.readOnly"), false);
        var row = db.update(THIRD_PARTY_PROMPT_SYNC_SETTINGS)
                .set(THIRD_PARTY_PROMPT_SYNC_SETTINGS.ENABLED, enabled)
                .set(THIRD_PARTY_PROMPT_SYNC_SETTINGS.VERSION, THIRD_PARTY_PROMPT_SYNC_SETTINGS.VERSION.plus(1))
                .where(THIRD_PARTY_PROMPT_SYNC_SETTINGS.ID.eq(SINGLETON_ID))
                .and(THIRD_PARTY_PROMPT_SYNC_SETTINGS.VERSION.eq(expectedVersion))
                .returning().fetchOne();
        if (row == null) throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                ApiMessage.of("api.third-party.conflict"), ApiMessage.of("api.third-party.sync-settings-conflict"), false);
        return new ThirdPartyPromptSyncSettings(row.getEnabled(), deploymentEnabled, row.getVersion());
    }
}

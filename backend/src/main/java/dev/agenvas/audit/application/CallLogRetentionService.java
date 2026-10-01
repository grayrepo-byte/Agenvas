package dev.agenvas.audit.application;

import dev.agenvas.audit.domain.CallLogRetentionSettings;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CallLogRetentionService {
    public static final int BATCH_SIZE = 1000;
    public static final int MAX_BATCHES = 10;
    private final CallLogRepository repository;
    private final Clock clock;

    public CallLogRetentionService(CallLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }
    public CallLogRetentionSettings settings() { return repository.retentionSettings(); }

    public CallLogRetentionSettings update(Integer days, int expectedVersion) {
        if (days != null && (days < CallLogRetentionSettings.MIN_DAYS || days > CallLogRetentionSettings.MAX_DAYS)
                || expectedVersion < 1) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    ApiMessage.of("api.audit-retention.invalid-title"), ApiMessage.of("api.audit-retention.invalid-detail"), false);
        }
        return repository.updateRetentionSettings(days, expectedVersion).orElseThrow(() ->
                new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        ApiMessage.of("api.audit-retention.conflict-title"), ApiMessage.of("api.audit-retention.conflict-detail"), false));
    }

    /** Each batch is a separate short transaction; reread the policy between batches. */
    public int cleanExpired() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = repository.purgeExpired(clock.instant(), BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) break;
        }
        return total;
    }
}

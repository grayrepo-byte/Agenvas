package dev.agenvas.provider.infrastructure;

import static dev.agenvas.db.Tables.MEDIA_FUNCTION_SETTING;

import dev.agenvas.provider.domain.MediaFunction;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Function routing uses CAS; endpoint credentials remain exclusively in the capability catalog. */
@Repository
public class JooqMediaFunctionRepository {
    public record Setting(MediaFunction operation, UUID capabilityId, long version) {}
    private final DSLContext dsl;

    public JooqMediaFunctionRepository(DSLContext dsl) { this.dsl = dsl; }

    public List<Setting> list() {
        return dsl.selectFrom(MEDIA_FUNCTION_SETTING).orderBy(MEDIA_FUNCTION_SETTING.OPERATION)
                .fetch(row -> new Setting(MediaFunction.valueOf(row.getOperation()),
                        row.getCapabilityId(), row.getVersion()));
    }

    public Setting get(MediaFunction operation) {
        return dsl.selectFrom(MEDIA_FUNCTION_SETTING)
                .where(MEDIA_FUNCTION_SETTING.OPERATION.eq(operation.name()))
                .fetchOptional(row -> new Setting(operation, row.getCapabilityId(), row.getVersion()))
                .orElseThrow(() -> new IllegalStateException("Missing media function setting"));
    }

    public boolean update(MediaFunction operation, long expectedVersion, UUID capabilityId, Instant now) {
        return dsl.update(MEDIA_FUNCTION_SETTING).set(MEDIA_FUNCTION_SETTING.CAPABILITY_ID, capabilityId)
                .set(MEDIA_FUNCTION_SETTING.VERSION, MEDIA_FUNCTION_SETTING.VERSION.plus(1))
                .set(MEDIA_FUNCTION_SETTING.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
                .where(MEDIA_FUNCTION_SETTING.OPERATION.eq(operation.name()))
                .and(MEDIA_FUNCTION_SETTING.VERSION.eq(expectedVersion)).execute() == 1;
    }
}

package dev.agenvas.asset.storage;

import static dev.agenvas.db.Tables.MEDIA_RELAY_OBJECT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Copies survive project/task deletion so cleanup can recover after a restart or failed upload. */
@Repository
public class MediaRelayRepository {
    private static final int CLEANUP_BATCH_SIZE = 32;
    private final DSLContext dsl;
    public MediaRelayRepository(DSLContext dsl) { this.dsl = dsl; }
    public record Copy(UUID id, UUID profileId, String key, Instant expiresAt) {}
    public void register(Copy copy, Instant createdAt) {
        dsl.insertInto(MEDIA_RELAY_OBJECT).set(MEDIA_RELAY_OBJECT.ID, copy.id())
                .set(MEDIA_RELAY_OBJECT.PROFILE_ID, copy.profileId()).set(MEDIA_RELAY_OBJECT.OBJECT_KEY, copy.key())
                .set(MEDIA_RELAY_OBJECT.EXPIRES_AT, utc(copy.expiresAt())).set(MEDIA_RELAY_OBJECT.CREATED_AT, utc(createdAt)).execute();
    }
    public List<Copy> expired(Instant now) {
        return dsl.selectFrom(MEDIA_RELAY_OBJECT).where(MEDIA_RELAY_OBJECT.EXPIRES_AT.le(utc(now)))
                .orderBy(MEDIA_RELAY_OBJECT.EXPIRES_AT, MEDIA_RELAY_OBJECT.ID).limit(CLEANUP_BATCH_SIZE)
                .fetch(r -> new Copy(r.getId(), r.getProfileId(), r.getObjectKey(), r.getExpiresAt().toInstant()));
    }
    public void removed(Copy copy) {
        // DELETE of an expired, never-reused UUID object is idempotent across cleanup workers.
        dsl.deleteFrom(MEDIA_RELAY_OBJECT).where(MEDIA_RELAY_OBJECT.ID.eq(copy.id()))
                .and(MEDIA_RELAY_OBJECT.EXPIRES_AT.eq(utc(copy.expiresAt()))).execute();
    }
    private static OffsetDateTime utc(Instant time) { return OffsetDateTime.ofInstant(time, ZoneOffset.UTC); }
}

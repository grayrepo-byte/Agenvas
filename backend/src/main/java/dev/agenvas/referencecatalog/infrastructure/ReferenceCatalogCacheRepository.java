package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.db.Tables.REFERENCE_CATALOG_CACHE_JOB;
import dev.agenvas.referencecatalog.domain.ReferenceCatalogCacheJob;
import dev.agenvas.referencecatalog.domain.ReferenceCatalogCacheJob.*;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Short database claims and checkpoints; no network or cache file lock is held by a transaction. */
@Repository
public class ReferenceCatalogCacheRepository {
    private final DSLContext db;private final ObjectMapper mapper;
    public ReferenceCatalogCacheRepository(DSLContext db,ObjectMapper mapper){this.db=db;this.mapper=mapper;}
    public void lockQueue(){db.fetch("select pg_advisory_xact_lock(?)",8265300L);}
    public int activeCount(){return db.fetchCount(REFERENCE_CATALOG_CACHE_JOB,REFERENCE_CATALOG_CACHE_JOB.STATE.in("QUEUED","RUNNING"));}
    public List<ReferenceCatalogCacheJob> list(){return db.selectFrom(REFERENCE_CATALOG_CACHE_JOB).orderBy(REFERENCE_CATALOG_CACHE_JOB.CREATED_AT.desc()).limit(50).fetch(this::map);}
    public Optional<ReferenceCatalogCacheJob> get(UUID id,boolean lock){var query=db.selectFrom(REFERENCE_CATALOG_CACHE_JOB).where(REFERENCE_CATALOG_CACHE_JOB.ID.eq(id));return(lock?query.forUpdate().fetchOptional():query.fetchOptional()).map(this::map);}
    public Optional<ReferenceCatalogCacheJob> command(UUID owner,String key){return db.selectFrom(REFERENCE_CATALOG_CACHE_JOB).where(REFERENCE_CATALOG_CACHE_JOB.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_CACHE_JOB.COMMAND_KEY.eq(key))).fetchOptional(this::map);}
    public void insert(ReferenceCatalogCacheJob job){db.insertInto(REFERENCE_CATALOG_CACHE_JOB).set(REFERENCE_CATALOG_CACHE_JOB.ID,job.id()).set(REFERENCE_CATALOG_CACHE_JOB.OWNER_ID,job.ownerId())
            .set(REFERENCE_CATALOG_CACHE_JOB.SOURCE_ID,job.sourceId()).set(REFERENCE_CATALOG_CACHE_JOB.SNAPSHOT_ID,job.snapshotId()).set(REFERENCE_CATALOG_CACHE_JOB.COMMAND_KEY,job.commandKey())
            .set(REFERENCE_CATALOG_CACHE_JOB.PAYLOAD_HASH,job.payloadHash()).set(REFERENCE_CATALOG_CACHE_JOB.STATE,job.state().name()).set(REFERENCE_CATALOG_CACHE_JOB.ITEMS_JSON,json(job.items()))
            .set(REFERENCE_CATALOG_CACHE_JOB.CREATED_AT,time(job.createdAt())).set(REFERENCE_CATALOG_CACHE_JOB.UPDATED_AT,time(job.updatedAt())).execute();}
    public Optional<ReferenceCatalogCacheJob> claim(Instant now){
        var selected=db.selectFrom(REFERENCE_CATALOG_CACHE_JOB).where(REFERENCE_CATALOG_CACHE_JOB.STATE.eq("QUEUED").or(REFERENCE_CATALOG_CACHE_JOB.STATE.eq("RUNNING").and(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL.le(time(now)))))
                .orderBy(REFERENCE_CATALOG_CACHE_JOB.CREATED_AT).limit(1).forUpdate().skipLocked().fetchOptional();
        if(selected.isEmpty())return Optional.empty();UUID token=UUID.randomUUID();UUID id=selected.get().getId();
        db.update(REFERENCE_CATALOG_CACHE_JOB).set(REFERENCE_CATALOG_CACHE_JOB.STATE,"RUNNING").set(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN,token).set(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL,time(now.plusSeconds(180)))
                .set(REFERENCE_CATALOG_CACHE_JOB.UPDATED_AT,time(now)).where(REFERENCE_CATALOG_CACHE_JOB.ID.eq(id)).execute();return get(id,false);
    }
    public boolean owns(UUID id,UUID token,Instant now){return db.fetchExists(REFERENCE_CATALOG_CACHE_JOB,REFERENCE_CATALOG_CACHE_JOB.ID.eq(id).and(REFERENCE_CATALOG_CACHE_JOB.STATE.eq("RUNNING"))
            .and(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN.eq(token)).and(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL.gt(time(now))));}
    public boolean progress(UUID id,UUID token,List<Item> items,State state,Instant now){
        var update=db.update(REFERENCE_CATALOG_CACHE_JOB).set(REFERENCE_CATALOG_CACHE_JOB.ITEMS_JSON,json(items)).set(REFERENCE_CATALOG_CACHE_JOB.STATE,state.name()).set(REFERENCE_CATALOG_CACHE_JOB.UPDATED_AT,time(now));
        if(state==State.RUNNING)update.set(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL,time(now.plusSeconds(180)));
        else update.setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN).setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL);
        return update.where(REFERENCE_CATALOG_CACHE_JOB.ID.eq(id).and(REFERENCE_CATALOG_CACHE_JOB.STATE.eq("RUNNING")).and(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN.eq(token)).and(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL.gt(time(now)))).execute()==1;
    }
    public void retry(UUID id,List<Item> items,Instant now){if(db.update(REFERENCE_CATALOG_CACHE_JOB).set(REFERENCE_CATALOG_CACHE_JOB.STATE,"QUEUED").set(REFERENCE_CATALOG_CACHE_JOB.ITEMS_JSON,json(items))
            .set(REFERENCE_CATALOG_CACHE_JOB.UPDATED_AT,time(now)).setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN).setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL)
            .where(REFERENCE_CATALOG_CACHE_JOB.ID.eq(id).and(REFERENCE_CATALOG_CACHE_JOB.STATE.eq("PARTIAL_FAILED"))).execute()!=1)throw new IllegalStateException("Locked cache job changed");}
    public void cancel(UUID id,Instant now){db.update(REFERENCE_CATALOG_CACHE_JOB).set(REFERENCE_CATALOG_CACHE_JOB.STATE,"CANCELLED").setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN).setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL)
            .set(REFERENCE_CATALOG_CACHE_JOB.UPDATED_AT,time(now)).where(REFERENCE_CATALOG_CACHE_JOB.ID.eq(id).and(REFERENCE_CATALOG_CACHE_JOB.STATE.in("QUEUED","RUNNING"))).execute();}
    public void cancelMatching(String source,UUID snapshot,Instant now){var condition=REFERENCE_CATALOG_CACHE_JOB.STATE.in("QUEUED","RUNNING");if(source!=null)condition=condition.and(REFERENCE_CATALOG_CACHE_JOB.SOURCE_ID.eq(source));if(snapshot!=null)condition=condition.and(REFERENCE_CATALOG_CACHE_JOB.SNAPSHOT_ID.eq(snapshot));
        db.update(REFERENCE_CATALOG_CACHE_JOB).set(REFERENCE_CATALOG_CACHE_JOB.STATE,"CANCELLED").setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_TOKEN).setNull(REFERENCE_CATALOG_CACHE_JOB.LEASE_UNTIL).set(REFERENCE_CATALOG_CACHE_JOB.UPDATED_AT,time(now)).where(condition).execute();}
    private ReferenceCatalogCacheJob map(dev.agenvas.db.tables.records.ReferenceCatalogCacheJobRecord r){
        Progress progress=mapper.readValue(r.getItemsJson().data(),Progress.class);if(progress.schemaVersion()!=1)throw new IllegalStateException("Unknown cache progress schema");
        return new ReferenceCatalogCacheJob(r.getId(),r.getOwnerId(),r.getSourceId(),r.getSnapshotId(),r.getCommandKey(),r.getPayloadHash(),State.valueOf(r.getState()),progress.items(),r.getLeaseToken(),r.getLeaseUntil()==null?null:r.getLeaseUntil().toInstant(),r.getCreatedAt().toInstant(),r.getUpdatedAt().toInstant());
    }
    private JSONB json(List<Item> items){return JSONB.valueOf(mapper.writeValueAsString(new Progress(1,List.copyOf(items))));}
    private static OffsetDateTime time(Instant value){return value.atOffset(ZoneOffset.UTC);}
}

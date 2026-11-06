package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.db.Tables.*;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Owner index is always consulted before file hashes; shared physical bytes never grant access. */
@Repository
public class ReferenceCatalogPrivateImageRepository {
    private final DSLContext db;
    public ReferenceCatalogPrivateImageRepository(DSLContext db){this.db=db;}
    public record Image(UUID id,UUID ownerId,String sourceId,UUID snapshotId,String entryId,String variantId,String path,String blobSha,Long byteSize,String sha256,Integer width,Integer height,boolean enabled){}
    public record Command(String payloadHash,UUID imageId){}
    public void lockAcceptance(){db.fetch("select pg_advisory_xact_lock(?)",8265400L);}
    public Optional<Image> owned(UUID owner,UUID id){return db.selectFrom(REFERENCE_CATALOG_PRIVATE_IMAGE).where(REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_PRIVATE_IMAGE.ID.eq(id))).fetchOptional(this::map);}
    public Optional<Image> selection(UUID owner,UUID snapshot,String entry,String variant){return db.selectFrom(REFERENCE_CATALOG_PRIVATE_IMAGE).where(REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID.eq(owner)
            .and(REFERENCE_CATALOG_PRIVATE_IMAGE.SNAPSHOT_ID.eq(snapshot)).and(REFERENCE_CATALOG_PRIVATE_IMAGE.ENTRY_ID.eq(entry)).and(REFERENCE_CATALOG_PRIVATE_IMAGE.VARIANT_ID.eq(variant))).fetchOptional(this::map);}
    public List<Image> list(UUID owner,String source,UUID snapshot){var c=REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_PRIVATE_IMAGE.SHA256.isNotNull());if(source!=null)c=c.and(REFERENCE_CATALOG_PRIVATE_IMAGE.SOURCE_ID.eq(source));if(snapshot!=null)c=c.and(REFERENCE_CATALOG_PRIVATE_IMAGE.SNAPSHOT_ID.eq(snapshot));
        return db.selectFrom(REFERENCE_CATALOG_PRIVATE_IMAGE).where(c).orderBy(REFERENCE_CATALOG_PRIVATE_IMAGE.CREATED_AT.desc()).limit(5000).fetch(this::map);}
    public Optional<Command> command(UUID owner,String key){return db.selectFrom(REFERENCE_CATALOG_PRIVATE_COMMAND).where(REFERENCE_CATALOG_PRIVATE_COMMAND.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_PRIVATE_COMMAND.COMMAND_KEY.eq(key)))
            .fetchOptional(r->new Command(r.getPayloadHash(),r.getImageId()));}
    public void insert(Image image,Instant now){db.insertInto(REFERENCE_CATALOG_PRIVATE_IMAGE).set(REFERENCE_CATALOG_PRIVATE_IMAGE.ID,image.id()).set(REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID,image.ownerId())
            .set(REFERENCE_CATALOG_PRIVATE_IMAGE.SOURCE_ID,image.sourceId()).set(REFERENCE_CATALOG_PRIVATE_IMAGE.SNAPSHOT_ID,image.snapshotId()).set(REFERENCE_CATALOG_PRIVATE_IMAGE.ENTRY_ID,image.entryId())
            .set(REFERENCE_CATALOG_PRIVATE_IMAGE.VARIANT_ID,image.variantId()).set(REFERENCE_CATALOG_PRIVATE_IMAGE.REPOSITORY_PATH,image.path()).set(REFERENCE_CATALOG_PRIVATE_IMAGE.CREATED_AT,time(now)).set(REFERENCE_CATALOG_PRIVATE_IMAGE.UPDATED_AT,time(now)).execute();}
    public void insertCommand(UUID owner,String key,String hash,UUID image,Instant now){db.insertInto(REFERENCE_CATALOG_PRIVATE_COMMAND).set(REFERENCE_CATALOG_PRIVATE_COMMAND.OWNER_ID,owner).set(REFERENCE_CATALOG_PRIVATE_COMMAND.COMMAND_KEY,key)
            .set(REFERENCE_CATALOG_PRIVATE_COMMAND.PAYLOAD_HASH,hash).set(REFERENCE_CATALOG_PRIVATE_COMMAND.IMAGE_ID,image).set(REFERENCE_CATALOG_PRIVATE_COMMAND.CREATED_AT,time(now)).execute();}
    public Optional<Image> knownProof(Image image){return db.selectFrom(REFERENCE_CATALOG_PRIVATE_IMAGE).where(REFERENCE_CATALOG_PRIVATE_IMAGE.SOURCE_ID.eq(image.sourceId()).and(REFERENCE_CATALOG_PRIVATE_IMAGE.SNAPSHOT_ID.eq(image.snapshotId()))
            .and(REFERENCE_CATALOG_PRIVATE_IMAGE.REPOSITORY_PATH.eq(image.path())).and(REFERENCE_CATALOG_PRIVATE_IMAGE.BLOB_SHA.isNotNull())).limit(1).fetchOptional(this::map);}
    public void proof(UUID owner,UUID id,String blob,long size){if(db.update(REFERENCE_CATALOG_PRIVATE_IMAGE).set(REFERENCE_CATALOG_PRIVATE_IMAGE.BLOB_SHA,blob).set(REFERENCE_CATALOG_PRIVATE_IMAGE.BYTE_SIZE,size)
            .where(REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_PRIVATE_IMAGE.ID.eq(id)).and(REFERENCE_CATALOG_PRIVATE_IMAGE.BLOB_SHA.isNull())).execute()!=1){var existing=owned(owner,id).orElseThrow();if(!blob.equals(existing.blobSha()) || existing.byteSize()!=size)throw new IllegalStateException("Frozen private image proof changed");}}
    public Optional<Image> verifiedBlob(String blob,long size){return db.selectFrom(REFERENCE_CATALOG_PRIVATE_IMAGE).where(REFERENCE_CATALOG_PRIVATE_IMAGE.BLOB_SHA.eq(blob).and(REFERENCE_CATALOG_PRIVATE_IMAGE.BYTE_SIZE.eq(size)).and(REFERENCE_CATALOG_PRIVATE_IMAGE.SHA256.isNotNull())).limit(1).fetchOptional(this::map);}
    public Image publish(UUID owner,UUID id,String hash,int width,int height,Instant now){var current=owned(owner,id).orElseThrow();if(current.sha256()!=null && (!current.sha256().equals(hash) || current.width()!=width || current.height()!=height))throw new IllegalStateException("Observed private image integrity cannot change");
        if(db.update(REFERENCE_CATALOG_PRIVATE_IMAGE).set(REFERENCE_CATALOG_PRIVATE_IMAGE.SHA256,hash).set(REFERENCE_CATALOG_PRIVATE_IMAGE.WIDTH,width).set(REFERENCE_CATALOG_PRIVATE_IMAGE.HEIGHT,height)
                .set(REFERENCE_CATALOG_PRIVATE_IMAGE.ENABLED,true).set(REFERENCE_CATALOG_PRIVATE_IMAGE.UPDATED_AT,time(now)).where(REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_PRIVATE_IMAGE.ID.eq(id))).execute()!=1)throw new IllegalStateException("Private image disappeared");return owned(owner,id).orElseThrow();}
    public void disable(UUID owner,UUID id,Instant now){db.update(REFERENCE_CATALOG_PRIVATE_IMAGE).set(REFERENCE_CATALOG_PRIVATE_IMAGE.ENABLED,false).set(REFERENCE_CATALOG_PRIVATE_IMAGE.UPDATED_AT,time(now))
            .where(REFERENCE_CATALOG_PRIVATE_IMAGE.OWNER_ID.eq(owner).and(REFERENCE_CATALOG_PRIVATE_IMAGE.ID.eq(id))).execute();}
    public Set<String> protectedHashes(){return new HashSet<>(db.selectDistinct(REFERENCE_CATALOG_PRIVATE_IMAGE.SHA256).from(REFERENCE_CATALOG_PRIVATE_IMAGE).where(REFERENCE_CATALOG_PRIVATE_IMAGE.ENABLED.isTrue().and(REFERENCE_CATALOG_PRIVATE_IMAGE.SHA256.isNotNull())).fetch(REFERENCE_CATALOG_PRIVATE_IMAGE.SHA256));}
    private Image map(dev.agenvas.db.tables.records.ReferenceCatalogPrivateImageRecord r){return new Image(r.getId(),r.getOwnerId(),r.getSourceId(),r.getSnapshotId(),r.getEntryId(),r.getVariantId(),r.getRepositoryPath(),r.getBlobSha(),r.getByteSize(),r.getSha256(),r.getWidth(),r.getHeight(),r.getEnabled());}
    private static OffsetDateTime time(Instant value){return value.atOffset(ZoneOffset.UTC);}
}

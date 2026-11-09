package dev.agenvas.referencecatalog.infrastructure;

import static dev.agenvas.db.Tables.CUSTOM_COSTUME_REFERENCE;
import static dev.agenvas.db.Tables.CUSTOM_COSTUME_SAVE_COMMAND;

import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import java.time.*;
import java.util.*;
import org.jooq.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.*;

/** Self-created entries never participate in external source or cache cleanup. */
@Repository
public class CustomCostumeRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public CustomCostumeRepository(DSLContext db,ObjectMapper mapper){this.db=db;this.mapper=mapper;}
    public record Costume(UUID id,UUID owner,String name,String category,List<String> tags,Media media,long version){}
    public record Tags(List<String> values){}
    public List<Costume> list(){return db.selectFrom(CUSTOM_COSTUME_REFERENCE).where(CUSTOM_COSTUME_REFERENCE.DELETED_AT.isNull())
            .orderBy(CUSTOM_COSTUME_REFERENCE.CREATED_AT,CUSTOM_COSTUME_REFERENCE.ID).fetch(this::costume);}
    public Optional<Costume> find(UUID id){return db.selectFrom(CUSTOM_COSTUME_REFERENCE).where(CUSTOM_COSTUME_REFERENCE.ID.eq(id).and(CUSTOM_COSTUME_REFERENCE.DELETED_AT.isNull())).fetchOptional(this::costume);}
    /** Row lock orders acceptance against metadata updates and deletion, without holding it during archive I/O. */
    public Optional<Costume> lock(UUID id){return db.selectFrom(CUSTOM_COSTUME_REFERENCE).where(CUSTOM_COSTUME_REFERENCE.ID.eq(id).and(CUSTOM_COSTUME_REFERENCE.DELETED_AT.isNull())).forUpdate().fetchOptional(this::costume);}
    private Costume costume(dev.agenvas.db.tables.records.CustomCostumeReferenceRecord row){return new Costume(row.getId(),row.getOwnerId(),row.getName(),row.getCategory(),mapper.readValue(row.getTagsJson().data(),Tags.class).values(),mapper.readValue(row.getMediaJson().data(),Media.class),row.getVersion());}
    public void insert(Costume c,Instant now){db.insertInto(CUSTOM_COSTUME_REFERENCE).set(CUSTOM_COSTUME_REFERENCE.ID,c.id()).set(CUSTOM_COSTUME_REFERENCE.OWNER_ID,c.owner())
            .set(CUSTOM_COSTUME_REFERENCE.NAME,c.name()).set(CUSTOM_COSTUME_REFERENCE.CATEGORY,c.category()).set(CUSTOM_COSTUME_REFERENCE.TAGS_JSON,json(new Tags(c.tags())))
            .set(CUSTOM_COSTUME_REFERENCE.MEDIA_JSON,json(c.media())).set(CUSTOM_COSTUME_REFERENCE.VERSION,0L).set(CUSTOM_COSTUME_REFERENCE.CREATED_AT,time(now)).set(CUSTOM_COSTUME_REFERENCE.UPDATED_AT,time(now)).execute();}
    public boolean update(UUID id,long expected,String name,String category,List<String> tags,Instant now){return db.update(CUSTOM_COSTUME_REFERENCE).set(CUSTOM_COSTUME_REFERENCE.NAME,name)
            .set(CUSTOM_COSTUME_REFERENCE.CATEGORY,category).set(CUSTOM_COSTUME_REFERENCE.TAGS_JSON,json(new Tags(tags))).set(CUSTOM_COSTUME_REFERENCE.VERSION,CUSTOM_COSTUME_REFERENCE.VERSION.plus(1))
            .set(CUSTOM_COSTUME_REFERENCE.UPDATED_AT,time(now)).where(CUSTOM_COSTUME_REFERENCE.ID.eq(id).and(CUSTOM_COSTUME_REFERENCE.VERSION.eq(expected)).and(CUSTOM_COSTUME_REFERENCE.DELETED_AT.isNull())).execute()==1;}
    public boolean delete(UUID id,long expected,Instant now){return db.update(CUSTOM_COSTUME_REFERENCE).set(CUSTOM_COSTUME_REFERENCE.DELETED_AT,time(now)).set(CUSTOM_COSTUME_REFERENCE.UPDATED_AT,time(now))
            .set(CUSTOM_COSTUME_REFERENCE.VERSION,CUSTOM_COSTUME_REFERENCE.VERSION.plus(1)).where(CUSTOM_COSTUME_REFERENCE.ID.eq(id).and(CUSTOM_COSTUME_REFERENCE.VERSION.eq(expected)).and(CUSTOM_COSTUME_REFERENCE.DELETED_AT.isNull())).execute()==1;}
    public Optional<ReferenceCatalogRepository.Command> command(UUID owner,UUID project,String key,boolean lock){var q=db.selectFrom(CUSTOM_COSTUME_SAVE_COMMAND).where(CUSTOM_COSTUME_SAVE_COMMAND.OWNER_ID.eq(owner).and(CUSTOM_COSTUME_SAVE_COMMAND.PROJECT_ID.eq(project)).and(CUSTOM_COSTUME_SAVE_COMMAND.COMMAND_KEY.eq(key)));
        return (lock?q.forUpdate().fetchOptional():q.fetchOptional()).map(r->new ReferenceCatalogRepository.Command(r.getId(),r.getPayloadHash(),mapper.readTree(r.getInputJson().data()),r.getResultJson()==null?null:mapper.readTree(r.getResultJson().data())));}
    public void insertCommand(ReferenceCatalogRepository.Command c,UUID owner,UUID project,String key,Instant now){db.insertInto(CUSTOM_COSTUME_SAVE_COMMAND).set(CUSTOM_COSTUME_SAVE_COMMAND.ID,c.id()).set(CUSTOM_COSTUME_SAVE_COMMAND.OWNER_ID,owner)
            .set(CUSTOM_COSTUME_SAVE_COMMAND.PROJECT_ID,project).set(CUSTOM_COSTUME_SAVE_COMMAND.COMMAND_KEY,key).set(CUSTOM_COSTUME_SAVE_COMMAND.PAYLOAD_HASH,c.payloadHash()).set(CUSTOM_COSTUME_SAVE_COMMAND.INPUT_JSON,json(c.input())).set(CUSTOM_COSTUME_SAVE_COMMAND.CREATED_AT,time(now)).execute();}
    public void complete(UUID id,JsonNode result){if(db.update(CUSTOM_COSTUME_SAVE_COMMAND).set(CUSTOM_COSTUME_SAVE_COMMAND.RESULT_JSON,json(result)).where(CUSTOM_COSTUME_SAVE_COMMAND.ID.eq(id).and(CUSTOM_COSTUME_SAVE_COMMAND.RESULT_JSON.isNull())).execute()!=1)throw new IllegalStateException("Locked costume save changed");}
    private JSONB json(Object value){return JSONB.valueOf(mapper.writeValueAsString(value));}
    private static OffsetDateTime time(Instant value){return value.atOffset(ZoneOffset.UTC);}
}

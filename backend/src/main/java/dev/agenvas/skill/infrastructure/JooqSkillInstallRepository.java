package dev.agenvas.skill.infrastructure;

import static dev.agenvas.db.tables.SkillInstallOperation.SKILL_INSTALL_OPERATION;
import static dev.agenvas.db.tables.SkillInstallCommand.SKILL_INSTALL_COMMAND;
import static dev.agenvas.db.tables.SkillBindingCommand.SKILL_BINDING_COMMAND;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Database-owned preparation leases and command identities for Agent Skill installation. */
@Repository
public class JooqSkillInstallRepository {
    public record Operation(UUID id, UUID ownerId, UUID projectId, UUID skillId, UUID skillVersionId,
            String commandKey, String payloadHash, JsonNode input, JsonNode result, JsonNode cleanup, String status,
            long epoch, Instant leaseUntil, String errorCode, String errorDetail) {
        /** Committed project mappings stay usable while unrelated temporary files are cleaned. */
        public boolean registered() { return result != null && result.path("assets").isArray(); }
    }
    public record Command(String payloadHash, UUID operationId) {}
    public record BindingCommand(String payloadHash, JsonNode response) {}
    private static final String ACCEPTED_STATUS = "ACCEPTED";
    private static final String PREPARING_STATUS = "PREPARING";
    private static final String SUCCEEDED_STATUS = "SUCCEEDED";
    private static final String FAILED_STATUS = "FAILED";
    private static final String CLEANING_STATUS = "CLEANING";
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public JooqSkillInstallRepository(DSLContext dsl, ObjectMapper mapper) { this.dsl = dsl; this.mapper = mapper; }
    public Optional<Command> command(UUID owner, UUID project, String key) {
        var t = SKILL_INSTALL_COMMAND;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner)).and(t.PROJECT_ID.eq(project)).and(t.COMMAND_KEY.eq(key))
                .fetchOptional(r -> new Command(r.get(t.PAYLOAD_HASH).trim(), r.get(t.OPERATION_ID)));
    }
    public void saveCommand(UUID owner, UUID project, String key, String hash, UUID operation, Instant now) {
        var t = SKILL_INSTALL_COMMAND;
        dsl.insertInto(t).set(t.OWNER_ID,owner).set(t.PROJECT_ID,project).set(t.COMMAND_KEY,key)
                .set(t.PAYLOAD_HASH,hash).set(t.OPERATION_ID,operation).set(t.CREATED_AT,time(now)).execute();
    }
    public Optional<BindingCommand> bindingCommand(UUID owner, UUID project, String key) {
        var t = SKILL_BINDING_COMMAND;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner)).and(t.PROJECT_ID.eq(project)).and(t.COMMAND_KEY.eq(key))
                .fetchOptional(r -> new BindingCommand(r.get(t.PAYLOAD_HASH).trim(), read(r.get(t.RESPONSE_JSON))));
    }
    public void saveBindingCommand(UUID owner, UUID project, UUID agent, String key, String hash, JsonNode response, Instant now) {
        var t = SKILL_BINDING_COMMAND;
        dsl.insertInto(t).set(t.OWNER_ID,owner).set(t.PROJECT_ID,project).set(t.AGENT_ID,agent)
                .set(t.COMMAND_KEY,key).set(t.PAYLOAD_HASH,hash).set(t.RESPONSE_JSON,json(response)).set(t.CREATED_AT,time(now)).execute();
    }
    public Optional<Operation> find(UUID owner, UUID project, UUID id) {
        var t = SKILL_INSTALL_OPERATION;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner)).and(t.PROJECT_ID.eq(project)).and(t.ID.eq(id)).fetchOptional(this::map);
    }
    public Optional<Operation> version(UUID owner, UUID project, UUID version) {
        var t = SKILL_INSTALL_OPERATION;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner)).and(t.PROJECT_ID.eq(project)).and(t.SKILL_VERSION_ID.eq(version)).fetchOptional(this::map);
    }
    public void create(Operation op, Instant now) {
        var t = SKILL_INSTALL_OPERATION;
        dsl.insertInto(t).set(t.ID,op.id()).set(t.OWNER_ID,op.ownerId()).set(t.PROJECT_ID,op.projectId())
                .set(t.SKILL_ID,op.skillId()).set(t.SKILL_VERSION_ID,op.skillVersionId()).set(t.COMMAND_KEY,op.commandKey())
                .set(t.PAYLOAD_HASH,op.payloadHash()).set(t.INPUT_JSON,json(op.input())).set(t.STATUS,op.status())
                .set(t.EPOCH,0L).set(t.CREATED_AT,time(now)).set(t.UPDATED_AT,time(now)).execute();
    }
    public boolean retry(Operation op, Instant now) {
        var t=SKILL_INSTALL_OPERATION;
        return dsl.update(t).set(t.STATUS,ACCEPTED_STATUS).set(t.EPOCH,op.epoch()+1).setNull(t.ERROR_CODE).setNull(t.ERROR_DETAIL)
                .setNull(t.RESULT_JSON).set(t.UPDATED_AT,time(now)).where(t.ID.eq(op.id())).and(t.STATUS.eq(FAILED_STATUS)).and(t.EPOCH.eq(op.epoch())).execute()==1;
    }
    public Optional<Operation> claim(Instant now, Instant until) {
        var t=SKILL_INSTALL_OPERATION;
        Optional<Operation> op = dsl.selectFrom(t).where(t.STATUS.eq(ACCEPTED_STATUS).or(t.STATUS.eq(PREPARING_STATUS).and(t.LEASE_UNTIL.le(time(now)))))
                .orderBy(t.CREATED_AT,t.ID).limit(1).forUpdate().skipLocked().fetchOptional(this::map);
        if(op.isEmpty()) return op;
        Operation found=op.get();
        if(dsl.update(t).set(t.STATUS,PREPARING_STATUS).set(t.EPOCH,found.epoch()+1).set(t.LEASE_UNTIL,time(until))
                .set(t.UPDATED_AT,time(now)).where(t.ID.eq(found.id())).and(t.EPOCH.eq(found.epoch())).execute()!=1) return Optional.empty();
        return find(found.ownerId(),found.projectId(),found.id());
    }
    /** Records file ownership even if its preparation lease expires during a local copy. */
    public void trackPrepared(Operation op, JsonNode asset) {
        var t=SKILL_INSTALL_OPERATION;
        var row=dsl.selectFrom(t).where(t.ID.eq(op.id())).and(t.OWNER_ID.eq(op.ownerId())).and(t.PROJECT_ID.eq(op.projectId()))
                .forUpdate().fetchOptional();
        if(row.isEmpty()) return;
        var cleanup=(tools.jackson.databind.node.ArrayNode)read(row.get().get(t.CLEANUP_JSON));
        int tracked=-1;
        for(int index=0;index<cleanup.size();index++) if(cleanup.get(index).path("id").asText().equals(asset.path("id").asText()))tracked=index;
        if(tracked<0)cleanup.add(asset.deepCopy());
        else if(asset.path("objectKey").isTextual())cleanup.set(tracked,asset.deepCopy());
        dsl.update(t).set(t.CLEANUP_JSON,json(cleanup)).where(t.ID.eq(op.id())).execute();
    }
    /** Keeps prepared metadata durable before any final registration or cleanup decision. */
    public boolean checkpoint(Operation op, JsonNode progress, Instant now) {
        var t=SKILL_INSTALL_OPERATION;
        return dsl.update(t).set(t.RESULT_JSON,json(progress)).set(t.UPDATED_AT,time(now))
                .where(t.ID.eq(op.id())).and(t.STATUS.eq(PREPARING_STATUS)).and(t.EPOCH.eq(op.epoch()))
                .and(t.LEASE_UNTIL.gt(time(now))).execute()==1;
    }
    public Optional<Operation> claimCleanup(Instant now, Instant until) {
        var t=SKILL_INSTALL_OPERATION;
        var found=dsl.selectFrom(t).where(t.STATUS.in(FAILED_STATUS,SUCCEEDED_STATUS).and(t.CLEANUP_JSON.ne(JSONB.valueOf("[]")))
                .or(t.STATUS.eq(CLEANING_STATUS).and(t.LEASE_UNTIL.le(time(now)))))
                .orderBy(t.UPDATED_AT,t.ID).limit(1).forUpdate().skipLocked().fetchOptional(this::map);
        if(found.isEmpty()) return found;
        Operation op=found.get();
        if(dsl.update(t).set(t.STATUS,CLEANING_STATUS).set(t.EPOCH,op.epoch()+1).set(t.LEASE_UNTIL,time(until)).set(t.UPDATED_AT,time(now))
                .where(t.ID.eq(op.id())).and(t.EPOCH.eq(op.epoch())).execute()!=1)return Optional.empty();
        return find(op.ownerId(),op.projectId(),op.id());
    }
    public boolean finishCleanup(Operation op, Instant now) {
        var t=SKILL_INSTALL_OPERATION;
        var row=dsl.selectFrom(t).where(t.ID.eq(op.id())).and(t.STATUS.eq(CLEANING_STATUS)).and(t.EPOCH.eq(op.epoch()))
                .and(t.LEASE_UNTIL.gt(time(now))).forUpdate().fetchOptional();
        if(row.isEmpty())return false;
        java.util.Set<String> cleaned=new java.util.HashSet<>();
        op.cleanup().forEach(asset -> cleaned.add(asset.path("id").asText()));
        var remaining=mapper.createArrayNode();
        read(row.get().get(t.CLEANUP_JSON)).forEach(asset -> {if(!cleaned.contains(asset.path("id").asText()))remaining.add(asset);});
        String status=op.registered()?SUCCEEDED_STATUS:FAILED_STATUS;
        return dsl.update(t).set(t.STATUS,status).set(t.CLEANUP_JSON,json(remaining)).setNull(t.LEASE_UNTIL).set(t.UPDATED_AT,time(now))
                .where(t.ID.eq(op.id())).and(t.STATUS.eq(CLEANING_STATUS)).and(t.EPOCH.eq(op.epoch())).execute()==1;
    }
    public boolean active(Operation op, Instant now) {
        var t=SKILL_INSTALL_OPERATION;
        return dsl.selectFrom(t).where(t.ID.eq(op.id())).and(t.STATUS.eq(PREPARING_STATUS)).and(t.EPOCH.eq(op.epoch()))
                .and(t.LEASE_UNTIL.gt(time(now))).forUpdate().fetchOptional().isPresent();
    }
    public boolean finish(Operation op, String status, JsonNode result, String code, String detail, Instant now) {
        var t=SKILL_INSTALL_OPERATION;
        return dsl.update(t).set(t.STATUS,status).set(t.RESULT_JSON,result==null?null:json(result)).setNull(t.LEASE_UNTIL)
                .set(t.ERROR_CODE,code).set(t.ERROR_DETAIL,detail).set(t.UPDATED_AT,time(now))
                .where(t.ID.eq(op.id())).and(t.STATUS.eq(PREPARING_STATUS)).and(t.EPOCH.eq(op.epoch())).and(t.LEASE_UNTIL.gt(time(now))).execute()==1;
    }
    public List<Operation> successful(UUID owner, UUID project) {
        var t=SKILL_INSTALL_OPERATION;
        return dsl.selectFrom(t).where(t.OWNER_ID.eq(owner)).and(t.PROJECT_ID.eq(project)).and(t.STATUS.in(SUCCEEDED_STATUS,CLEANING_STATUS))
                .fetch(this::map).stream().filter(Operation::registered).toList();
    }
    private Operation map(Record r) {
        var t=SKILL_INSTALL_OPERATION;
        OffsetDateTime lease=r.get(t.LEASE_UNTIL);
        return new Operation(r.get(t.ID),r.get(t.OWNER_ID),r.get(t.PROJECT_ID),r.get(t.SKILL_ID),r.get(t.SKILL_VERSION_ID),
                r.get(t.COMMAND_KEY),r.get(t.PAYLOAD_HASH).trim(),read(r.get(t.INPUT_JSON)),read(r.get(t.RESULT_JSON)),read(r.get(t.CLEANUP_JSON)),r.get(t.STATUS),r.get(t.EPOCH),
                lease==null?null:lease.toInstant(),r.get(t.ERROR_CODE),r.get(t.ERROR_DETAIL));
    }
    private OffsetDateTime time(Instant value){ return value.atOffset(ZoneOffset.UTC); }
    private JSONB json(JsonNode value){ return JSONB.valueOf(value.toString()); }
    private JsonNode read(JSONB value){ return value==null?null:mapper.readTree(value.data()); }
}

package dev.agenvas.skill.infrastructure;

import static dev.agenvas.db.Tables.AGENT_SKILL_BINDING;
import static dev.agenvas.db.Tables.CREATIVE_SKILL;
import static dev.agenvas.db.Tables.SKILL_DRAFT;
import static dev.agenvas.db.Tables.SKILL_PUBLISH_OPERATION;
import static dev.agenvas.db.Tables.SKILL_VERSION;

import dev.agenvas.skill.domain.SkillContent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Account boundaries and lease fencing are enforced in every SQL mutation. */
@Repository
public class SkillRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public SkillRepository(DSLContext db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }
    public Optional<SkillContent.Catalogue> skill(UUID owner, UUID id, boolean lock) {
        var query = db.selectFrom(CREATIVE_SKILL).where(CREATIVE_SKILL.OWNER_ID.eq(owner).and(CREATIVE_SKILL.ID.eq(id)));
        var row = lock ? query.forUpdate().fetchOne() : query.fetchOne();
        return Optional.ofNullable(row).map(value -> new SkillContent.Catalogue(value.getId(), value.getOwnerId(),
                value.getTitle(), value.getDescription(), value.getCurrentVersionId(), instant(value.getTrashedAt()),
                value.getVersion(), instant(value.getCreatedAt()), instant(value.getUpdatedAt())));
    }
    public void create(SkillContent.Catalogue value, SkillContent.DraftContent draft) {
        db.insertInto(CREATIVE_SKILL).set(CREATIVE_SKILL.ID, value.id()).set(CREATIVE_SKILL.OWNER_ID, value.ownerId())
                .set(CREATIVE_SKILL.TITLE, value.title()).set(CREATIVE_SKILL.DESCRIPTION, value.description())
                .set(CREATIVE_SKILL.CREATED_AT, time(value.createdAt())).set(CREATIVE_SKILL.UPDATED_AT, time(value.updatedAt())).execute();
        db.insertInto(SKILL_DRAFT).set(SKILL_DRAFT.SKILL_ID, value.id()).set(SKILL_DRAFT.OWNER_ID, value.ownerId())
                .set(SKILL_DRAFT.CONTENT_JSON, json(draft)).set(SKILL_DRAFT.UPDATED_AT, time(value.updatedAt())).execute();
    }
    public boolean metadata(UUID owner, UUID id, long expected, String title, String description, Instant trashed, Instant now) {
        return db.update(CREATIVE_SKILL).set(CREATIVE_SKILL.TITLE, title).set(CREATIVE_SKILL.DESCRIPTION, description)
                .set(CREATIVE_SKILL.TRASHED_AT, time(trashed)).set(CREATIVE_SKILL.VERSION, expected + 1)
                .set(CREATIVE_SKILL.UPDATED_AT, time(now)).where(CREATIVE_SKILL.OWNER_ID.eq(owner)
                        .and(CREATIVE_SKILL.ID.eq(id)).and(CREATIVE_SKILL.VERSION.eq(expected))).execute() == 1;
    }
    public record Cursor(Instant updatedAt, UUID id) {}
    private Condition scope(UUID owner, String query, boolean trash) {
        Condition condition = CREATIVE_SKILL.OWNER_ID.eq(owner).and(trash ? CREATIVE_SKILL.TRASHED_AT.isNotNull() : CREATIVE_SKILL.TRASHED_AT.isNull());
        if (query != null && !query.isBlank()) condition = condition.and(CREATIVE_SKILL.TITLE.containsIgnoreCase(query));
        return condition;
    }
    public int count(UUID owner, String query, boolean trash) { return db.fetchCount(db.selectFrom(CREATIVE_SKILL).where(scope(owner, query, trash))); }
    public List<SkillContent.Catalogue> list(UUID owner, String query, boolean trash, Cursor cursor, int limit) {
        Condition condition = scope(owner, query, trash);
        if (cursor != null) condition = condition.and(CREATIVE_SKILL.UPDATED_AT.lt(time(cursor.updatedAt()))
                .or(CREATIVE_SKILL.UPDATED_AT.eq(time(cursor.updatedAt())).and(CREATIVE_SKILL.ID.lt(cursor.id()))));
        return db.selectFrom(CREATIVE_SKILL).where(condition).orderBy(CREATIVE_SKILL.UPDATED_AT.desc(), CREATIVE_SKILL.ID.desc())
                .limit(limit).fetch().map(row -> new SkillContent.Catalogue(row.getId(), row.getOwnerId(), row.getTitle(),
                        row.getDescription(), row.getCurrentVersionId(), instant(row.getTrashedAt()), row.getVersion(),
                        instant(row.getCreatedAt()), instant(row.getUpdatedAt())));
    }
    public Optional<SkillContent.Draft> draft(UUID owner, UUID id, boolean lock) {
        var query = db.selectFrom(SKILL_DRAFT).where(SKILL_DRAFT.OWNER_ID.eq(owner).and(SKILL_DRAFT.SKILL_ID.eq(id)));
        var row = lock ? query.forUpdate().fetchOne() : query.fetchOne();
        return Optional.ofNullable(row).map(value -> new SkillContent.Draft(value.getSkillId(), value.getOwnerId(), value.getVersion(),
                mapper.readValue(value.getContentJson().data(), SkillContent.DraftContent.class), instant(value.getUpdatedAt())));
    }
    public boolean saveDraft(UUID owner, UUID id, long expected, SkillContent.DraftContent content, Instant now) {
        return db.update(SKILL_DRAFT).set(SKILL_DRAFT.CONTENT_JSON, json(content)).set(SKILL_DRAFT.VERSION, expected + 1)
                .set(SKILL_DRAFT.UPDATED_AT, time(now)).where(SKILL_DRAFT.OWNER_ID.eq(owner).and(SKILL_DRAFT.SKILL_ID.eq(id))
                        .and(SKILL_DRAFT.VERSION.eq(expected))).execute() == 1;
    }
    public Optional<SkillContent.Version> version(UUID owner, UUID skillId, UUID versionId) {
        return db.selectFrom(SKILL_VERSION).where(SKILL_VERSION.OWNER_ID.eq(owner).and(SKILL_VERSION.SKILL_ID.eq(skillId))
                .and(SKILL_VERSION.ID.eq(versionId))).fetchOptional().map(row -> new SkillContent.Version(row.getId(), row.getOwnerId(),
                        row.getSkillId(), row.getVersionNumber(), row.getBundleHash(), mapper.readValue(row.getBundleJson().data(), SkillContent.Bundle.class),
                        instant(row.getCreatedAt())));
    }
    public Optional<SkillContent.Version> version(UUID owner, UUID versionId) {
        return db.select(SKILL_VERSION.SKILL_ID).from(SKILL_VERSION).where(SKILL_VERSION.OWNER_ID.eq(owner)
                .and(SKILL_VERSION.ID.eq(versionId))).fetchOptional(SKILL_VERSION.SKILL_ID).flatMap(skill -> version(owner, skill, versionId));
    }
    public List<SkillContent.Version> versions(UUID owner, UUID skillId) {
        return db.select(SKILL_VERSION.ID).from(SKILL_VERSION).where(SKILL_VERSION.OWNER_ID.eq(owner).and(SKILL_VERSION.SKILL_ID.eq(skillId)))
                .orderBy(SKILL_VERSION.VERSION_NUMBER.desc()).fetch(SKILL_VERSION.ID).stream().map(id -> version(owner, skillId, id).orElseThrow()).toList();
    }
    public long nextVersion(UUID owner, UUID skillId) {
        Long last = db.select(org.jooq.impl.DSL.max(SKILL_VERSION.VERSION_NUMBER)).from(SKILL_VERSION)
                .where(SKILL_VERSION.OWNER_ID.eq(owner).and(SKILL_VERSION.SKILL_ID.eq(skillId))).fetchOne(0, Long.class);
        return last == null ? 1 : last + 1;
    }
    public void publish(SkillContent.Version value, long expectedCatalogueVersion) {
        db.insertInto(SKILL_VERSION).set(SKILL_VERSION.ID, value.id()).set(SKILL_VERSION.OWNER_ID, value.ownerId())
                .set(SKILL_VERSION.SKILL_ID, value.skillId()).set(SKILL_VERSION.VERSION_NUMBER, value.versionNumber())
                .set(SKILL_VERSION.BUNDLE_HASH, value.bundleHash()).set(SKILL_VERSION.BUNDLE_JSON, json(value.bundle()))
                .set(SKILL_VERSION.CREATED_AT, time(value.createdAt())).execute();
        if (db.update(CREATIVE_SKILL).set(CREATIVE_SKILL.CURRENT_VERSION_ID, value.id())
                .set(CREATIVE_SKILL.VERSION, expectedCatalogueVersion + 1).set(CREATIVE_SKILL.UPDATED_AT, time(value.createdAt()))
                .where(CREATIVE_SKILL.OWNER_ID.eq(value.ownerId()).and(CREATIVE_SKILL.ID.eq(value.skillId()))
                        .and(CREATIVE_SKILL.VERSION.eq(expectedCatalogueVersion))).execute() != 1)
            throw new IllegalStateException("Skill publication lost catalogue lock");
    }
    public boolean reserve(SkillContent.PublishOperation value) {
        return db.insertInto(SKILL_PUBLISH_OPERATION).set(SKILL_PUBLISH_OPERATION.ID, value.id())
                .set(SKILL_PUBLISH_OPERATION.OWNER_ID, value.ownerId()).set(SKILL_PUBLISH_OPERATION.SKILL_ID, value.skillId())
                .set(SKILL_PUBLISH_OPERATION.COMMAND_KEY, value.commandKey()).set(SKILL_PUBLISH_OPERATION.PAYLOAD_HASH, value.payloadHash())
                .set(SKILL_PUBLISH_OPERATION.INPUT_JSON, json(value.input())).set(SKILL_PUBLISH_OPERATION.PROGRESS_JSON, json(value.progress()))
                .set(SKILL_PUBLISH_OPERATION.STATUS, value.status().name()).set(SKILL_PUBLISH_OPERATION.CREATED_AT, time(value.createdAt()))
                .set(SKILL_PUBLISH_OPERATION.UPDATED_AT, time(value.updatedAt()))
                .onConflict(SKILL_PUBLISH_OPERATION.OWNER_ID, SKILL_PUBLISH_OPERATION.COMMAND_KEY).doNothing().execute() == 1;
    }
    public Optional<SkillContent.PublishOperation> operation(UUID owner, UUID id) {
        return db.selectFrom(SKILL_PUBLISH_OPERATION).where(SKILL_PUBLISH_OPERATION.OWNER_ID.eq(owner)
                .and(SKILL_PUBLISH_OPERATION.ID.eq(id))).fetchOptional().map(this::operation);
    }
    public Optional<SkillContent.PublishOperation> key(UUID owner, String key) {
        return db.selectFrom(SKILL_PUBLISH_OPERATION).where(SKILL_PUBLISH_OPERATION.OWNER_ID.eq(owner)
                .and(SKILL_PUBLISH_OPERATION.COMMAND_KEY.eq(key))).fetchOptional().map(this::operation);
    }
    public Optional<SkillContent.PublishOperation> claim(Instant now, Instant until) {
        var row = db.selectFrom(SKILL_PUBLISH_OPERATION).where(SKILL_PUBLISH_OPERATION.STATUS.eq(SkillContent.OperationStatus.ACCEPTED.name())
                .or(SKILL_PUBLISH_OPERATION.STATUS.eq(SkillContent.OperationStatus.ARCHIVING.name())
                        .and(SKILL_PUBLISH_OPERATION.LEASE_UNTIL.le(time(now)))))
                .orderBy(SKILL_PUBLISH_OPERATION.CREATED_AT, SKILL_PUBLISH_OPERATION.ID).limit(1).forUpdate().skipLocked().fetchOne();
        if (row == null) return Optional.empty();
        if (db.update(SKILL_PUBLISH_OPERATION).set(SKILL_PUBLISH_OPERATION.STATUS, SkillContent.OperationStatus.ARCHIVING.name())
                .set(SKILL_PUBLISH_OPERATION.EPOCH, row.getEpoch() + 1).set(SKILL_PUBLISH_OPERATION.LEASE_UNTIL, time(until))
                .set(SKILL_PUBLISH_OPERATION.UPDATED_AT, time(now)).where(SKILL_PUBLISH_OPERATION.ID.eq(row.getId())
                        .and(SKILL_PUBLISH_OPERATION.EPOCH.eq(row.getEpoch())).and(SKILL_PUBLISH_OPERATION.STATUS.eq(row.getStatus()))).execute() != 1)
            return Optional.empty();
        return operation(row.getOwnerId(), row.getId());
    }
    public boolean progress(SkillContent.PublishOperation value, JsonNode progress, Instant leaseUntil, Instant now) {
        return db.update(SKILL_PUBLISH_OPERATION).set(SKILL_PUBLISH_OPERATION.PROGRESS_JSON, json(progress))
                .set(SKILL_PUBLISH_OPERATION.LEASE_UNTIL, time(leaseUntil)).set(SKILL_PUBLISH_OPERATION.UPDATED_AT, time(now))
                .where(SKILL_PUBLISH_OPERATION.ID.eq(value.id()).and(SKILL_PUBLISH_OPERATION.OWNER_ID.eq(value.ownerId()))
                        .and(SKILL_PUBLISH_OPERATION.STATUS.eq(SkillContent.OperationStatus.ARCHIVING.name()))
                        .and(SKILL_PUBLISH_OPERATION.EPOCH.eq(value.epoch())).and(SKILL_PUBLISH_OPERATION.LEASE_UNTIL.gt(time(now)))).execute() == 1;
    }
    public boolean finish(SkillContent.PublishOperation value, SkillContent.OperationStatus status, UUID versionId,
            String code, String detail, Instant now) {
        return db.update(SKILL_PUBLISH_OPERATION).set(SKILL_PUBLISH_OPERATION.STATUS, status.name())
                .set(SKILL_PUBLISH_OPERATION.LEASE_UNTIL, (OffsetDateTime) null).set(SKILL_PUBLISH_OPERATION.RESULT_VERSION_ID, versionId)
                .set(SKILL_PUBLISH_OPERATION.ERROR_CODE, code).set(SKILL_PUBLISH_OPERATION.ERROR_DETAIL, detail)
                .set(SKILL_PUBLISH_OPERATION.UPDATED_AT, time(now)).where(SKILL_PUBLISH_OPERATION.ID.eq(value.id())
                        .and(SKILL_PUBLISH_OPERATION.OWNER_ID.eq(value.ownerId())).and(SKILL_PUBLISH_OPERATION.EPOCH.eq(value.epoch()))
                        .and(SKILL_PUBLISH_OPERATION.STATUS.eq(SkillContent.OperationStatus.ARCHIVING.name())).and(SKILL_PUBLISH_OPERATION.LEASE_UNTIL.gt(time(now)))).execute() == 1;
    }
    public boolean retry(UUID owner, UUID id, Instant now) {
        return db.update(SKILL_PUBLISH_OPERATION).set(SKILL_PUBLISH_OPERATION.STATUS, SkillContent.OperationStatus.ACCEPTED.name())
                .set(SKILL_PUBLISH_OPERATION.ERROR_CODE, (String) null).set(SKILL_PUBLISH_OPERATION.ERROR_DETAIL, (String) null)
                .set(SKILL_PUBLISH_OPERATION.UPDATED_AT, time(now)).where(SKILL_PUBLISH_OPERATION.OWNER_ID.eq(owner)
                        .and(SKILL_PUBLISH_OPERATION.ID.eq(id)).and(SKILL_PUBLISH_OPERATION.STATUS.eq(SkillContent.OperationStatus.FAILED.name()))).execute() == 1;
    }
    public Optional<SkillContent.PublishOperation> cleanup() {
        return db.selectFrom(SKILL_PUBLISH_OPERATION).where(SKILL_PUBLISH_OPERATION.STATUS.eq(SkillContent.OperationStatus.SUCCEEDED.name())
                .and(SKILL_PUBLISH_OPERATION.PINS_CLEANED.isFalse())).orderBy(SKILL_PUBLISH_OPERATION.UPDATED_AT, SKILL_PUBLISH_OPERATION.ID)
                .limit(1).fetchOptional().map(this::operation);
    }
    public void cleaned(UUID owner, UUID id) {
        db.update(SKILL_PUBLISH_OPERATION).set(SKILL_PUBLISH_OPERATION.PINS_CLEANED, true)
                .where(SKILL_PUBLISH_OPERATION.OWNER_ID.eq(owner).and(SKILL_PUBLISH_OPERATION.ID.eq(id))).execute();
    }
    public Optional<SkillContent.Binding> binding(UUID owner, UUID project, UUID agent) {
        return db.selectFrom(AGENT_SKILL_BINDING).where(AGENT_SKILL_BINDING.OWNER_ID.eq(owner)
                .and(AGENT_SKILL_BINDING.PROJECT_ID.eq(project)).and(AGENT_SKILL_BINDING.AGENT_ID.eq(agent)))
                .fetchOptional().map(row -> new SkillContent.Binding(row.getAgentId(), row.getProjectId(), row.getOwnerId(),
                        row.getSkillId(), row.getSkillVersionId(), instant(row.getUpdatedAt())));
    }
    public void saveBinding(UUID owner, UUID project, UUID agent, UUID skill, UUID version, Instant now) {
        if (skill == null && version == null) {
            db.deleteFrom(AGENT_SKILL_BINDING).where(AGENT_SKILL_BINDING.OWNER_ID.eq(owner)
                    .and(AGENT_SKILL_BINDING.PROJECT_ID.eq(project)).and(AGENT_SKILL_BINDING.AGENT_ID.eq(agent))).execute();
            return;
        }
        db.insertInto(AGENT_SKILL_BINDING).set(AGENT_SKILL_BINDING.AGENT_ID, agent).set(AGENT_SKILL_BINDING.PROJECT_ID, project)
                .set(AGENT_SKILL_BINDING.OWNER_ID, owner).set(AGENT_SKILL_BINDING.SKILL_ID, skill)
                .set(AGENT_SKILL_BINDING.SKILL_VERSION_ID, version).set(AGENT_SKILL_BINDING.UPDATED_AT, time(now))
                .onConflict(AGENT_SKILL_BINDING.AGENT_ID).doUpdate().set(AGENT_SKILL_BINDING.SKILL_ID, skill)
                .set(AGENT_SKILL_BINDING.SKILL_VERSION_ID, version).set(AGENT_SKILL_BINDING.UPDATED_AT, time(now))
                .where(AGENT_SKILL_BINDING.OWNER_ID.eq(owner).and(AGENT_SKILL_BINDING.PROJECT_ID.eq(project))).execute();
    }
    private SkillContent.PublishOperation operation(dev.agenvas.db.tables.records.SkillPublishOperationRecord row) {
        return new SkillContent.PublishOperation(row.getId(), row.getOwnerId(), row.getSkillId(), row.getCommandKey(), row.getPayloadHash(),
                mapper.readValue(row.getInputJson().data(), SkillContent.PublishInput.class), mapper.readTree(row.getProgressJson().data()),
                SkillContent.OperationStatus.valueOf(row.getStatus()), row.getEpoch(), instant(row.getLeaseUntil()), row.getResultVersionId(),
                row.getErrorCode(), row.getErrorDetail(), row.getPinsCleaned(), instant(row.getCreatedAt()), instant(row.getUpdatedAt()));
    }
    private JSONB json(Object value) { return JSONB.valueOf(mapper.writeValueAsString(value)); }
    private OffsetDateTime time(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}

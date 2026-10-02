package dev.agenvas.mediatemplate.infrastructure;

import static dev.agenvas.db.Tables.MEDIA_TEMPLATE;
import static dev.agenvas.db.Tables.MEDIA_TEMPLATE_ATTACHMENT;
import static dev.agenvas.db.Tables.MEDIA_TEMPLATE_IMAGE;
import static dev.agenvas.db.Tables.MEDIA_TEMPLATE_IMPORT_COMMAND;
import static dev.agenvas.db.Tables.MEDIA_TEMPLATE_IMPORT_IMAGE;
import static dev.agenvas.db.Tables.MEDIA_TEMPLATE_IMPORT_SOURCE;

import dev.agenvas.asset.application.PrivateMediaArchive.Media;
import dev.agenvas.mediatemplate.domain.MediaTemplate;
import dev.agenvas.mediatemplate.domain.MediaTemplate.Scope;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Template SQL belongs to this module; project content is created through application services. */
@Repository
public class MediaTemplateRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;
    public MediaTemplateRepository(DSLContext db, ObjectMapper mapper) { this.db = db; this.mapper = mapper; }

    public List<MediaTemplate> list(UUID owner, TargetKind kind, Scope scope, String query) {
        Condition visible = MEDIA_TEMPLATE.SCOPE.eq(Scope.SYSTEM.name())
                .or(MEDIA_TEMPLATE.SCOPE.eq(Scope.PERSONAL.name()).and(MEDIA_TEMPLATE.OWNER_ID.eq(owner)));
        if (kind != null) visible = visible.and(MEDIA_TEMPLATE.TARGET_KIND.eq(kind.name()));
        if (scope != null) visible = visible.and(MEDIA_TEMPLATE.SCOPE.eq(scope.name()));
        if (!query.isBlank()) visible = visible.and(MEDIA_TEMPLATE.NAME.containsIgnoreCase(query)
                .or(MEDIA_TEMPLATE.PROMPT.containsIgnoreCase(query)));
        return db.selectFrom(MEDIA_TEMPLATE).where(visible)
                .orderBy(MEDIA_TEMPLATE.UPDATED_AT.desc(), MEDIA_TEMPLATE.ID.asc()).fetch().map(this::template);
    }
    public Optional<MediaTemplate> find(UUID id, boolean lock) {
        var query = db.selectFrom(MEDIA_TEMPLATE).where(MEDIA_TEMPLATE.ID.eq(id));
        return (lock ? query.forUpdate().fetchOptional() : query.fetchOptional()).map(this::template);
    }
    public void insert(MediaTemplate value) {
        db.insertInto(MEDIA_TEMPLATE).set(MEDIA_TEMPLATE.ID, value.id()).set(MEDIA_TEMPLATE.OWNER_ID, value.ownerId())
                .set(MEDIA_TEMPLATE.SCOPE, value.scope().name()).set(MEDIA_TEMPLATE.TARGET_KIND, value.targetKind().name())
                .set(MEDIA_TEMPLATE.NAME, value.name()).set(MEDIA_TEMPLATE.PROMPT, value.prompt())
                .set(MEDIA_TEMPLATE.VERSION, value.version()).set(MEDIA_TEMPLATE.CREATED_AT, time(value.createdAt()))
                .set(MEDIA_TEMPLATE.UPDATED_AT, time(value.updatedAt())).execute();
        attachments(value.id(), value.imageIds());
    }
    public boolean update(MediaTemplate value, long expected) {
        boolean changed = db.update(MEDIA_TEMPLATE).set(MEDIA_TEMPLATE.NAME, value.name())
                .set(MEDIA_TEMPLATE.TARGET_KIND, value.targetKind().name()).set(MEDIA_TEMPLATE.PROMPT, value.prompt())
                .set(MEDIA_TEMPLATE.VERSION, value.version()).set(MEDIA_TEMPLATE.UPDATED_AT, time(value.updatedAt()))
                .where(MEDIA_TEMPLATE.ID.eq(value.id()).and(MEDIA_TEMPLATE.VERSION.eq(expected))).execute() == 1;
        if (changed) attachments(value.id(), value.imageIds());
        return changed;
    }
    private void attachments(UUID template, List<UUID> images) {
        db.deleteFrom(MEDIA_TEMPLATE_ATTACHMENT).where(MEDIA_TEMPLATE_ATTACHMENT.TEMPLATE_ID.eq(template)).execute();
        for (int i = 0; i < images.size(); i++) db.insertInto(MEDIA_TEMPLATE_ATTACHMENT)
                .set(MEDIA_TEMPLATE_ATTACHMENT.TEMPLATE_ID, template).set(MEDIA_TEMPLATE_ATTACHMENT.IMAGE_ID, images.get(i))
                .set(MEDIA_TEMPLATE_ATTACHMENT.POSITION, i).execute();
    }
    public boolean delete(UUID id, long expected) {
        return db.deleteFrom(MEDIA_TEMPLATE).where(MEDIA_TEMPLATE.ID.eq(id)
                .and(MEDIA_TEMPLATE.VERSION.eq(expected))).execute() == 1;
    }
    public void insertImage(Media image, Instant now) {
        db.insertInto(MEDIA_TEMPLATE_IMAGE).set(MEDIA_TEMPLATE_IMAGE.ID, image.id())
                .set(MEDIA_TEMPLATE_IMAGE.OWNER_ID, image.ownerId())
                .set(MEDIA_TEMPLATE_IMAGE.METADATA_JSON, JSONB.valueOf(mapper.writeValueAsString(image)))
                .set(MEDIA_TEMPLATE_IMAGE.CREATED_AT, time(now)).execute();
    }
    public Optional<Media> image(UUID id, boolean lock) {
        var query = db.selectFrom(MEDIA_TEMPLATE_IMAGE).where(MEDIA_TEMPLATE_IMAGE.ID.eq(id));
        return (lock ? query.forUpdate().fetchOptional() : query.fetchOptional())
                .map(row -> mapper.readValue(row.getMetadataJson().data(), Media.class));
    }
    public boolean sharedImage(UUID id) {
        return db.fetchExists(db.selectOne().from(MEDIA_TEMPLATE_ATTACHMENT).join(MEDIA_TEMPLATE)
                .on(MEDIA_TEMPLATE_ATTACHMENT.TEMPLATE_ID.eq(MEDIA_TEMPLATE.ID))
                .where(MEDIA_TEMPLATE_ATTACHMENT.IMAGE_ID.eq(id).and(MEDIA_TEMPLATE.SCOPE.eq(Scope.SYSTEM.name()))));
    }
    public boolean imageInUse(UUID id) {
        return db.fetchExists(db.selectOne().from(MEDIA_TEMPLATE_ATTACHMENT).where(MEDIA_TEMPLATE_ATTACHMENT.IMAGE_ID.eq(id)))
                || db.fetchExists(db.selectOne().from(MEDIA_TEMPLATE_IMPORT_SOURCE).where(MEDIA_TEMPLATE_IMPORT_SOURCE.IMAGE_ID.eq(id)));
    }
    public void deleteImage(UUID id) { db.deleteFrom(MEDIA_TEMPLATE_IMAGE).where(MEDIA_TEMPLATE_IMAGE.ID.eq(id)).execute(); }

    public record ImportCommand(UUID id, UUID ownerId, UUID projectId, String payloadHash, JsonNode input, JsonNode result) {}
    public Optional<ImportCommand> command(UUID owner, UUID project, String key, boolean lock) {
        var query = db.selectFrom(MEDIA_TEMPLATE_IMPORT_COMMAND).where(MEDIA_TEMPLATE_IMPORT_COMMAND.OWNER_ID.eq(owner)
                .and(MEDIA_TEMPLATE_IMPORT_COMMAND.PROJECT_ID.eq(project)).and(MEDIA_TEMPLATE_IMPORT_COMMAND.COMMAND_KEY.eq(key)));
        return (lock ? query.forUpdate().fetchOptional() : query.fetchOptional()).map(this::command);
    }
    public void insertCommand(ImportCommand value, String key, List<UUID> images, Instant now) {
        db.insertInto(MEDIA_TEMPLATE_IMPORT_COMMAND).set(MEDIA_TEMPLATE_IMPORT_COMMAND.ID, value.id())
                .set(MEDIA_TEMPLATE_IMPORT_COMMAND.OWNER_ID, value.ownerId()).set(MEDIA_TEMPLATE_IMPORT_COMMAND.PROJECT_ID, value.projectId())
                .set(MEDIA_TEMPLATE_IMPORT_COMMAND.COMMAND_KEY, key).set(MEDIA_TEMPLATE_IMPORT_COMMAND.PAYLOAD_HASH, value.payloadHash())
                .set(MEDIA_TEMPLATE_IMPORT_COMMAND.INPUT_JSON, JSONB.valueOf(mapper.writeValueAsString(value.input())))
                .set(MEDIA_TEMPLATE_IMPORT_COMMAND.CREATED_AT, time(now)).execute();
        for (UUID image : images) db.insertInto(MEDIA_TEMPLATE_IMPORT_SOURCE)
                .set(MEDIA_TEMPLATE_IMPORT_SOURCE.COMMAND_ID, value.id()).set(MEDIA_TEMPLATE_IMPORT_SOURCE.IMAGE_ID, image).execute();
    }
    public void complete(UUID command, JsonNode result) {
        if (db.update(MEDIA_TEMPLATE_IMPORT_COMMAND).set(MEDIA_TEMPLATE_IMPORT_COMMAND.RESULT_JSON, JSONB.valueOf(mapper.writeValueAsString(result)))
                .where(MEDIA_TEMPLATE_IMPORT_COMMAND.ID.eq(command).and(MEDIA_TEMPLATE_IMPORT_COMMAND.RESULT_JSON.isNull())).execute() != 1)
            throw new IllegalStateException("Locked template import changed unexpectedly");
    }
    public void provenance(UUID project, UUID version, MediaTemplate template) {
        db.insertInto(MEDIA_TEMPLATE_IMPORT_IMAGE).set(MEDIA_TEMPLATE_IMPORT_IMAGE.PROJECT_ID, project)
                .set(MEDIA_TEMPLATE_IMPORT_IMAGE.VERSION_ID, version).set(MEDIA_TEMPLATE_IMPORT_IMAGE.TEMPLATE_ID, template.id())
                .set(MEDIA_TEMPLATE_IMPORT_IMAGE.TEMPLATE_VERSION, template.version()).set(MEDIA_TEMPLATE_IMPORT_IMAGE.TEMPLATE_NAME, template.name()).execute();
    }
    private ImportCommand command(Record row) {
        JSONB result = row.get(MEDIA_TEMPLATE_IMPORT_COMMAND.RESULT_JSON);
        return new ImportCommand(row.get(MEDIA_TEMPLATE_IMPORT_COMMAND.ID), row.get(MEDIA_TEMPLATE_IMPORT_COMMAND.OWNER_ID),
                row.get(MEDIA_TEMPLATE_IMPORT_COMMAND.PROJECT_ID), row.get(MEDIA_TEMPLATE_IMPORT_COMMAND.PAYLOAD_HASH),
                mapper.readTree(row.get(MEDIA_TEMPLATE_IMPORT_COMMAND.INPUT_JSON).data()), result == null ? null : mapper.readTree(result.data()));
    }
    private MediaTemplate template(Record row) {
        UUID id = row.get(MEDIA_TEMPLATE.ID);
        var images = db.select(MEDIA_TEMPLATE_ATTACHMENT.IMAGE_ID).from(MEDIA_TEMPLATE_ATTACHMENT)
                .where(MEDIA_TEMPLATE_ATTACHMENT.TEMPLATE_ID.eq(id)).orderBy(MEDIA_TEMPLATE_ATTACHMENT.POSITION.asc())
                .fetch(MEDIA_TEMPLATE_ATTACHMENT.IMAGE_ID);
        return new MediaTemplate(id, row.get(MEDIA_TEMPLATE.OWNER_ID), Scope.valueOf(row.get(MEDIA_TEMPLATE.SCOPE)),
                TargetKind.valueOf(row.get(MEDIA_TEMPLATE.TARGET_KIND)), row.get(MEDIA_TEMPLATE.NAME), row.get(MEDIA_TEMPLATE.PROMPT), images,
                row.get(MEDIA_TEMPLATE.VERSION), row.get(MEDIA_TEMPLATE.CREATED_AT).toInstant(), row.get(MEDIA_TEMPLATE.UPDATED_AT).toInstant());
    }
    private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}

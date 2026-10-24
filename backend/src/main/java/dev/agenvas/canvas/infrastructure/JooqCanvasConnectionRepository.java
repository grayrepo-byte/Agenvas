package dev.agenvas.canvas.infrastructure;

import static dev.agenvas.db.Tables.CANVAS_CONNECTION;
import static dev.agenvas.db.Tables.CANVAS_ITEM;
import static dev.agenvas.db.Tables.PROJECT;

import dev.agenvas.canvas.application.CanvasConnectionRepository;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.db.tables.records.CanvasConnectionRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class JooqCanvasConnectionRepository implements CanvasConnectionRepository {
    private final DSLContext dsl;

    public JooqCanvasConnectionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void create(CanvasConnection connection) {
        dsl.insertInto(CANVAS_CONNECTION)
                .set(CANVAS_CONNECTION.ID, connection.id())
                .set(CANVAS_CONNECTION.PROJECT_ID, connection.projectId())
                .set(CANVAS_CONNECTION.SOURCE_CANVAS_ITEM_ID,
                        connection.sourceCanvasItemId())
                .set(CANVAS_CONNECTION.TARGET_CANVAS_ITEM_ID,
                        connection.targetCanvasItemId())
                .set(CANVAS_CONNECTION.RELATION_TYPE, connection.relationType().name())
                .set(CANVAS_CONNECTION.SOURCE_ARTIFACT_VERSION_ID,
                        connection.sourceArtifactVersionId())
                .set(CANVAS_CONNECTION.VERSION, connection.version())
                .set(CANVAS_CONNECTION.CREATED_AT, utc(connection.createdAt()))
                .set(CANVAS_CONNECTION.UPDATED_AT, utc(connection.updatedAt()))
                .execute();
    }

    @Override
    public Optional<CanvasConnection> find(UUID ownerId, UUID projectId, UUID connectionId) {
        return dsl.select(CANVAS_CONNECTION.fields())
                .from(CANVAS_CONNECTION)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_CONNECTION.PROJECT_ID))
                .where(CANVAS_CONNECTION.ID.eq(connectionId))
                .and(CANVAS_CONNECTION.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .fetchOptional(row -> map(row.into(CANVAS_CONNECTION)));
    }

    @Override
    public List<CanvasConnection> list(UUID ownerId, UUID projectId) {
        return dsl.select(CANVAS_CONNECTION.fields())
                .from(CANVAS_CONNECTION)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_CONNECTION.PROJECT_ID))
                .where(CANVAS_CONNECTION.PROJECT_ID.eq(projectId))
                .and(PROJECT.OWNER_ID.eq(ownerId))
                .orderBy(CANVAS_CONNECTION.CREATED_AT, CANVAS_CONNECTION.ID)
                .fetch(row -> map(row.into(CANVAS_CONNECTION)));
    }

    @Override
    public TopologyGuard copyGuard(UUID ownerId, UUID projectId, UUID canvasItemId) {
        long epoch = dsl.select(CANVAS_ITEM.TOPOLOGY_EPOCH).from(CANVAS_ITEM)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_ITEM.PROJECT_ID))
                .where(PROJECT.OWNER_ID.eq(ownerId)).and(CANVAS_ITEM.PROJECT_ID.eq(projectId))
                .and(CANVAS_ITEM.ID.eq(canvasItemId)).fetchOptional(CANVAS_ITEM.TOPOLOGY_EPOCH)
                .orElseThrow(() -> new IllegalStateException("Owned canvas copy target disappeared"));
        List<CanvasConnection> edges = dsl.select(CANVAS_CONNECTION.fields()).from(CANVAS_CONNECTION)
                .join(PROJECT).on(PROJECT.ID.eq(CANVAS_CONNECTION.PROJECT_ID))
                .where(PROJECT.OWNER_ID.eq(ownerId)).and(CANVAS_CONNECTION.PROJECT_ID.eq(projectId))
                .and(CANVAS_CONNECTION.SOURCE_CANVAS_ITEM_ID.eq(canvasItemId)
                        .or(CANVAS_CONNECTION.TARGET_CANVAS_ITEM_ID.eq(canvasItemId)))
                .orderBy(CANVAS_CONNECTION.ID).fetch(row -> map(row.into(CANVAS_CONNECTION)));
        return new TopologyGuard(epoch, edges);
    }

    @Override
    public boolean delete(UUID projectId, UUID connectionId) {
        return dsl.deleteFrom(CANVAS_CONNECTION)
                .where(CANVAS_CONNECTION.PROJECT_ID.eq(projectId))
                .and(CANVAS_CONNECTION.ID.eq(connectionId))
                .execute() == 1;
    }

    private CanvasConnection map(CanvasConnectionRecord row) {
        return new CanvasConnection(row.getId(), row.getProjectId(),
                row.getSourceCanvasItemId(), row.getTargetCanvasItemId(),
                CanvasConnection.RelationType.valueOf(row.getRelationType()),
                row.getSourceArtifactVersionId(), row.getVersion(),
                row.getCreatedAt().toInstant(), row.getUpdatedAt().toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

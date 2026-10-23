package dev.agenvas.storyboard.infrastructure;

import static dev.agenvas.db.Tables.STORYBOARD;
import static dev.agenvas.db.Tables.STORYBOARD_REVISION;

import dev.agenvas.storyboard.application.StoryboardRepository;
import dev.agenvas.storyboard.domain.Storyboard;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Stores complete immutable snapshots; only the small current pointer is mutable. */
@Repository
public class JooqStoryboardRepository implements StoryboardRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JooqStoryboardRepository(DSLContext dsl, ObjectMapper mapper, Clock clock) {
        this.dsl = dsl; this.mapper = mapper; this.clock = clock;
    }

    @Override
    public Optional<Storyboard> current(UUID projectId) {
        return dsl.select(STORYBOARD_REVISION.CONTENT_JSON).from(STORYBOARD)
                .join(STORYBOARD_REVISION).on(STORYBOARD_REVISION.PROJECT_ID.eq(STORYBOARD.PROJECT_ID)
                        .and(STORYBOARD_REVISION.VERSION.eq(STORYBOARD.CURRENT_VERSION)))
                .where(STORYBOARD.PROJECT_ID.eq(projectId))
                .fetchOptional(row -> parse(row.value1()));
    }

    @Override
    public boolean append(Storyboard board, long expectedVersion) {
        int changed = expectedVersion == 0
                ? dsl.insertInto(STORYBOARD).set(STORYBOARD.PROJECT_ID, board.projectId())
                        .set(STORYBOARD.CURRENT_VERSION, board.version())
                        .onConflict(STORYBOARD.PROJECT_ID).doNothing().execute()
                : dsl.update(STORYBOARD).set(STORYBOARD.CURRENT_VERSION, board.version())
                        .where(STORYBOARD.PROJECT_ID.eq(board.projectId()))
                        .and(STORYBOARD.CURRENT_VERSION.eq(expectedVersion)).execute();
        if (changed != 1) return false;
        var json = mapper.valueToTree(board);
        ((tools.jackson.databind.node.ObjectNode) json).put("schemaVersion", 1);
        dsl.insertInto(STORYBOARD_REVISION).set(STORYBOARD_REVISION.PROJECT_ID, board.projectId())
                .set(STORYBOARD_REVISION.VERSION, board.version())
                .set(STORYBOARD_REVISION.CONTENT_JSON, JSONB.valueOf(json.toString()))
                .set(STORYBOARD_REVISION.CREATED_AT, clock.instant().atOffset(ZoneOffset.UTC)).execute();
        return true;
    }

    @Override
    public List<Storyboard> revisions(UUID projectId, long afterVersion, int limit) {
        return dsl.select(STORYBOARD_REVISION.CONTENT_JSON).from(STORYBOARD_REVISION)
                .where(STORYBOARD_REVISION.PROJECT_ID.eq(projectId))
                .and(STORYBOARD_REVISION.VERSION.gt(afterVersion))
                .orderBy(STORYBOARD_REVISION.VERSION).limit(limit).fetch(row -> parse(row.value1()));
    }

    @Override
    public java.util.Map<UUID, Integer> maxShotRevisions(UUID projectId, java.util.Set<UUID> shotIds) {
        if (shotIds.isEmpty()) return java.util.Map.of();
        var id = org.jooq.impl.DSL.field("shot.value ->> 'id'", String.class);
        var revision = org.jooq.impl.DSL.max(org.jooq.impl.DSL.field("(shot.value ->> 'revision')::integer", Integer.class));
        return dsl.select(id, revision).from(STORYBOARD_REVISION)
                .crossJoin(org.jooq.impl.DSL.table("jsonb_array_elements({0} -> 'shots') as shot(value)", STORYBOARD_REVISION.CONTENT_JSON))
                .where(STORYBOARD_REVISION.PROJECT_ID.eq(projectId))
                .and(id.in(shotIds.stream().map(UUID::toString).toList())).groupBy(id)
                .fetchMap(row -> UUID.fromString(row.value1()), row -> row.value2());
    }

    private Storyboard parse(JSONB json) {
        var tree = (tools.jackson.databind.node.ObjectNode) mapper.readTree(json.data());
        if (tree.path("schemaVersion").asInt() != 1) throw new IllegalStateException("Unsupported storyboard content schema");
        tree.remove("schemaVersion");
        return mapper.treeToValue(tree, Storyboard.class);
    }

    @Override
    public boolean hasCanvasReference(UUID projectId, UUID canvasItemId) {
        return dsl.fetchExists(dsl.selectOne().from(STORYBOARD_REVISION)
                .where(STORYBOARD_REVISION.PROJECT_ID.eq(projectId))
                .and(org.jooq.impl.DSL.condition(
                        "jsonb_path_exists({0}, '$.**.canvasItemId ? (@ == $item)', jsonb_build_object('item', {1}))",
                        STORYBOARD_REVISION.CONTENT_JSON, org.jooq.impl.DSL.val(canvasItemId.toString()))));
    }
}

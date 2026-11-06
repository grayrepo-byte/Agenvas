package dev.agenvas.canvas.infrastructure;

import static dev.agenvas.canvas.application.CanvasCharacterNodeModels.*;
import static dev.agenvas.db.Tables.CANVAS_CHARACTER_COMMAND;
import static dev.agenvas.db.Tables.CANVAS_CHARACTER_CONNECTION;
import static dev.agenvas.db.Tables.CANVAS_CHARACTER_NODE;
import static dev.agenvas.db.Tables.CANVAS_ITEM;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Project authorization and event-row locking are established by the application service. */
@Repository
public class CanvasCharacterNodeRepository {
    private final DSLContext db;
    private final ObjectMapper mapper;

    public CanvasCharacterNodeRepository(DSLContext db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public record NodeRow(UUID canvasItemId, Selection selection, long version) {}

    public record Receipt(String requestHash, JsonNode result) {}

    public Optional<NodeRow> find(UUID project, UUID item) {
        return db.selectFrom(CANVAS_CHARACTER_NODE)
                .where(
                        CANVAS_CHARACTER_NODE
                                .PROJECT_ID
                                .eq(project)
                                .and(CANVAS_CHARACTER_NODE.CANVAS_ITEM_ID.eq(item)))
                .fetchOptional()
                .map(
                        row ->
                                new NodeRow(
                                        item,
                                        mapper.treeToValue(
                                                body(row.getSelectionJson()), Selection.class),
                                        row.getVersion()));
    }

    public void insert(UUID project, UUID item, Selection selection, long version) {
        if (db.insertInto(CANVAS_CHARACTER_NODE)
                        .set(CANVAS_CHARACTER_NODE.PROJECT_ID, project)
                        .set(CANVAS_CHARACTER_NODE.CANVAS_ITEM_ID, item)
                        .set(CANVAS_CHARACTER_NODE.CHARACTER_ID, selection.characterId())
                        .set(CANVAS_CHARACTER_NODE.CHARACTER_VERSION, selection.characterVersion())
                        .set(CANVAS_CHARACTER_NODE.SELECTION_JSON, json(selection))
                        .set(CANVAS_CHARACTER_NODE.VERSION, version)
                        .execute()
                != 1) throw new IllegalStateException("Character node insert failed");
    }

    public boolean update(UUID project, UUID item, Selection selection, long expected) {
        return db.update(CANVAS_CHARACTER_NODE)
                        .set(CANVAS_CHARACTER_NODE.CHARACTER_VERSION, selection.characterVersion())
                        .set(CANVAS_CHARACTER_NODE.SELECTION_JSON, json(selection))
                        .set(CANVAS_CHARACTER_NODE.VERSION, expected + 1)
                        .where(
                                CANVAS_CHARACTER_NODE
                                        .PROJECT_ID
                                        .eq(project)
                                        .and(CANVAS_CHARACTER_NODE.CANVAS_ITEM_ID.eq(item))
                                        .and(
                                                CANVAS_CHARACTER_NODE.CHARACTER_ID.eq(
                                                        selection.characterId()))
                                        .and(CANVAS_CHARACTER_NODE.VERSION.eq(expected)))
                        .execute()
                == 1;
    }

    public List<CharacterConnection> connections(UUID project) {
        return db.selectFrom(CANVAS_CHARACTER_CONNECTION)
                .where(CANVAS_CHARACTER_CONNECTION.PROJECT_ID.eq(project))
                .orderBy(CANVAS_CHARACTER_CONNECTION.ID)
                .fetch()
                .map(
                        row ->
                                new CharacterConnection(
                                        row.getId(),
                                        row.getSourceCanvasItemId(),
                                        row.getTargetCanvasItemId(),
                                        row.getVersion()));
    }

    public boolean connect(UUID project, CharacterConnection edge) {
        return db.insertInto(CANVAS_CHARACTER_CONNECTION)
                        .set(CANVAS_CHARACTER_CONNECTION.ID, edge.id())
                        .set(CANVAS_CHARACTER_CONNECTION.PROJECT_ID, project)
                        .set(
                                CANVAS_CHARACTER_CONNECTION.SOURCE_CANVAS_ITEM_ID,
                                edge.sourceCanvasItemId())
                        .set(
                                CANVAS_CHARACTER_CONNECTION.TARGET_CANVAS_ITEM_ID,
                                edge.targetCanvasItemId())
                        .set(CANVAS_CHARACTER_CONNECTION.VERSION, edge.version())
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }

    public boolean disconnect(UUID project, UUID id, long expected) {
        return db.deleteFrom(CANVAS_CHARACTER_CONNECTION)
                        .where(
                                CANVAS_CHARACTER_CONNECTION
                                        .PROJECT_ID
                                        .eq(project)
                                        .and(CANVAS_CHARACTER_CONNECTION.ID.eq(id))
                                        .and(CANVAS_CHARACTER_CONNECTION.VERSION.eq(expected)))
                        .execute()
                == 1;
    }

    public long topologyEpoch(UUID project, UUID item) {
        return db.select(CANVAS_ITEM.TOPOLOGY_EPOCH)
                .from(CANVAS_ITEM)
                .where(CANVAS_ITEM.PROJECT_ID.eq(project).and(CANVAS_ITEM.ID.eq(item)))
                .fetchSingle(CANVAS_ITEM.TOPOLOGY_EPOCH);
    }

    public Optional<Receipt> receipt(UUID project, UUID command) {
        return db.selectFrom(CANVAS_CHARACTER_COMMAND)
                .where(
                        CANVAS_CHARACTER_COMMAND
                                .PROJECT_ID
                                .eq(project)
                                .and(CANVAS_CHARACTER_COMMAND.COMMAND_ID.eq(command)))
                .fetchOptional()
                .map(row -> new Receipt(row.getRequestHash(), body(row.getResultJson())));
    }

    public void receipt(UUID project, UUID command, String hash, Object result) {
        if (db.insertInto(CANVAS_CHARACTER_COMMAND)
                        .set(CANVAS_CHARACTER_COMMAND.PROJECT_ID, project)
                        .set(CANVAS_CHARACTER_COMMAND.COMMAND_ID, command)
                        .set(CANVAS_CHARACTER_COMMAND.REQUEST_HASH, hash)
                        .set(CANVAS_CHARACTER_COMMAND.RESULT_JSON, json(result))
                        .execute()
                != 1) throw new IllegalStateException("Character node receipt insert failed");
    }

    private JSONB json(Object value) {
        var doc = mapper.createObjectNode().put("schemaVersion", 1);
        doc.set("content", mapper.valueToTree(value));
        return JSONB.valueOf(mapper.writeValueAsString(doc));
    }

    private JsonNode body(JSONB value) {
        JsonNode doc = mapper.readTree(value.data());
        if (doc.path("schemaVersion").asInt() != 1 || !doc.has("content"))
            throw new IllegalStateException("Unsupported character node document");
        return doc.path("content");
    }
}

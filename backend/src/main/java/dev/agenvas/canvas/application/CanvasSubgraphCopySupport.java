package dev.agenvas.canvas.application;

import static dev.agenvas.canvas.application.CanvasEditModels.*;
import static dev.agenvas.canvas.application.CanvasCharacterNodeModels.CharacterConnection;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.canvas.infrastructure.CanvasCharacterNodeRepository;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Copies only the internal topology of a selection inside the canvas edit transaction.
 * Draft inputs are installed separately with these exact edge identities, never by reconnecting
 * through today's model defaults or re-resolving an immutable media version.
 */
@Service
public class CanvasSubgraphCopySupport {
    private final CanvasConnectionRepository connections;
    private final CanvasCharacterNodeRepository characters;
    private final ProjectService projects;
    private final ArtifactService artifacts;
    private final Clock clock;

    public CanvasSubgraphCopySupport(CanvasConnectionRepository connections, CanvasCharacterNodeRepository characters,
            ProjectService projects, ArtifactService artifacts, Clock clock) {
        this.connections = connections; this.characters = characters; this.projects = projects;
        this.artifacts = artifacts; this.clock = clock;
    }

    public record Graph(List<CanvasConnection> connections, List<CharacterConnection> characterConnections) {
        public static Graph empty() { return new Graph(List.of(), List.of()); }
    }
    public record Prepared(Graph source, Map<UUID, UUID> edgeIds) {}

    @Transactional(readOnly = true)
    public Graph current(UUID owner, UUID project) {
        projects.get(owner, project);
        return new Graph(connections.list(owner, project), characters.connections(project));
    }

    /** Complete edge identities detect stale clipboard topology, including disconnect/reconnect. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Prepared prepare(UUID owner, UUID project, List<Copy> copies, List<ConnectionCopy> requests) {
        Graph all = current(owner, project);
        Set<UUID> sources = new HashSet<>();
        copies.forEach(copy -> sources.add(copy.sourceItemId()));
        Graph internal = new Graph(all.connections().stream().filter(e -> sources.contains(e.sourceCanvasItemId())
                && sources.contains(e.targetCanvasItemId())).toList(),
                all.characterConnections().stream().filter(e -> sources.contains(e.sourceCanvasItemId())
                && sources.contains(e.targetCanvasItemId())).toList());
        Map<UUID, ConnectionCopy> bySource = new HashMap<>();
        Set<UUID> used = new HashSet<>();
        all.connections().forEach(e -> used.add(e.id()));
        all.characterConnections().forEach(e -> used.add(e.id()));
        Map<UUID, UUID> ids = new HashMap<>();
        for (ConnectionCopy request : requests) {
            if (!used.add(request.targetConnectionId()) || bySource.put(request.sourceConnectionId(), request) != null) throw conflict();
            ids.put(request.sourceConnectionId(), request.targetConnectionId());
        }
        if (requests.size() != internal.connections().size() + internal.characterConnections().size()) throw conflict();
        for (var edge : internal.connections()) check(bySource.get(edge.id()), ConnectionKind.ARTIFACT, edge.version());
        for (var edge : internal.characterConnections()) check(bySource.get(edge.id()), ConnectionKind.CHARACTER, edge.version());
        return new Prepared(internal, Map.copyOf(ids));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Graph remap(UUID owner, UUID project, Prepared prepared, Map<UUID, CanvasItem> targets) {
        List<CanvasConnection> edges = new ArrayList<>();
        var now = clock.instant();
        // Preserve source prompt order even when original timestamps tie: UUIDs change on copy.
        int index = 0;
        for (var edge : prepared.source().connections()) {
            CanvasItem source = targets.get(edge.sourceCanvasItemId()), target = targets.get(edge.targetCanvasItemId());
            UUID version = edge.relationType() == CanvasConnection.RelationType.PROMPT_INPUT
                    ? artifacts.get(owner, project, source.subjectId()).resourceDefaultVersion().id() : edge.sourceArtifactVersionId();
            artifacts.requireVersion(owner, project, source.subjectId(), version);
            var created = now.plusNanos(1000L * index++);
            edges.add(new CanvasConnection(prepared.edgeIds().get(edge.id()), project, source.id(), target.id(),
                    edge.relationType(), version, 0, created, created));
        }
        List<CharacterConnection> logical = prepared.source().characterConnections().stream().map(edge ->
                new CharacterConnection(prepared.edgeIds().get(edge.id()), targets.get(edge.sourceCanvasItemId()).id(),
                        targets.get(edge.targetCanvasItemId()).id(), 0)).toList();
        return new Graph(List.copyOf(edges), logical);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void install(UUID owner, UUID project, Graph graph) {
        projects.requireActiveProject(owner, project);
        try {
            for (var edge : graph.connections()) connections.create(edge);
            for (var edge : graph.characterConnections()) if (!characters.connect(project, edge)) throw conflict();
        } catch (DuplicateKeyException collision) { throw conflict(); }
    }

    /** Call only after every copied node's full guard has matched. Never clean up foreign edges. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void remove(UUID project, Graph graph) {
        for (var edge : graph.connections()) if (!connections.delete(project, edge.id())) throw conflict();
        for (var edge : graph.characterConnections()) if (!characters.disconnect(project, edge.id(), edge.version())) throw conflict();
    }

    public Graph nextIncarnation(Graph graph) {
        return new Graph(graph.connections().stream().map(e -> new CanvasConnection(e.id(), e.projectId(),
                e.sourceCanvasItemId(), e.targetCanvasItemId(), e.relationType(), e.sourceArtifactVersionId(),
                e.version() + 1, e.createdAt(), clock.instant())).toList(),
                graph.characterConnections().stream().map(e -> new CharacterConnection(e.id(), e.sourceCanvasItemId(),
                        e.targetCanvasItemId(), e.version() + 1)).toList());
    }

    private void check(ConnectionCopy value, ConnectionKind kind, long version) {
        if (value == null || value.kind() != kind || value.expectedVersion() != version) throw conflict();
    }
    private ApiProblemException conflict() {
        return new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_EDIT_CONFLICT",
                ApiMessage.of("api.canvas-service.canvas-card-updated"), ApiMessage.of("api.canvas-edit.conflict"), false);
    }
}

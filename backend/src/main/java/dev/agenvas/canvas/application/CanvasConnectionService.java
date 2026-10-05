package dev.agenvas.canvas.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Creates server-first CanvasItem topology and its card-local input source atomically. */
@Service
public class CanvasConnectionService {
    private final ProjectService projects;
    private final CanvasItemQueryService canvasItems;
    private final ArtifactService artifacts;
    private final AgentInstanceService agents;
    private final MediaDraftService drafts;
    private final CanvasConnectionRepository connections;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CanvasConnectionService(ProjectService projects, CanvasItemQueryService canvasItems,
            ArtifactService artifacts, AgentInstanceService agents, MediaDraftService drafts,
            CanvasConnectionRepository connections, ProjectEventService events,
            ObjectMapper mapper, Clock clock) {
        this.projects = projects;
        this.canvasItems = canvasItems;
        this.artifacts = artifacts;
        this.agents = agents;
        this.drafts = drafts;
        this.connections = connections;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ConnectionResult connect(UUID ownerId, UUID projectId, UUID sourceItemId,
            UUID targetItemId, UUID sourceVersionId,
            CanvasConnection.RelationType relationType, long expectedTargetDraftVersion) {
        return connect(ownerId, projectId, sourceItemId, targetItemId, sourceVersionId,
                relationType, expectedTargetDraftVersion, null);
    }

    @Transactional
    public ConnectionResult connect(UUID ownerId, UUID projectId, UUID sourceItemId,
            UUID targetItemId, UUID sourceVersionId,
            CanvasConnection.RelationType relationType, Long expectedTargetDraftVersion,
            Long expectedTargetAgentVersion) {
        return connect(ownerId, projectId, sourceItemId, targetItemId, sourceVersionId,
                relationType, expectedTargetDraftVersion, expectedTargetAgentVersion, false, null);
    }

    /** Connects a validated proposal input, retaining its role, order and fixed version. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ConnectionResult connectPreparedMediaInputWithinChange(UUID ownerId, UUID projectId,
            UUID sourceItemId, UUID targetItemId, UUID sourceVersionId, long expectedDraftVersion) {
        MediaDraft draft = drafts.get(ownerId, projectId, targetItemId);
        if (draft.mediaInputs().stream().noneMatch(input -> input.versionId().equals(sourceVersionId))) {
            throw invalid(ApiMessage.of("api.canvas-connection-service.the-image-entry-does-not-exist-in-the-media-draft"));
        }
        return connect(ownerId, projectId, sourceItemId, targetItemId, sourceVersionId,
                CanvasConnection.RelationType.MEDIA_INPUT, expectedDraftVersion, null, true, null);
    }

    private ConnectionResult connect(UUID ownerId, UUID projectId, UUID sourceItemId,
            UUID targetItemId, UUID sourceVersionId, CanvasConnection.RelationType relationType,
            Long expectedTargetDraftVersion, Long expectedTargetAgentVersion, boolean replaceManualSource,
            String slotKey) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            CanvasItem source = canvasItems.requireArtifactItem(ownerId, projectId,
                    sourceItemId);
            Artifact sourceArtifact = artifacts.get(ownerId, projectId,
                    source.subjectId()).artifact();
            if (sourceArtifact.kind() != Artifact.Kind.IMAGE
                    && (!Set.of(Artifact.Kind.AUDIO, Artifact.Kind.VIDEO).contains(sourceArtifact.kind())
                        || relationType != CanvasConnection.RelationType.MEDIA_INPUT)) {
                throw invalid(ApiMessage.of("api.canvas-connection-service.canvas-image-input-connections-must-originate-from-the-image-card"));
            }
            if (sourceVersionId == null
                    || !sourceVersionId.equals(source.selectedVersionId())) {
                throw conflict(ApiMessage.of("api.canvas-connection-service.the-source-image-version-has-changed-during-dragging-please-reconnect"));
            }
            if (relationType == CanvasConnection.RelationType.MEDIA_DERIVATION) {
                throw invalid(ApiMessage.of("api.canvas-connection-service.media-derived-lines-can-only-be-created-by-media-change"));
            }
            artifacts.requireVersion(ownerId, projectId, source.subjectId(), sourceVersionId);
            List<CanvasConnection> current = connections.list(ownerId, projectId);
            if (current.stream().anyMatch(connection ->
                    connection.sourceCanvasItemId().equals(sourceItemId)
                            && connection.targetCanvasItemId().equals(targetItemId)
                            && connection.relationType() == relationType)) {
                throw conflict(ApiMessage.of("api.canvas-connection-service.there-is-already-a-connection-of-the-same-kind-between"));
            }
            if (reaches(current, targetItemId, sourceItemId)) {
                throw invalid(ApiMessage.of("api.canvas-connection-service.this-connection-forms-a-canvasitem-loop"));
            }
            Instant now = clock.instant();
            CanvasConnection connection = new CanvasConnection(UUID.randomUUID(), projectId,
                    sourceItemId, targetItemId, relationType, sourceVersionId, 0, now, now);
            connections.create(connection);
            MediaDraft draft = null;
            AgentInstance agent = null;
            if (relationType == CanvasConnection.RelationType.MEDIA_INPUT) {
                CanvasItem target = canvasItems.requireArtifactItem(ownerId, projectId,
                        targetItemId);
                Artifact targetArtifact = artifacts.get(ownerId, projectId,
                        target.subjectId()).artifact();
                if (targetArtifact.kind() == Artifact.Kind.TEXT
                        || expectedTargetDraftVersion == null) {
                    throw invalid(ApiMessage.of("api.canvas-connection-service.the-media-input-connection-must-point-to-the-image-or"));
                }
                draft = drafts.addConnectionInputWithinChange(ownerId, projectId,
                        targetItemId, expectedTargetDraftVersion, sourceVersionId,
                        connection.id(), replaceManualSource, slotKey);
            } else if (relationType == CanvasConnection.RelationType.AGENT_IMAGE_INPUT) {
                CanvasItem target = canvasItems.requireAgentItem(ownerId, projectId, targetItemId);
                if (expectedTargetAgentVersion == null) {
                    throw invalid(ApiMessage.of("api.canvas-connection-service.the-agent-image-connection-must-carry-the-target-agent-version"));
                }
                agent = agents.addImageBindingWithinChange(ownerId, projectId,
                        target.subjectId(), expectedTargetAgentVersion,
                        sourceArtifact.id(), sourceVersionId);
            } else {
                throw invalid(ApiMessage.of("api.canvas-connection-service.unsupported-canvas-wire-type"));
            }
            return ProjectEventService.Change.changed(new ConnectionResult(connection, draft, agent),
                    connectionEvent(connection, "canvas.connection.created"));
        }).value();
    }

    /** Records removable media lineage without turning it into a draft input. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CanvasConnection createMediaDerivationWithinChange(UUID ownerId, UUID projectId,
            UUID sourceItemId, UUID targetItemId, UUID sourceVersionId) {
        projects.requireActiveProject(ownerId, projectId);
        CanvasItem source = canvasItems.requireArtifactItem(ownerId, projectId, sourceItemId);
        CanvasItem target = canvasItems.requireArtifactItem(ownerId, projectId, targetItemId);
        Artifact sourceArtifact = artifacts.get(ownerId, projectId, source.subjectId()).artifact();
        Artifact targetArtifact = artifacts.get(ownerId, projectId, target.subjectId()).artifact();
        boolean sameMediaBranch = sourceArtifact.kind() != Artifact.Kind.TEXT
                && targetArtifact.kind() == sourceArtifact.kind()
                && source.subjectId().equals(target.subjectId())
                && sourceVersionId.equals(target.selectedVersionId());
        boolean audioExtraction = sourceArtifact.kind() == Artifact.Kind.VIDEO
                && targetArtifact.kind() == Artifact.Kind.AUDIO
                && target.selectedVersionId() == null;
        if (!sourceVersionId.equals(source.selectedVersionId())
                || !(sameMediaBranch || audioExtraction)) {
            throw invalid(ApiMessage.of("api.canvas-connection-service.media-derivation-lines-must-connect-fixed-source-versions-and-new"));
        }
        Instant now = clock.instant();
        CanvasConnection connection = new CanvasConnection(UUID.randomUUID(), projectId,
                sourceItemId, targetItemId, CanvasConnection.RelationType.MEDIA_DERIVATION,
                sourceVersionId, 0, now, now);
        connections.create(connection);
        events.append(ownerId, projectId, connectionEvent(connection,
                "canvas.connection.created"));
        return connection;
    }

    @Transactional
    public ConnectionResult disconnect(UUID ownerId, UUID projectId, UUID connectionId,
            Long expectedTargetDraftVersion, Long expectedTargetAgentVersion) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            CanvasConnection connection = connections.find(ownerId, projectId, connectionId)
                    .orElseThrow(this::notFound);
            MediaDraft draft = null;
            AgentInstance agent = null;
            if (connection.relationType() == CanvasConnection.RelationType.MEDIA_INPUT) {
                if (expectedTargetDraftVersion == null) throw invalid(ApiMessage.of("api.canvas-connection-service.disconnected-media-is-missing-draft-versions"));
                draft = drafts.removeConnectionInputWithinChange(ownerId, projectId,
                        connection.targetCanvasItemId(), expectedTargetDraftVersion, connectionId);
            } else if (connection.relationType()
                    == CanvasConnection.RelationType.AGENT_IMAGE_INPUT) {
                CanvasItem target = canvasItems.requireAgentItem(ownerId, projectId,
                        connection.targetCanvasItemId());
                if (expectedTargetAgentVersion == null) throw invalid(ApiMessage.of("api.canvas-connection-service.disconnecting-agent-missing-agent-version"));
                agent = removeAgentBindingIfFinal(ownerId, projectId, connection,
                        target.subjectId(), expectedTargetAgentVersion);
            }
            if (!connections.delete(projectId, connectionId)) {
                throw conflict(ApiMessage.of("api.canvas-connection-service.the-connection-was-deleted-by-another-operation"));
            }
            return ProjectEventService.Change.changed(new ConnectionResult(connection, draft, agent),
                    connectionEvent(connection, "canvas.connection.deleted"));
        }).value();
    }

    /** Atomically clears one media input, all of its source lines and bound prompt mentions. */
    @Transactional
    public MediaDraft removeMediaInput(UUID ownerId, UUID projectId, UUID targetCanvasItemId,
            UUID imageVersionId, long expectedDraftVersion) {
        return events.recordChange(ownerId, projectId, () -> {
            projects.requireActiveProject(ownerId, projectId);
            MediaDraft before = drafts.get(ownerId, projectId, targetCanvasItemId);
            MediaDraft.MediaInput input = before.mediaInputs().stream()
                    .filter(candidate -> candidate.versionId().equals(imageVersionId))
                    .findFirst().orElseThrow(() -> invalid(ApiMessage.of("api.canvas-connection-service.the-image-entry-does-not-exist-in-the-media-draft")));
            MediaDraft updated = drafts.removeMediaInputWithinChange(ownerId, projectId,
                    targetCanvasItemId, expectedDraftVersion, imageVersionId);
            List<UUID> connectionIds = input.sources().stream()
                    .filter(source -> source.type() == MediaDraft.SourceType.CONNECTION)
                    .map(MediaDraft.InputSource::connectionId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            for (UUID connectionId : connectionIds) {
                if (!connections.delete(projectId, connectionId)) {
                    throw conflict(ApiMessage.of("api.canvas-connection-service.the-connection-associated-with-the-image-input-has-been-deleted"));
                }
            }
            ObjectNode payload = mapper.createObjectNode();
            payload.put("canvasItemId", targetCanvasItemId.toString());
            payload.put("draftVersion", updated.version());
            payload.put("imageVersionId", imageVersionId.toString());
            payload.put("removedConnectionCount", connectionIds.size());
            return ProjectEventService.Change.changed(updated,
                    new ProjectEventService.EventDraft("media.draft.changed", 1,
                            targetCanvasItemId, updated.version(), payload));
        }).value();
    }

    @Transactional(readOnly = true)
    public List<CanvasConnection> list(UUID ownerId, UUID projectId) {
        projects.get(ownerId, projectId);
        return connections.list(ownerId, projectId);
    }

    /** Removes every edge owned by a card and reference-counts downstream media inputs. */
    public void removeItemConnectionsWithinChange(UUID ownerId, UUID projectId,
            UUID canvasItemId) {
        List<CanvasConnection> affected = connections.list(ownerId, projectId).stream()
                .filter(connection -> connection.sourceCanvasItemId().equals(canvasItemId)
                        || connection.targetCanvasItemId().equals(canvasItemId))
                .toList();
        for (CanvasConnection connection : affected) {
            if (connection.sourceCanvasItemId().equals(canvasItemId)
                    && !connection.targetCanvasItemId().equals(canvasItemId)
                    && connection.relationType() == CanvasConnection.RelationType.MEDIA_INPUT) {
                MediaDraft target = drafts.get(ownerId, projectId,
                        connection.targetCanvasItemId());
                drafts.removeConnectionInputWithinChange(ownerId, projectId,
                        connection.targetCanvasItemId(), target.version(), connection.id());
            }
            if (connection.relationType() == CanvasConnection.RelationType.AGENT_IMAGE_INPUT) {
                CanvasItem target = canvasItems.requireAgentItem(ownerId, projectId,
                        connection.targetCanvasItemId());
                AgentInstance current = agents.get(ownerId, projectId, target.subjectId());
                removeAgentBindingIfFinal(ownerId, projectId, connection, target.subjectId(),
                        current.version());
            }
            if (!connections.delete(projectId, connection.id())) {
                throw conflict(ApiMessage.of("api.canvas-connection-service.the-connection-was-deleted-by-another-operation"));
            }
        }
    }

    private AgentInstance removeAgentBindingIfFinal(UUID ownerId, UUID projectId,
            CanvasConnection removing, UUID agentId, long expectedAgentVersion) {
        boolean stillReferenced = connections.list(ownerId, projectId).stream()
                .anyMatch(candidate -> !candidate.id().equals(removing.id())
                        && candidate.relationType() == CanvasConnection.RelationType.AGENT_IMAGE_INPUT
                        && candidate.targetCanvasItemId().equals(removing.targetCanvasItemId())
                        && candidate.sourceArtifactVersionId().equals(
                                removing.sourceArtifactVersionId()));
        return stillReferenced ? agents.get(ownerId, projectId, agentId)
                : agents.removeImageBindingWithinChange(ownerId, projectId, agentId,
                        expectedAgentVersion, removing.sourceArtifactVersionId());
    }

    /** Clears all editable media lines into a target before a full historical-input restore. */
    public long clearTargetMediaConnectionsWithinChange(UUID ownerId, UUID projectId,
            UUID targetCanvasItemId, long expectedDraftVersion) {
        MediaDraft current = drafts.get(ownerId, projectId, targetCanvasItemId);
        if (current.version() != expectedDraftVersion) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    ApiMessage.of("api.canvas-connection-service.draft-version-conflict"), ApiMessage.of("api.canvas-connection-service.the-target-draft-has-changed-before-restoring-history-input"), true);
        }
        List<CanvasConnection> affected = connections.list(ownerId, projectId).stream()
                .filter(connection -> connection.targetCanvasItemId().equals(targetCanvasItemId)
                        && connection.relationType() == CanvasConnection.RelationType.MEDIA_INPUT)
                .toList();
        for (CanvasConnection connection : affected) {
            current = drafts.removeConnectionInputWithinChange(ownerId, projectId,
                    targetCanvasItemId, current.version(), connection.id());
            if (!connections.delete(projectId, connection.id())) {
                throw conflict(ApiMessage.of("api.canvas-connection-service.the-connection-has-been-deleted-by-other-operations-when-restoring"));
            }
        }
        return current.version();
    }

    private boolean reaches(List<CanvasConnection> graph, UUID start, UUID target) {
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (current.equals(target)) return true;
            graph.stream().filter(edge -> edge.relationType()
                            != CanvasConnection.RelationType.MEDIA_DERIVATION
                            && edge.sourceCanvasItemId().equals(current))
                    .map(CanvasConnection::targetCanvasItemId).forEach(queue::addLast);
        }
        return false;
    }

    private ProjectEventService.EventDraft connectionEvent(CanvasConnection connection,
            String type) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("connectionId", connection.id().toString());
        payload.put("sourceCanvasItemId", connection.sourceCanvasItemId().toString());
        payload.put("targetCanvasItemId", connection.targetCanvasItemId().toString());
        return new ProjectEventService.EventDraft(type, 1, connection.id(),
                connection.version(), payload);
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                ApiMessage.of("api.canvas-connection-service.the-connection-does-not-exist"), ApiMessage.of("api.canvas-connection-service.the-connection-does-not-exist-or-the-current-user-does"), false);
    }

    private static ApiProblemException invalid(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.canvas-connection-service.canvas-connection-is-invalid"), detail, false);
    }

    private static ApiProblemException conflict(ApiMessage detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "CANVAS_CONNECTION_CONFLICT",
                ApiMessage.of("api.canvas-connection-service.canvas-connection-conflict"), detail, true);
    }

    public record ConnectionResult(CanvasConnection connection, MediaDraft draft,
            AgentInstance agent) {}
}

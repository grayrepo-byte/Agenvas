package dev.agenvas.canvas.api;

import dev.agenvas.agent.api.AgentInstanceController.AgentResponse;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasConnection;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas/connections")
public class CanvasConnectionController {
    private final CanvasConnectionService connections;

    public CanvasConnectionController(CanvasConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping
    public ConnectionList list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return new ConnectionList(connections.list(principal.userId(), projectId).stream()
                .map(ConnectionView::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResult connect(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @Valid @RequestBody ConnectRequest request) {
        return ConnectionResult.from(connections.connect(principal.userId(), projectId,
                request.sourceCanvasItemId(), request.targetCanvasItemId(),
                request.sourceVersionId(), request.relationType(),
                request.expectedTargetDraftVersion(), request.expectedTargetAgentVersion()));
    }

    @PostMapping("/{connectionId}/disconnect")
    public ConnectionResult disconnect(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID connectionId,
            @Valid @RequestBody DisconnectRequest request) {
        return ConnectionResult.from(connections.disconnect(principal.userId(), projectId,
                connectionId, request.expectedTargetDraftVersion(),
                request.expectedTargetAgentVersion()));
    }

    public record ConnectRequest(@NotNull UUID sourceCanvasItemId,
            @NotNull UUID targetCanvasItemId, @NotNull UUID sourceVersionId,
            @NotNull CanvasConnection.RelationType relationType,
            @PositiveOrZero Long expectedTargetDraftVersion,
            @PositiveOrZero Long expectedTargetAgentVersion) {}
    public record DisconnectRequest(@PositiveOrZero Long expectedTargetDraftVersion,
            @PositiveOrZero Long expectedTargetAgentVersion) {}
    public record ConnectionList(List<ConnectionView> items) {}
    public record ConnectionResult(ConnectionView connection, MediaDraftView draft,
            AgentResponse agent) {
        private static ConnectionResult from(CanvasConnectionService.ConnectionResult result) {
            return new ConnectionResult(ConnectionView.from(result.connection()),
                    MediaDraftView.from(result.draft()), result.agent() == null
                            ? null : AgentResponse.from(result.agent()));
        }
    }
    public record ConnectionView(UUID id, UUID projectId, UUID sourceCanvasItemId,
            UUID targetCanvasItemId, CanvasConnection.RelationType relationType,
            UUID sourceArtifactVersionId, long version, Instant createdAt, Instant updatedAt) {
        public static ConnectionView from(CanvasConnection connection) {
            return new ConnectionView(connection.id(), connection.projectId(),
                    connection.sourceCanvasItemId(), connection.targetCanvasItemId(),
                    connection.relationType(), connection.sourceArtifactVersionId(),
                    connection.version(), connection.createdAt(), connection.updatedAt());
        }
    }
    public record MediaDraftView(UUID projectId, UUID canvasItemId, String prompt,
            JsonNode parameters, Integer durationSeconds, UUID capabilityId,
            String videoInputMode, List<MediaInputView> mediaInputs,
            List<PromptMentionView> mentions, String displayMode, long version,
            Instant createdAt, Instant updatedAt) {
        private static MediaDraftView from(MediaDraft draft) {
            if (draft == null) return null;
            return new MediaDraftView(draft.projectId(), draft.canvasItemId(), draft.prompt(),
                    draft.parameters(), draft.durationSeconds(), draft.capabilityId(),
                    draft.videoInputMode() == null ? null : draft.videoInputMode().name(),
                    draft.mediaInputs().stream().map(MediaInputView::from).toList(),
                    draft.mentions().stream().map(PromptMentionView::from).toList(),
                    draft.displayMode().name(), draft.version(), draft.createdAt(),
                    draft.updatedAt());
        }
    }
    public record MediaInputView(UUID versionId, UUID artifactId, String role, int order,
            String color, List<InputSourceView> sources) {
        private static MediaInputView from(MediaDraft.MediaInput input) {
            return new MediaInputView(input.versionId(), input.artifactId(),
                    input.role().name(), input.order(), input.color(),
                    input.sources().stream().map(InputSourceView::from).toList());
        }
    }
    public record InputSourceView(UUID id, String type, UUID connectionId) {
        private static InputSourceView from(MediaDraft.InputSource source) {
            return new InputSourceView(source.id(), source.type().name(), source.connectionId());
        }
    }
    public record PromptMentionView(UUID versionId, String role) {
        private static PromptMentionView from(MediaDraft.PromptMention mention) {
            return new PromptMentionView(mention.versionId(), mention.role().name());
        }
    }
}

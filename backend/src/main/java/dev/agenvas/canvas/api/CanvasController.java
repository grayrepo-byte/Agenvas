package dev.agenvas.canvas.api;

import dev.agenvas.agent.api.AgentInstanceController.AgentResponse;
import dev.agenvas.artifact.api.ArtifactController.ArtifactResponse;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated REST boundary for persisted canvas projection and atomic layout commands. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas")
public class CanvasController {

    private final CanvasService canvas;

    public CanvasController(CanvasService canvas) {
        this.canvas = canvas;
    }

    /** Returns the authoritative persisted layout with card subject projections. */
    @GetMapping("/items")
    public CanvasResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return CanvasResponse.from(canvas.list(principal.userId(), projectId));
    }

    /** Applies one atomic batch so partial drag or resize saves cannot leak through. */
    @PostMapping("/commands")
    public CanvasResponse apply(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody CanvasCommandBatchRequest request) {
        List<CanvasService.CanvasCommand> commands =
                request.commands().stream().map(this::toCommand).toList();
        return CanvasResponse.from(canvas.apply(principal.userId(), projectId, commands));
    }

    private CanvasService.CanvasCommand toCommand(CanvasCommandRequest request) {
        return switch (request.type()) {
            case PLACE_ARTIFACT -> new CanvasService.PlaceArtifact(
                    request.itemId(),
                    require(request.artifactId(), "artifactId"),
                    require(request.x(), "x"),
                    require(request.y(), "y"),
                    require(request.width(), "width"),
                    require(request.height(), "height"),
                    require(request.zIndex(), "zIndex"),
                    request.groupId(),
                    request.locked() != null && request.locked());
            case PLACE_AGENT -> new CanvasService.PlaceAgent(
                    request.itemId(),
                    require(request.agentId(), "agentId"),
                    require(request.x(), "x"),
                    require(request.y(), "y"),
                    require(request.width(), "width"),
                    require(request.height(), "height"),
                    require(request.zIndex(), "zIndex"),
                    request.groupId(),
                    request.locked() != null && request.locked());
            case UPDATE_LAYOUT -> new CanvasService.UpdateLayout(
                    request.itemId(),
                    require(request.expectedVersion(), "expectedVersion"),
                    require(request.x(), "x"),
                    require(request.y(), "y"),
                    require(request.width(), "width"),
                    require(request.height(), "height"),
                    require(request.zIndex(), "zIndex"),
                    request.groupId());
            case SET_LOCKED -> new CanvasService.SetLocked(
                    request.itemId(),
                    require(request.expectedVersion(), "expectedVersion"),
                    require(request.locked(), "locked"));
            case REMOVE -> new CanvasService.Remove(
                    request.itemId(), require(request.expectedVersion(), "expectedVersion"));
        };
    }

    private <T> T require(T value, String field) {
        if (value == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "画布命令无效",
                    field + " 是当前命令的必填字段。",
                    false);
        }
        return value;
    }

    /** Atomic command batch with a bounded number of independent item targets. */
    public record CanvasCommandBatchRequest(
            @NotEmpty @Size(max = 100) List<@Valid CanvasCommandRequest> commands) {}

    /** Union-shaped command input; type-specific required fields are checked before execution. */
    public record CanvasCommandRequest(
            @NotNull CommandType type,
            @NotNull UUID itemId,
            UUID artifactId,
            UUID agentId,
            @PositiveOrZero Long expectedVersion,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            Integer zIndex,
            UUID groupId,
            Boolean locked) {}

    /** Supported canvas operations. */
    public enum CommandType {
        PLACE_ARTIFACT,
        PLACE_AGENT,
        UPDATE_LAYOUT,
        SET_LOCKED,
        REMOVE
    }

    /** Full authoritative canvas response. */
    public record CanvasResponse(List<CanvasItemResponse> items) {

        public static CanvasResponse from(List<CanvasService.CanvasEntry> entries) {
            return new CanvasResponse(entries.stream().map(CanvasItemResponse::from).toList());
        }
    }

    /** Persisted layout plus the current content projection used to render its card. */
    public record CanvasItemResponse(
            UUID id,
            CanvasItem.SubjectType subjectType,
            UUID subjectId,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId,
            boolean locked,
            long version,
            ArtifactResponse artifact,
            AgentResponse agent) {

        static CanvasItemResponse from(CanvasService.CanvasEntry entry) {
            CanvasItem item = entry.item();
            return new CanvasItemResponse(
                    item.id(),
                    item.subjectType(),
                    item.subjectId(),
                    item.x(),
                    item.y(),
                    item.width(),
                    item.height(),
                    item.zIndex(),
                    item.groupId(),
                    item.locked(),
                    item.version(),
                    entry.artifact() == null ? null : ArtifactResponse.from(entry.artifact()),
                    entry.agent() == null ? null : AgentResponse.from(entry.agent()));
        }
    }
}

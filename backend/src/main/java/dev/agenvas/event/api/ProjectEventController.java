package dev.agenvas.event.api;

import dev.agenvas.event.application.ProjectEventHub;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Authenticated event stream with explicit and browser-native Last-Event-ID replay cursors. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
public class ProjectEventController {

    private final ProjectEventHub hub;

    public ProjectEventController(ProjectEventHub hub) {
        this.hub = hub;
    }

    /** Streams committed events after the chosen exclusive sequence cursor. */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Long after,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long cursor = lastEventId == null || lastEventId.isBlank()
                ? after == null ? 0 : after
                : parseLastEventId(lastEventId);
        return hub.subscribe(principal.userId(), projectId, cursor);
    }

    private long parseLastEventId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "事件游标无效",
                    "Last-Event-ID 必须是项目事件序号。",
                    false);
        }
    }
}

package dev.agenvas.audit.api;

import dev.agenvas.audit.application.CallLogService;
import dev.agenvas.audit.domain.CallLog;
import dev.agenvas.audit.domain.CallLogPage;
import dev.agenvas.identity.application.AdminPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator audit view, scoped to projects belonging to the authenticated principal. */
@RestController
public class CallLogController {
    private final CallLogService logs;
    public CallLogController(CallLogService logs) { this.logs = logs; }

    @GetMapping("/api/v1/call-logs")
    public PageResponse list(@AuthenticationPrincipal AdminPrincipal principal,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) CallLog.Kind kind,
            @RequestParam(required = false) CallLog.Status status,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(logs.list(principal.userId(),
                new CallLogService.Filter(projectId, kind, status, traceId, from, to, page, size)));
    }

    public record PageResponse(List<CallResponse> items, int page, int size,
            long totalElements, long totalPages) {
        static PageResponse from(CallLogPage page) {
            return new PageResponse(page.items().stream().map(CallResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }
    public record CallResponse(UUID id, UUID projectId, String projectTitle, UUID taskId, UUID runId,
            CallLog.Kind kind, CallLog.Operation operation, CallLog.Status status,
            dev.agenvas.task.domain.Task.Status taskStatus, String provider, String model,
            String traceId, String providerRequestId, String errorCode, Instant startedAt,
            Instant respondedAt, Long durationMs, boolean historical, boolean mock) {
        static CallResponse from(CallLog value) {
            return new CallResponse(value.id(), value.projectId(), value.projectTitle(),
                    value.taskId(), value.runId(), value.kind(), value.operation(), value.status(),
                    value.taskStatus(), value.provider(), value.model(), value.traceId(),
                    value.providerRequestId(), value.errorCode(), value.startedAt(), value.respondedAt(),
                    value.durationMs(), value.historical(), value.mock());
        }
    }
}

package dev.agenvas.settings.api;

import dev.agenvas.settings.application.SystemLogBuffer;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only process diagnostics; no filesystem paths or commands are accepted. */
@RestController
public class SystemLogsController {
    private final SystemLogBuffer logs;

    public SystemLogsController(SystemLogBuffer logs) { this.logs = logs; }

    @GetMapping("/api/v1/settings/system-logs")
    public ResponseEntity<SystemLogBuffer.Snapshot> list(
            @RequestParam(required = false) SystemLogBuffer.Stream stream,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "" + SystemLogBuffer.DEFAULT_LIMIT) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(logs.snapshot(stream, search, limit));
    }
}

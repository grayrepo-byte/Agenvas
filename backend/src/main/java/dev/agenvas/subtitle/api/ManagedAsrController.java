package dev.agenvas.subtitle.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.subtitle.runtime.ManagedAsrService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import java.util.Objects;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings/local-asr")
public class ManagedAsrController {
    private final ManagedAsrService service;
    public ManagedAsrController(ManagedAsrService service){this.service=service;}
    public record Settings(@NotNull Boolean enabled,@Min(1) long expectedVersion){}
    public record Version(@Min(1) long expectedVersion){}
    public record Cancel(@NotNull UUID operationId){}
    @GetMapping public ResponseEntity<ManagedAsrService.Snapshot> get(@AuthenticationPrincipal AdminPrincipal admin){Objects.requireNonNull(admin);return response(service.get());}
    @PutMapping public ResponseEntity<ManagedAsrService.Snapshot> update(@AuthenticationPrincipal AdminPrincipal admin,@Valid @RequestBody Settings request){Objects.requireNonNull(admin);return response(service.update(request.enabled(),request.expectedVersion()));}
    @PostMapping("/download") public ResponseEntity<ManagedAsrService.Snapshot> download(@AuthenticationPrincipal AdminPrincipal admin,@Valid @RequestBody Version request){Objects.requireNonNull(admin);return response(service.download(request.expectedVersion()));}
    @PostMapping("/cancel") public ResponseEntity<ManagedAsrService.Snapshot> cancel(@AuthenticationPrincipal AdminPrincipal admin,@Valid @RequestBody Cancel request){Objects.requireNonNull(admin);return response(service.cancel(request.operationId()));}
    @PostMapping("/check") public ResponseEntity<ManagedAsrService.Snapshot> check(@AuthenticationPrincipal AdminPrincipal admin,@Valid @RequestBody Version request){Objects.requireNonNull(admin);return response(service.check(request.expectedVersion()));}
    private static ResponseEntity<ManagedAsrService.Snapshot> response(ManagedAsrService.Snapshot body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}

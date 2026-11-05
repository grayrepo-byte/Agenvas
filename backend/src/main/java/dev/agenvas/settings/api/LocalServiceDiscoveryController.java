package dev.agenvas.settings.api;

import dev.agenvas.settings.application.LocalServiceDiscoveryService;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Service;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Administrator/CSRF-protected explicit action; no endpoint supplied by the caller. */
@RestController
public class LocalServiceDiscoveryController {
    private final LocalServiceDiscoveryService discovery;
    public LocalServiceDiscoveryController(LocalServiceDiscoveryService discovery) { this.discovery = discovery; }

    @PostMapping("/api/v1/settings/local-services/discover")
    public ResponseEntity<LocalServiceDiscoveryService.Result> discover(@RequestBody JsonNode request) {
        if (request == null || !request.isObject() || request.size() != 1 || !request.path("service").isString()) throw invalid();
        Service service;
        try { service = Service.valueOf(request.path("service").asString()); }
        catch (IllegalArgumentException invalid) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(discovery.discover(service));
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                ApiMessage.of("api.local-service-discovery.title"), ApiMessage.of("api.local-service-discovery.invalid"), false);
    }
}

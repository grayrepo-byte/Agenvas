package dev.agenvas.identity.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Supplies the CSRF token that the SPA must echo on state-changing requests. */
@RestController
@RequestMapping("/api/v1/auth")
public class CsrfController {

    /** Forces deferred token creation and returns only the header contract needed by the SPA. */
    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    /** CSRF token response for browser clients. */
    public record CsrfResponse(String headerName, String token) {}
}

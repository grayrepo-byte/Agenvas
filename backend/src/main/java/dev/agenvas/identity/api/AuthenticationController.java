package dev.agenvas.identity.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.AdminSessionService;
import dev.agenvas.identity.application.AuthenticationAttemptLimiter;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary for setup and administrator session lifecycle operations. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final IdentityService identityService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContexts;
    private final CsrfTokenRepository csrfTokens;
    private final AuthenticationAttemptLimiter attemptLimiter;
    private final AdminSessionService sessionService;
    private final SecurityContextHolderStrategy contextHolder =
            SecurityContextHolder.getContextHolderStrategy();

    public AuthenticationController(
            IdentityService identityService,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContexts,
            CsrfTokenRepository csrfTokens,
            AuthenticationAttemptLimiter attemptLimiter,
            AdminSessionService sessionService) {
        this.identityService = identityService;
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
        this.csrfTokens = csrfTokens;
        this.attemptLimiter = attemptLimiter;
        this.sessionService = sessionService;
    }

    /** Creates the first administrator; it does not implicitly create a login session. */
    @PostMapping("/setup")
    public ResponseEntity<CurrentUserResponse> setup(
            @RequestHeader("X-Agenvas-Bootstrap-Secret") String bootstrapSecret,
            @Valid @RequestBody SetupRequest request) {
        AdminPrincipal principal = identityService.setup(
                bootstrapSecret, request.loginName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(CurrentUserResponse.from(principal));
    }

    /** Authenticates credentials, rotates the session id, and persists the security context. */
    @PostMapping("/login")
    public CurrentUserResponse login(
            @Valid @RequestBody LoginRequest login,
            HttpServletRequest request,
            HttpServletResponse response) {
        String normalizedLogin = IdentityService.normalizeLoginName(login.loginName());
        String limiterKey = "login:" + request.getRemoteAddr() + ":" + normalizedLogin;
        attemptLimiter.checkAllowed(limiterKey);
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            normalizedLogin, login.password()));
            HttpSession session = request.getSession(true);
            request.changeSessionId();
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            contextHolder.setContext(context);
            securityContexts.saveContext(context, request, response);
            csrfTokens.saveToken(null, request, response);
            attemptLimiter.reset(limiterKey);
            return CurrentUserResponse.from((AdminPrincipal) authentication.getPrincipal());
        } catch (BadCredentialsException invalidCredentials) {
            attemptLimiter.recordFailure(limiterKey);
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "登录失败",
                    "登录名或密码不正确。",
                    false);
        }
    }

    /** Returns the currently authenticated administrator without sensitive account fields. */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AdminPrincipal principal) {
        return CurrentUserResponse.from(principal);
    }

    /** Invalidates the current server-side session and clears its CSRF token. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request, HttpServletResponse response) {
        contextHolder.clearContext();
        securityContexts.saveContext(contextHolder.createEmptyContext(), request, response);
        csrfTokens.saveToken(null, request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    /** Changes the password and removes every older administrator session. */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest password,
            HttpServletRequest request) {
        String limiterKey = "password:" + principal.userId();
        attemptLimiter.checkAllowed(limiterKey);
        try {
            identityService.changePassword(
                    principal, password.currentPassword(), password.newPassword());
            attemptLimiter.reset(limiterKey);
            sessionService.invalidateOtherSessions(principal.loginName(), request.getSession().getId());
            return ResponseEntity.noContent().build();
        } catch (ApiProblemException problem) {
            if (problem.status() == HttpStatus.UNAUTHORIZED) {
                attemptLimiter.recordFailure(limiterKey);
            }
            throw problem;
        }
    }

    /** Validated first-administrator request. */
    public record SetupRequest(
            @NotBlank
                    @Size(min = 3, max = 64)
                    @Pattern(regexp = "[A-Za-z0-9._-]+", message = "只能包含字母、数字、点、下划线和连字符")
                    String loginName,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    /** Validated login request. */
    public record LoginRequest(
            @NotBlank @Size(min = 3, max = 64) String loginName,
            @NotBlank @Size(max = 128) String password) {}

    /** Validated password change request. */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}

    /** Public authenticated administrator representation. */
    public record CurrentUserResponse(String id, String loginName, String role) {
        static CurrentUserResponse from(AdminPrincipal principal) {
            return new CurrentUserResponse(principal.userId().toString(), principal.loginName(), "ADMIN");
        }
    }
}

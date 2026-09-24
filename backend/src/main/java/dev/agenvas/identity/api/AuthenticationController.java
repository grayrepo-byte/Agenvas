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

/** 首次初始化、管理员登录、会话查询、退出和密码变更的 HTTP 边界。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    /** 执行初始化凭据与管理员密码校验。 */
    private final IdentityService identityService;
    /** 验证已存储的管理员账户凭据。 */
    private final AuthenticationManager authenticationManager;
    /** 保存和清除服务端 Session 中的 SecurityContext。 */
    private final SecurityContextRepository securityContexts;
    /** 生成、轮换和清除与 Session 绑定的 CSRF Token。 */
    private final CsrfTokenRepository csrfTokens;
    /** 按来源地址及账户维度限制连续失败请求。 */
    private final AuthenticationAttemptLimiter attemptLimiter;
    /** 密码更新后使其他管理员会话失效。 */
    private final AdminSessionService sessionService;
    /** 当前线程的 Spring Security 上下文策略，用于安全地清理和保存认证。 */
    private final SecurityContextHolderStrategy contextHolder =
            SecurityContextHolder.getContextHolderStrategy();

    /** 注入账户、认证、Session 和失败限流组件。 */
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

    /** 创建首个管理员账户；初始化成功不会自动建立登录会话。 */
    @PostMapping("/setup")
    public ResponseEntity<CurrentUserResponse> setup(
            @RequestHeader("X-Agenvas-Bootstrap-Secret") String bootstrapSecret,
            @Valid @RequestBody SetupRequest request) {
        AdminPrincipal principal = identityService.setup(
                bootstrapSecret, request.loginName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(CurrentUserResponse.from(principal));
    }

    /** 验证账户凭据、轮换 Session ID 并持久化安全上下文，同时重置 CSRF Token。 */
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

    /** 返回当前已认证管理员的公开信息，不包含密码哈希等账户敏感字段。 */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AdminPrincipal principal) {
        return CurrentUserResponse.from(principal);
    }

    /** 清除安全上下文和 CSRF Token，并销毁当前服务端会话。 */
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

    /** 在失败限流保护下变更密码，并使该管理员的其他旧会话失效。 */
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

    /** 经过 Bean Validation 的首个管理员输入。
     * @param loginName 首个管理员登录名，仅允许字母、数字及指定分隔符
     * @param password 初始密码，至少 12 个字符且最多 128 个字符
     */
    public record SetupRequest(
            @NotBlank
                    @Size(min = 3, max = 64)
                    @Pattern(regexp = "[A-Za-z0-9._-]+", message = "只能包含字母、数字、点、下划线和连字符")
                    String loginName,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    /** 经过 Bean Validation 的登录请求。
     * @param loginName 待验证的管理员登录名
     * @param password 待验证的明文密码
     */
    public record LoginRequest(
            @NotBlank @Size(min = 3, max = 64) String loginName,
            @NotBlank @Size(max = 128) String password) {}

    /** 经过 Bean Validation 的密码修改请求。
     * @param currentPassword 当前明文密码
     * @param newPassword 新密码，至少 12 个字符
     */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}

    /** 对外返回的管理员身份，不包含账户状态或认证凭据。
     * @param id 管理员 UUID 字符串
     * @param loginName 规范化后的登录名
     * @param role 当前公开角色，首版固定为 ADMIN
     */
    public record CurrentUserResponse(String id, String loginName, String role) {
        /** 从已认证主体投影为不含敏感字段的响应 DTO。 */
        static CurrentUserResponse from(AdminPrincipal principal) {
            return new CurrentUserResponse(principal.userId().toString(), principal.loginName(), "ADMIN");
        }
    }
}

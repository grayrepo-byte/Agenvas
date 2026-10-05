package dev.agenvas.identity.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 负责一次性管理员初始化和敏感账户操作，并在数据库事务中仲裁初始化及并发版本。 */
@Service
public class IdentityService {

    /** BCrypt 实际处理的 UTF-8 字节上限，避免长密码被算法静默截断。 */
    private static final int BCRYPT_MAX_BYTES = 72;

    /** 创建账户并锁定初始化流程，防止并发创建多个初始管理员。 */
    private final AdminAccountRepository accounts;
    /** 对管理员密码进行带盐编码并验证现有密码。 */
    private final PasswordEncoder passwordEncoder;
    /** 为账户创建和密码更新提供可注入的时间源。 */
    private final Clock clock;

    /** 注入账户持久化、密码编码和时间源。 */
    public IdentityService(
            AdminAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 串行创建管理员并持久记录完成状态；停用或删除账户也不重新开放初始化。 */
    @Transactional
    public AdminPrincipal setup(String loginName, String password) {
        String normalizedLogin = normalizeLoginName(loginName);
        validateLoginName(normalizedLogin);
        validatePassword(password);
        accounts.lockSetup();
        if (accounts.isSetupCompleted()) {
            throw alreadyInitialized();
        }

        UUID userId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            accounts.createAdmin(userId, normalizedLogin, passwordEncoder.encode(password), now);
            if (!accounts.completeSetup(now)) {
                throw alreadyInitialized();
            }
        } catch (DataIntegrityViolationException conflict) {
            throw alreadyInitialized();
        }
        return new AdminPrincipal(userId, normalizedLogin);
    }

    /** 验证当前密码后更新新密码，并以账户版本防止并发覆盖。 */
    @Transactional
    public void changePassword(AdminPrincipal principal, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        AdminAccount account = accounts.findActiveByLoginName(principal.loginName())
                .filter(found -> found.id().equals(principal.userId()))
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                        ApiMessage.of("api.identity-service.login-has-expired"),
                        ApiMessage.of("api.identity-service.the-current-administrator-account-is-unavailable-please-log-in-again"),
                        false));
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "CURRENT_PASSWORD_INVALID",
                    ApiMessage.of("api.identity-service.the-current-password-is-incorrect"),
                    ApiMessage.of("api.identity-service.please-check-the-current-password-and-try-again"),
                    false);
        }
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_UNCHANGED",
                    ApiMessage.of("api.identity-service.new-password-unchanged"),
                    ApiMessage.of("api.identity-service.the-new-password-must-be-different-from-the-current-password"),
                    false);
        }
        boolean updated = accounts.updatePassword(
                account.id(), account.version(), passwordEncoder.encode(newPassword), clock.instant());
        if (!updated) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    ApiMessage.of("api.identity-service.account-updated"),
                    ApiMessage.of("api.identity-service.the-administrator-account-has-been-updated-in-other-requests-please"),
                    false);
        }
    }

    /** 去除首尾空白并按 Locale.ROOT 转小写，形成大小写不敏感的登录名。 */
    public static String normalizeLoginName(String loginName) {
        if (loginName == null) {
            return "";
        }
        return loginName.trim().toLowerCase(Locale.ROOT);
    }

    /** 限制密码字符长度和 UTF-8 字节数，避免 BCrypt 截断产生等价密码。 */
    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw validationProblem(ApiMessage.of("api.identity-service.password-must-be-12-to-128-characters"));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    ApiMessage.of("api.identity-service.password-too-long"),
                    ApiMessage.of("api.identity-service.the-utf-8-encoding-of-the-password-cannot-exceed-72"),
                    false);
        }
    }

    /** 仅允许规范化后的 ASCII 登录标识及规定长度。 */
    private void validateLoginName(String loginName) {
        if (!loginName.matches("[a-z0-9._-]{3,64}")) {
            throw validationProblem(ApiMessage.of("api.identity-service.login-name-must-be-3-to-64-letters-numbers-dots"));
        }
    }

    /** 构造登录名或通用密码规则失败时使用的 400 响应。 */
    private ApiProblemException validationProblem(ApiMessage detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                ApiMessage.of("api.identity-service.invalid-request"),
                detail,
                false);
    }

    /** 构造初始化已完成时使用的 409 响应。 */
    private ApiProblemException alreadyInitialized() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "SETUP_ALREADY_COMPLETED",
                ApiMessage.of("api.identity-service.system-has-been-initialized"),
                ApiMessage.of("api.identity-service.the-administrator-already-exists-and-cannot-perform-initialization-again"),
                false);
    }
}

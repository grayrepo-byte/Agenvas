package dev.agenvas.identity.application;

import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates one-time setup and security-sensitive administrator changes. */
@Service
public class IdentityService {

    private static final int BCRYPT_MAX_BYTES = 72;

    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final IdentityProperties properties;
    private final Clock clock;

    public IdentityService(
            AdminAccountRepository accounts,
            PasswordEncoder passwordEncoder,
            IdentityProperties properties,
            Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates the installation's only administrator after verifying the bootstrap secret. */
    @Transactional
    public AdminPrincipal setup(String presentedSecret, String loginName, String password) {
        verifyBootstrapSecret(presentedSecret);
        String normalizedLogin = normalizeLoginName(loginName);
        validateLoginName(normalizedLogin);
        validatePassword(password);
        accounts.lockSetup();
        if (accounts.hasAdminAccount()) {
            throw alreadyInitialized();
        }

        UUID userId = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            accounts.createAdmin(userId, normalizedLogin, passwordEncoder.encode(password), now);
        } catch (DataIntegrityViolationException conflict) {
            throw alreadyInitialized();
        }
        return new AdminPrincipal(userId, normalizedLogin);
    }

    /** Changes the authenticated administrator password using optimistic concurrency. */
    @Transactional
    public void changePassword(AdminPrincipal principal, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        AdminAccount account = accounts.findActiveByLoginName(principal.loginName())
                .filter(found -> found.id().equals(principal.userId()))
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                        "登录已失效",
                        "当前管理员账户不可用，请重新登录。",
                        false));
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "CURRENT_PASSWORD_INVALID",
                    "当前密码不正确",
                    "请核对当前密码后重试。",
                    false);
        }
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_UNCHANGED",
                    "新密码未变化",
                    "新密码必须与当前密码不同。",
                    false);
        }
        boolean updated = accounts.updatePassword(
                account.id(), account.version(), passwordEncoder.encode(newPassword), clock.instant());
        if (!updated) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "账户已更新",
                    "管理员账户已在其他请求中更新，请重新登录后再试。",
                    false);
        }
    }

    /** Normalizes the case-insensitive P0 login identifier. */
    public static String normalizeLoginName(String loginName) {
        if (loginName == null) {
            return "";
        }
        return loginName.trim().toLowerCase(Locale.ROOT);
    }

    private void verifyBootstrapSecret(String presentedSecret) {
        byte[] expected = properties.bootstrapSecret().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presentedSecret == null
                ? new byte[0]
                : presentedSecret.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "BOOTSTRAP_SECRET_INVALID",
                    "初始化凭据无效",
                    "部署提供的初始化凭据不正确。",
                    false);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw validationProblem("密码必须为 12 至 128 个字符。");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "密码过长",
                    "密码的 UTF-8 编码不能超过 72 字节。",
                    false);
        }
    }

    private void validateLoginName(String loginName) {
        if (!loginName.matches("[a-z0-9._-]{3,64}")) {
            throw validationProblem("登录名必须为 3 至 64 位字母、数字、点、下划线或连字符。");
        }
    }

    private ApiProblemException validationProblem(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                detail,
                false);
    }

    private ApiProblemException alreadyInitialized() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "SETUP_ALREADY_COMPLETED",
                "系统已初始化",
                "管理员已经存在，不能再次执行初始化。",
                false);
    }
}

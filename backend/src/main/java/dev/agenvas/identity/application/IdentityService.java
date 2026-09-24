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

/** 负责首次管理员初始化和敏感账户操作，并在服务端验证部署凭据及并发版本。 */
@Service
public class IdentityService {

    /** BCrypt 实际处理的 UTF-8 字节上限，避免长密码被算法静默截断。 */
    private static final int BCRYPT_MAX_BYTES = 72;

    /** 创建账户并锁定初始化流程，防止并发创建多个初始管理员。 */
    private final AdminAccountRepository accounts;
    /** 对管理员密码进行带盐编码并验证现有密码。 */
    private final PasswordEncoder passwordEncoder;
    /** 提供部署时注入的一次性初始化凭据。 */
    private final IdentityProperties properties;
    /** 为账户创建和密码更新提供可注入的时间源。 */
    private final Clock clock;

    /** 注入账户持久化、密码编码和初始化配置依赖。 */
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

    /** 校验部署凭据并串行执行一次性初始化；账户已存在时拒绝再次创建。 */
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

    /** 验证当前密码后更新新密码，并以账户版本防止并发覆盖。 */
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

    /** 去除首尾空白并按 Locale.ROOT 转小写，形成大小写不敏感的登录名。 */
    public static String normalizeLoginName(String loginName) {
        if (loginName == null) {
            return "";
        }
        return loginName.trim().toLowerCase(Locale.ROOT);
    }

    /** 以常量时间字节比较验证部署凭据，不记录或回显任一凭据内容。 */
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

    /** 限制密码字符长度和 UTF-8 字节数，避免 BCrypt 截断产生等价密码。 */
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

    /** 仅允许规范化后的 ASCII 登录标识及规定长度。 */
    private void validateLoginName(String loginName) {
        if (!loginName.matches("[a-z0-9._-]{3,64}")) {
            throw validationProblem("登录名必须为 3 至 64 位字母、数字、点、下划线或连字符。");
        }
    }

    /** 构造登录名或通用密码规则失败时使用的 400 响应。 */
    private ApiProblemException validationProblem(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数无效",
                detail,
                false);
    }

    /** 构造初始化已完成时使用的 409 响应。 */
    private ApiProblemException alreadyInitialized() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "SETUP_ALREADY_COMPLETED",
                "系统已初始化",
                "管理员已经存在，不能再次执行初始化。",
                false);
    }
}

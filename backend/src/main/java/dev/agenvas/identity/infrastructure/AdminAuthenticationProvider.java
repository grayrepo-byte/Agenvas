package dev.agenvas.identity.infrastructure;

import dev.agenvas.identity.application.AdminAccountRepository;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import java.util.List;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 验证活动管理员凭据，并只把身份主体及固定角色写入 Spring Security 会话。 */
@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {

    /** 首版唯一管理员角色；不会从用户请求或数据库自由文本推导权限。 */
    private static final List<SimpleGrantedAuthority> ADMIN_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));

    /** 查询按规范化登录名定位的活动管理员账户。 */
    private final AdminAccountRepository accounts;
    /** 比较提交密码与账户中保存的强哈希。 */
    private final PasswordEncoder passwordEncoder;

    /** 注入账户查询和密码验证器。 */
    public AdminAuthenticationProvider(
            AdminAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    /** 校验登录名和密码后创建不含密码哈希的认证令牌。 */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String loginName = IdentityService.normalizeLoginName(authentication.getName());
        String password = String.valueOf(authentication.getCredentials());
        return accounts.findActiveByLoginName(loginName)
                .filter(account -> passwordEncoder.matches(password, account.passwordHash()))
                .<Authentication>map(account -> UsernamePasswordAuthenticationToken.authenticated(
                        new AdminPrincipal(account.id(), account.loginName()), null, ADMIN_AUTHORITIES))
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    }

    /** 仅处理 Spring Security 的用户名密码令牌。 */
    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

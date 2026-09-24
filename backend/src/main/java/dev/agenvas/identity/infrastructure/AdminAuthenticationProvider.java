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

/** Authenticates the single administrator without storing its password hash in the session. */
@Component
public class AdminAuthenticationProvider implements AuthenticationProvider {

    private static final List<SimpleGrantedAuthority> ADMIN_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));

    private final AdminAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthenticationProvider(
            AdminAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

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

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

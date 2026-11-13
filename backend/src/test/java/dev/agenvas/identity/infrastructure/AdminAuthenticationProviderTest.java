package dev.agenvas.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.identity.application.AdminAccount;
import dev.agenvas.identity.application.AdminAccountRepository;
import dev.agenvas.identity.application.AdminPrincipal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/** Real password hashing ensures overlong input never authenticates through BCrypt truncation. */
class AdminAuthenticationProviderTest {
    private static Stream<String> maximumPasswords() {
        return Stream.of("a".repeat(72), "猫".repeat(24), "🔐".repeat(18), "a".repeat(69) + "猫");
    }

    @ParameterizedTest
    @MethodSource("maximumPasswords")
    void rejectsAnExtraByteAfterAnOtherwiseCorrectPassword(String password) {
        var provider = provider(password);
        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("admin", password + "x")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @ParameterizedTest
    @MethodSource("maximumPasswords")
    void acceptsTheExactMaximumPasswordWithoutRetainingCredentials(String password) {
        var authenticated = provider(password).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(" ADMIN ", password));
        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getPrincipal()).isInstanceOf(AdminPrincipal.class);
        assertThat(authenticated.getCredentials()).isNull();
    }

    @Test
    void preservesFullLengthVerificationForAnExistingNonBcryptHash() {
        String password = "legacy-full-length-" + "a".repeat(64);
        var encoder = org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
                .defaultsForSpringSecurity_v5_8();
        var provider = providerHash("{pbkdf2@SpringSecurity_v5_8}" + encoder.encode(password));
        assertThat(provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("admin", password))
                .isAuthenticated()).isTrue();
        assertThatThrownBy(() -> provider
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("admin", password + "x")))
                .isInstanceOf(BadCredentialsException.class);
    }

    private AdminAuthenticationProvider provider(String password) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return providerHash(encoder.encode(password));
    }

    private AdminAuthenticationProvider providerHash(String hash) {
        var accounts = mock(AdminAccountRepository.class);
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        when(accounts.findActiveByLoginName("admin")).thenReturn(Optional.of(new AdminAccount(
                UUID.randomUUID(), "admin", hash, AdminAccount.Status.ACTIVE, now, now, 0)));
        return new AdminAuthenticationProvider(accounts, encoder);
    }
}

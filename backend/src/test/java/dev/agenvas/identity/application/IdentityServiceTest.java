package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class IdentityServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);

    private final AdminAccountRepository accounts = mock(AdminAccountRepository.class);
    private final PasswordEncoder passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final IdentityService service = new IdentityService(
            accounts, passwords, CLOCK);

    @Test
    void createsOnlyAHashedNormalizedAdministrator() {
        when(accounts.isSetupCompleted()).thenReturn(false);
        when(accounts.completeSetup(CLOCK.instant())).thenReturn(true);

        AdminPrincipal principal = service.setup(" Admin ", "a-secure-password");

        assertThat(principal.loginName()).isEqualTo("admin");
        verify(accounts).createAdmin(
                any(UUID.class),
                org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.argThat(hash -> passwords.matches("a-secure-password", hash)),
                org.mockito.ArgumentMatchers.eq(CLOCK.instant()));
        verify(accounts).completeSetup(CLOCK.instant());
    }

    @Test
    void rejectsAnAlreadyCompletedInstallationBeforeWriting() {
        when(accounts.isSetupCompleted()).thenReturn(true);
        assertThatThrownBy(() -> service.setup("admin", "a-secure-password"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("SETUP_ALREADY_COMPLETED");

        verify(accounts, never()).createAdmin(any(), anyString(), anyString(), any());
        verify(accounts, never()).completeSetup(any());
    }

    @Test
    void doesNotReportSuccessIfCompletionCannotBeRecorded() {
        when(accounts.completeSetup(CLOCK.instant())).thenReturn(false);
        assertThatThrownBy(() -> service.setup("admin", "a-secure-password"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("SETUP_ALREADY_COMPLETED");
    }

    @Test
    void invalidAccountInputDoesNotCompleteSetup() {
        assertThatThrownBy(() -> service.setup("admin", "short"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("VALIDATION_ERROR");
        verify(accounts, never()).completeSetup(any());
    }

    @Test
    void updatesPasswordWithTheObservedAccountVersion() {
        UUID userId = UUID.randomUUID();
        String oldHash = passwords.encode("the-current-password");
        AdminAccount account = new AdminAccount(
                userId,
                "admin",
                oldHash,
                AdminAccount.Status.ACTIVE,
                CLOCK.instant(),
                CLOCK.instant(),
                7);
        when(accounts.findActiveByLoginName("admin")).thenReturn(Optional.of(account));
        when(accounts.updatePassword(any(), org.mockito.ArgumentMatchers.eq(7L), anyString(), any()))
                .thenReturn(true);

        service.changePassword(
                new AdminPrincipal(userId, "admin"),
                "the-current-password",
                "a-new-secure-password");

        verify(accounts).updatePassword(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.argThat(hash -> passwords.matches("a-new-secure-password", hash)),
                org.mockito.ArgumentMatchers.eq(CLOCK.instant()));
    }
}

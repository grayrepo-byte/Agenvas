package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SetupStatusServiceTest {

    @Test
    void requiresSetupWhenNoActiveAdministratorExists() {
        AdminAccountRepository repository = mock(AdminAccountRepository.class);
        when(repository.hasAdminAccount()).thenReturn(false);
        SetupStatusService service = new SetupStatusService(repository);

        assertThat(service.isSetupRequired()).isTrue();
    }

    @Test
    void reportsSetupCompleteWhenAnActiveAdministratorExists() {
        AdminAccountRepository repository = mock(AdminAccountRepository.class);
        when(repository.hasAdminAccount()).thenReturn(true);
        SetupStatusService service = new SetupStatusService(repository);

        assertThat(service.isSetupRequired()).isFalse();
    }
}

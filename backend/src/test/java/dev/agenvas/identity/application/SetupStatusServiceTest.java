package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SetupStatusServiceTest {

    @Test
    void requiresSetupWhenInstallationHasNeverBeenInitialized() {
        AdminAccountRepository repository = mock(AdminAccountRepository.class);
        when(repository.isSetupCompleted()).thenReturn(false);
        SetupStatusService service = new SetupStatusService(repository);

        assertThat(service.isSetupRequired()).isTrue();
    }

    @Test
    void reportsSetupCompleteAfterPermanentInitialization() {
        AdminAccountRepository repository = mock(AdminAccountRepository.class);
        when(repository.isSetupCompleted()).thenReturn(true);
        SetupStatusService service = new SetupStatusService(repository);

        assertThat(service.isSetupRequired()).isFalse();
    }
}

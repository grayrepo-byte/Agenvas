package dev.agenvas.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SetupStatusServiceTest {

    @Test
    void requiresSetupWhenNoActiveAdministratorExists() {
        SetupStatusService service = new SetupStatusService(() -> false);

        assertThat(service.isSetupRequired()).isTrue();
    }

    @Test
    void reportsSetupCompleteWhenAnActiveAdministratorExists() {
        SetupStatusService service = new SetupStatusService(() -> true);

        assertThat(service.isSetupRequired()).isFalse();
    }
}

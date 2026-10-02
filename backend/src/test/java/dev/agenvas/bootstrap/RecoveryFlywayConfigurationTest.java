package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;

/** Recovery mode cannot masquerade as a usable installation on an empty database. */
class RecoveryFlywayConfigurationTest {

    @Test
    void emptyDatabaseBackupIsRejectedWithoutMigratingIt() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);

        assertThatThrownBy(() -> new RecoveryFlywayConfiguration()
                .recoveryFlywayMigrationStrategy().migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("existing migrated database backup");
        verify(flyway, never()).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    void migratedDatabaseIsInspectedWithoutMigratingOrRepairingIt() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(mock(MigrationInfo.class));

        new RecoveryFlywayConfiguration().recoveryFlywayMigrationStrategy().migrate(flyway);

        verify(info).current();
        verify(flyway, never()).migrate();
        verify(flyway, never()).repair();
    }
}

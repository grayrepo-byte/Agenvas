package dev.agenvas.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
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
    }
}

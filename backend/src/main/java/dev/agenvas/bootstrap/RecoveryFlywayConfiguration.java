package dev.agenvas.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps a restored database at its backed-up schema version until migration is approved. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "true")
public class RecoveryFlywayConfiguration {

    /** Flyway remains inspectable but startup must not apply any pending migration. */
    @Bean
    FlywayMigrationStrategy recoveryFlywayMigrationStrategy() {
        return flyway -> {
            if (flyway.info().current() == null) {
                throw new IllegalStateException(
                        "Recovery mode requires an existing migrated database backup");
            }
            // No migration or repair: a restored snapshot may have unseen external requests.
        };
    }
}

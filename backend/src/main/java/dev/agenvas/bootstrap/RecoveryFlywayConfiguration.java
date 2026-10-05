package dev.agenvas.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 恢复备份后保持原数据库结构版本，直到运维批准执行迁移。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode", havingValue = "true")
public class RecoveryFlywayConfiguration {

    /** 仍可检查 Flyway 状态，但启动时不执行待应用迁移。 */
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

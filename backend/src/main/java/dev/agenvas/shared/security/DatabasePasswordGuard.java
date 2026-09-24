package dev.agenvas.shared.security;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/** Refuses published development database passwords before an installation becomes ready. */
@Component
public class DatabasePasswordGuard {

    /** Never includes the configured password in validation errors or logs. */
    public DatabasePasswordGuard(DataSourceProperties dataSource) {
        String password = dataSource.getPassword();
        if ("local-development-only".equals(password)
                || "replace-with-a-random-local-password".equals(password)) {
            throw new IllegalArgumentException(
                    "Database password must be deployment-specific");
        }
    }
}

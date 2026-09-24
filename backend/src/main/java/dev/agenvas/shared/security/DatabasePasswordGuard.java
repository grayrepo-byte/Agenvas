package dev.agenvas.shared.security;

import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/** 安装就绪前拒绝使用已公开的开发数据库密码。 */
@Component
public class DatabasePasswordGuard {

    /** 校验错误和日志均不包含配置的数据库密码。 */
    public DatabasePasswordGuard(DataSourceProperties dataSource) {
        String password = dataSource.getPassword();
        if ("local-development-only".equals(password)
                || "replace-with-a-random-local-password".equals(password)) {
            throw new IllegalArgumentException(
                    "Database password must be deployment-specific");
        }
    }
}

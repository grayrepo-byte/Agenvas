package dev.agenvas.shared.persistence;

import org.jooq.conf.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * jOOQ 默认把 java.time 类型编码成字符串再绑定，以兼容不支持原生 java.time 的旧 JDBC 驱动。
 * pgjdbc 支持原生类型，而字符串绑定会让 `timestamptz 列 与 ? 比较` 在 PostgreSQL 上直接失败：
 * {@code operator does not exist: timestamp with time zone &lt; character varying}。
 * 生成代码的类型化字段不受影响（jOOQ 按列类型解析并渲染成 cast(? as timestamptz)），
 * 只有纯 SQL 的绑定值会中招；开启原生绑定后两条路径行为一致。
 */
@Configuration
public class JooqSettingsConfiguration {

    /** 覆盖 jOOQ 默认设置：OffsetDateTime 交给 JDBC 驱动原生绑定。 */
    @Bean
    Settings jooqSettings() {
        return new Settings().withBindOffsetDateTimeType(true);
    }
}

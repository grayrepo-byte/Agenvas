package dev.agenvas.settings.application;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tests and embedded contexts get isolated buffers without replacing JVM-wide streams. */
@Configuration(proxyBeanMethods = false)
public class SystemLogConfiguration {
    @Bean
    @ConditionalOnMissingBean(SystemLogBuffer.class)
    SystemLogBuffer systemLogBuffer(Clock clock) { return new SystemLogBuffer(clock); }
}

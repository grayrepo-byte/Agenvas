package dev.agenvas.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 数据库和素材备份核对期间，不装配定时任务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfiguration {}

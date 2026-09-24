package dev.agenvas.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling itself is absent while auditing an older database and asset backup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agenvas", name = "recovery-mode",
        havingValue = "false", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfiguration {}

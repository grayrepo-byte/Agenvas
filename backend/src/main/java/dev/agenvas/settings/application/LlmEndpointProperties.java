package dev.agenvas.settings.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Loopback HTTP is an explicit local-development exception, never a private-network wildcard. */
@ConfigurationProperties(prefix = "agenvas.settings.llm")
public record LlmEndpointProperties(boolean allowLoopbackHttp) {}

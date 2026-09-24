package dev.agenvas.settings.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment secret is separate from the database that contains encrypted Provider keys. */
@ConfigurationProperties(prefix = "agenvas.credentials")
public record CredentialProperties(String masterKeyBase64, int keyVersion,
        String previousKeys) {}

package dev.agenvas.mediatemplate.application;

/** Persistent administrator consent and the deployment's independent scheduling limit. */
public record ThirdPartyPromptSyncSettings(boolean enabled, boolean deploymentEnabled, long version) {}

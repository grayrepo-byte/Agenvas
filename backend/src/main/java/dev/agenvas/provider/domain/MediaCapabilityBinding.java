package dev.agenvas.provider.domain;

import java.util.UUID;

/** Immutable provider identity pinned by a planned media step and every attempt. */
public record MediaCapabilityBinding(UUID connectionId, int connectionVersion,
        UUID capabilityId, int capabilityVersion, String adapterId, String mappingSha256) {}

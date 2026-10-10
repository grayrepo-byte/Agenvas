package dev.agenvas.mediatemplate.application;

import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import java.time.Instant;

public record ThirdPartyPromptSource(String id, String name, TargetKind targetKind, Format format,
        String url, String model, boolean enabled, long version, Instant nextSyncAt,
        Instant lastSyncedAt, String lastError, long promptCount, boolean syncing) {}

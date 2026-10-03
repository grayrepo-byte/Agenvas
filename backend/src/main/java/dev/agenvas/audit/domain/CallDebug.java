package dev.agenvas.audit.domain;

import dev.agenvas.shared.http.DebugHttpCapture.Exchange;
import java.util.List;
import java.util.UUID;

public record CallDebug(UUID id, boolean captured, List<Exchange> exchanges, LlmStreamLog llmStream) {}

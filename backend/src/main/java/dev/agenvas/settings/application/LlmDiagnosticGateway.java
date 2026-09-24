package dev.agenvas.settings.application;

import dev.agenvas.llm.application.ChatGateway;

/** Opens only the administrator's selected version for a synthetic capability probe. */
public interface LlmDiagnosticGateway {

    /** The caller must never pass user-authored prompts or business tools through this probe. */
    ChatGateway open(LlmProviderConfig config);
}

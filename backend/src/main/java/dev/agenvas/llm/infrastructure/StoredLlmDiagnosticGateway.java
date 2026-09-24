package dev.agenvas.llm.infrastructure;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.settings.application.LlmDiagnosticGateway;
import dev.agenvas.settings.application.LlmProviderConfig;
import org.springframework.stereotype.Component;

/** Uses the same pinned and guarded Spring AI model constructor as normal Run dispatch. */
@Component
public class StoredLlmDiagnosticGateway implements LlmDiagnosticGateway {

    private final StoredChatModelFactory factory;

    public StoredLlmDiagnosticGateway(StoredChatModelFactory factory) {
        this.factory = factory;
    }

    @Override
    public ChatGateway open(LlmProviderConfig config) {
        return factory.create(config);
    }
}

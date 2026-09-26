package dev.agenvas.llm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.LlmProperties;
import dev.agenvas.settings.application.LlmProviderConfig;
import dev.agenvas.settings.application.LlmProviderConfigRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.ai.chat.model.ChatModel;

class ConfiguredChatGatewayAuditTest {
    @Test void reportsRunPinnedModelEvenAfterTheActiveConfigurationChanges() {
        LlmProviderConfigRepository configs = mock(LlmProviderConfigRepository.class);
        StoredChatModelFactory factory = mock(StoredChatModelFactory.class);
        when(configs.active()).thenReturn(Optional.of(config(2, "new-model")));
        when(configs.findVersion(1)).thenReturn(Optional.of(config(1, "original-model")));
        var models = new DefaultListableBeanFactory().getBeanProvider(ChatModel.class);
        var gateway = new ConfiguredChatGateway(models, new LlmProperties(1, true), configs, factory);
        assertThat(gateway.modelDetails().modelId()).isEqualTo("new-model");
        assertThat(gateway.modelDetailsFor(new ChatGateway.ConfigIdentity("stored", 1)).modelId())
                .isEqualTo("original-model");
        verifyNoInteractions(factory);
    }

    private static LlmProviderConfig config(int version, String model) {
        return new LlmProviderConfig(UUID.randomUUID(), version, "https://private.invalid", model,
                new byte[0], new byte[0], 1, "masked", true, version == 2, Instant.EPOCH);
    }
}

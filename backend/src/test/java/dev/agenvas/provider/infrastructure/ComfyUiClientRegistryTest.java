package dev.agenvas.provider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.agenvas.plan.application.PlanProviderProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Restored-backup inspection must not migrate or register a new media origin. */
class ComfyUiClientRegistryTest {

    @Test
    void recoveryModeNeverWritesOrReadsHistoricalConfig() {
        JdbcClient jdbc = mock(JdbcClient.class);
        ObjectMapper mapper = new ObjectMapper();
        ComfyUiProperties properties = new ComfyUiProperties("http://127.0.0.1:8188");
        ComfyUiClient client = new ComfyUiClient(properties, mapper);
        ComfyUiClientRegistry registry = new ComfyUiClientRegistry(jdbc, mapper,
                new PlanProviderProperties("comfyui", 1), properties, client, true);

        registry.run(null);
        assertThat(registry.forOriginal(1, client.originSha256())).isEmpty();
        verifyNoInteractions(jdbc);
    }
}

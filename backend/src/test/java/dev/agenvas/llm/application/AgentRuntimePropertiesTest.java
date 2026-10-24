package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AgentRuntimePropertiesTest {
    @Test void bindingDefaultsUseOneMillionTokensAndAHalfWindowTrigger() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("agenvas.llm.agent", AgentRuntimeProperties.class);
        assertThat(properties).isEqualTo(AgentRuntimeProperties.defaults());
        assertThat(properties.contextWindowTokens()).isEqualTo(1_000_000);
        assertThat(properties.compactionTriggerTokens()).isEqualTo(500_000);
    }

    @Test void triggerPercentageCanBeConfiguredIndependentlyOfResponseReserve() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "agenvas.llm.agent.context-window-tokens", "65536",
                "agenvas.llm.agent.compaction-threshold-percent", "75")))
                .bindOrCreate("agenvas.llm.agent", AgentRuntimeProperties.class);
        assertThat(properties.compactionTriggerTokens()).isEqualTo(49_152);
        assertThat(properties.inputBudget()).isEqualTo(57_344);
    }

    @ParameterizedTest @ValueSource(ints = {0, 101})
    void invalidPercentagesAreRejected(int percent) {
        assertThatThrownBy(() -> new AgentRuntimeProperties(1_000_000, percent, 8192, 2048, 4,
                Duration.ofHours(24), 288, Duration.ofMinutes(5), 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void responseReserveStillBoundsAConfiguredFullWindowTrigger() {
        var properties = new AgentRuntimeProperties(8192, 100, 1024, 512, 2,
                Duration.ofHours(24), 288, Duration.ofMinutes(5), 4);
        assertThat(properties.compactionTriggerTokens()).isEqualTo(properties.inputBudget()).isEqualTo(7168);
    }
}

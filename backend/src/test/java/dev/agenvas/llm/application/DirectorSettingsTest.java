package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectorSettingsTest {
    @Test void defaultsUseOneMillionTokensAndAHalfWindowTrigger() {
        var settings = DirectorSettings.defaults();
        assertThat(settings.contextWindowTokens()).isEqualTo(1_000_000);
        assertThat(settings.compactionTriggerTokens()).isEqualTo(500_000);
        assertThat(settings.recoveryWindow()).isEqualTo(java.time.Duration.ofHours(24));
    }

    @Test void triggerPercentageIsIndependentOfResponseReserve() {
        var settings = new DirectorSettings(65536, 75, 8192, 2048, 4, 1440, 288, 300, 4, 1);
        assertThat(settings.compactionTriggerTokens()).isEqualTo(49_152);
        assertThat(settings.inputBudget()).isEqualTo(57_344);
    }

    @ParameterizedTest @ValueSource(ints = {0, 101})
    void invalidPercentagesAreRejected(int percent) {
        assertThatThrownBy(() -> new DirectorSettings(1_000_000, percent, 8192, 2048, 4,
                1440, 288, 300, 4, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void responseReserveStillBoundsAConfiguredFullWindowTrigger() {
        var properties = new DirectorSettings(8192, 100, 1024, 512, 2,
                1440, 288, 300, 4, 1);
        assertThat(properties.compactionTriggerTokens()).isEqualTo(properties.inputBudget()).isEqualTo(7168);
    }
}

package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ProviderModerationTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MODERATION_BLOCKED", "synthetic-private-code", "https://example.invalid/private"})
    void rejectsUnrecognizedCodesWithoutIncludingTheValueInAnException(String code) {
        assertThatThrownBy(() -> new ProviderModeration(code, "input", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid public moderation code");
    }

    @Test
    void normalizesUnknownDiagnosticsAndMakesTheBoundedCategorySnapshotImmutable() {
        var upstream = new ArrayList<>(Arrays.asList("harassment", "synthetic-private-value", "sexual", null,
                "self-harm", "violence", "other", "sexual"));
        var moderation = new ProviderModeration("moderation_blocked", "synthetic-private-stage", upstream);
        upstream.clear();

        assertThat(moderation.stage()).isEqualTo("unknown");
        assertThat(moderation.categories()).containsExactly("harassment", "other", "sexual", "self-harm", "violence");
        assertThatThrownBy(() -> moderation.categories().add("sexual"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ObjectMapper().writeValueAsString(moderation)).doesNotContain("synthetic-private");
    }

    @Test
    void missingDetailsHaveNoInventedCategoryOrStage() {
        var moderation = new ProviderModeration("content_policy_violation", null, null);

        assertThat(moderation.stage()).isEqualTo("unknown");
        assertThat(moderation.categories()).isEmpty();
    }
}

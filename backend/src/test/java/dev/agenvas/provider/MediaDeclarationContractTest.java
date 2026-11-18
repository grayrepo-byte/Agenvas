package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.provider.domain.MediaAdapterRegistry.Declaration;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.task.domain.Task;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MediaDeclarationContractTest {
    private Declaration video(int minimum, int maximum, Set<String> modes, String defaultMode) {
        return new Declaration(MediaPlatform.MOCK, Task.Kind.VIDEO_GENERATION, minimum, maximum, false, 4,
                modes, defaultMode, false, Set.of(), Set.of(), Set.of(), false, false, 0, 0);
    }
    @Test
    void callerCannotChangePublishedInputModesAfterRegistration() {
        var modes = new HashSet<>(Set.of("TEXT"));
        var declaration = video(1, 30, modes, "TEXT");
        modes.add("GENERAL_REFERENCE");
        assertThat(declaration.supportedVideoInputModes()).containsExactly("TEXT");
        assertThatThrownBy(() -> declaration.supportedVideoInputModes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test
    void contradictoryDurationOrDefaultModeCannotBecomeAPublishedContract() {
        assertThatThrownBy(() -> video(30, 1, Set.of("TEXT"), "TEXT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> video(1, 30, Set.of("TEXT"), "START_END"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

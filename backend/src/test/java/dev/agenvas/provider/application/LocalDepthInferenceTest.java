package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDepthInferenceTest {
    @Test
    void shutdownBeforeLazyLoadDisablesBothAvailabilityAndInference(@TempDir Path directory) throws Exception {
        Path file = Files.createFile(directory.resolve("model.onnx"));
        var inference = new LocalDepthInference(new LocalImageProperties(file.toString()));
        assertThat(inference.available()).isTrue();

        inference.close();
        inference.close();

        assertThat(inference.available()).isFalse();
        assertThatThrownBy(() -> inference.depth(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }
}

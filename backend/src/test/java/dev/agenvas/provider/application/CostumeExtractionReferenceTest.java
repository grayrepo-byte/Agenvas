package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CostumeExtractionReferenceTest {
    @Test
    void immutableVersionRejectsTamperedHashesAndCannotLeakMutableBytes() throws Exception {
        var mapper = new ObjectMapper();
        var descriptor = CostumeExtractionReference.snapshot(mapper);
        byte[] bytes = CostumeExtractionReference.read(descriptor);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertThat(image.getWidth() * 3).isEqualTo(image.getHeight() * 2);
        bytes[0] = 0;
        assertThat(CostumeExtractionReference.read(descriptor)[0]).isEqualTo((byte) 0x89);
        assertThatThrownBy(() -> CostumeExtractionReference.read(descriptor.deepCopy().put("sha256", "0".repeat(64))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CostumeExtractionReference.read(descriptor.deepCopy().put("id", "../../synthetic")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

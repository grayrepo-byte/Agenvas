package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KlingProtocolTest {
    @Test void mapsOnlyTheDeclaredVideoResolutionTiers() {
        assertThat(KlingProtocol.resolution(null)).isEqualTo("1080p");
        assertThat(KlingProtocol.providerResolution("2160p")).isEqualTo("4k");
        assertThat(KlingProtocol.providerResolution("720p")).isEqualTo("720p");
        assertThatThrownBy(() -> KlingProtocol.resolution("1440p")).isInstanceOf(ApiProblemException.class);
    }

    @Test void usesDifferentOfficialPromptLimitsAndRejectsBlankText() {
        KlingProtocol.validatePrompt("字".repeat(2_500), false);
        KlingProtocol.validatePrompt("字".repeat(3_072), true);
        assertThatThrownBy(() -> KlingProtocol.validatePrompt("字".repeat(2_501), false)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingProtocol.validatePrompt("字".repeat(3_073), true)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingProtocol.validatePrompt(" ", true)).isInstanceOf(ApiProblemException.class);
    }

    @Test void validatesFormatSizeDimensionsAndImageOnlyReferences() {
        KlingProtocol.validateMetadata(asset("image/png", 10_000_000, 300, 750), false);
        KlingProtocol.validateMetadata(asset("image/jpeg", 50_000_000, 750, 300), true);
        for (Asset invalid : java.util.List.of(asset("image/webp", 100, 300, 300),
                asset("image/png", 10_000_001, 300, 300), asset("image/png", 100, 299, 300),
                asset("image/png", 100, 300, 751), asset("image/png", 0, 300, 300))) {
            assertThatThrownBy(() -> KlingProtocol.validateMetadata(invalid)).isInstanceOf(ApiProblemException.class);
        }
    }

    private Asset asset(String mime, long bytes, int width, int height) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.IMAGE, "synthetic", mime,
                bytes, "a".repeat(64), width, height, null, null, null, null, Instant.now());
    }
}

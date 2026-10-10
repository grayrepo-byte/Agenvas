package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MiniMaxH3ProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaAdapterRegistry.Declaration declaration = new MediaAdapterRegistry(List.of()).declaration("MINIMAX_H3");

    @Test void declaresAllInputModesAndNormalizesResolutionDefaultsAndPrices() {
        assertThat(declaration.platform()).isEqualTo(MediaPlatform.MINIMAX);
        assertThat(declaration.supportedVideoInputModes()).containsExactlyInAnyOrder("TEXT", "START_END", "GENERAL_REFERENCE");
        assertThat(declaration.supportsEndFrame()).isTrue();
        assertThat(declaration.maxReferenceAudios()).isEqualTo(3);
        assertThat(declaration.maxReferenceVideos()).isEqualTo(3);
        var target = mapper.createObjectNode();
        MediaCapabilityConfiguration.normalize(mapper, declaration, mapper.readTree("""
                {"defaultParameters":{"aspectRatio":"AUTO","videoResolution":"1440p"},
                 "minimumSeconds":5,"maximumSeconds":12,"defaultDurationSeconds":6,
                 "pricingByResolution":{"1440p":{"amount":"0.30","currency":"CNY","unit":"SECOND"}}}
                """), target);
        assertThat(target.at("/defaultParameters/videoResolution").asText()).isEqualTo("1440p");
        assertThat(MediaCapabilityConfiguration.price(target, "1440p").path("amount").asText()).isEqualTo("0.30");
        assertThat(MediaCapabilityConfiguration.price(target, "768p")).isNull();
        assertThatThrownBy(() -> MediaCapabilityConfiguration.normalize(mapper, declaration,
                mapper.readTree("{\"defaultParameters\":{\"videoResolution\":\"480p\"}}"), mapper.createObjectNode()))
                .isInstanceOf(ApiProblemException.class);
    }
    @Test void validatesImageAudioVideoBoundsWithoutSeedancePixelMinimums() {
        MiniMaxH3Protocol.validateMetadata(asset(Asset.MediaKind.IMAGE, "image/png", 30_000_000L, 256, 256, null));
        MiniMaxH3Protocol.validateMetadata(asset(Asset.MediaKind.VIDEO, "video/mp4", 50_000_000L, 256, 256, 2000));
        MiniMaxH3Protocol.validateMetadata(asset(Asset.MediaKind.AUDIO, "audio/mpeg", 15_000_000L, null, null, 15000));
        for (Asset invalid : List.of(
                asset(Asset.MediaKind.IMAGE, "image/png", 30_000_000L + 1, 256, 256, null),
                asset(Asset.MediaKind.IMAGE, "image/png", 1, 255, 256, null),
                asset(Asset.MediaKind.IMAGE, "image/png", 1, 5760, 256, null),
                asset(Asset.MediaKind.VIDEO, "video/mp4", 50_000_000L + 1, 256, 256, 4000),
                asset(Asset.MediaKind.AUDIO, "audio/flac", 1, null, null, 4000),
                asset(Asset.MediaKind.AUDIO, "audio/wav", 1, null, null, 1999),
                asset(Asset.MediaKind.AUDIO, "audio/wav", 1, null, null, 15001))) {
            assertThatThrownBy(() -> MiniMaxH3Protocol.validateMetadata(invalid)).isInstanceOf(ApiProblemException.class);
        }
    }
    private static Asset asset(Asset.MediaKind kind, String mime, long bytes, Integer width, Integer height, Integer duration) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), kind, "synthetic", mime, bytes, "a".repeat(64),
                width, height, duration, null, null, null, Instant.now());
    }
}

package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderResultManifestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void oldManifestsWithoutProviderMetadataKeepItAbsent() {
        var manifest = mapper.readValue("""
                {"schemaVersion":1,"results":[{"ordinal":0,"nodeId":"1","kind":"VIDEO","primary":true,"url":"https://cdn.example.com/video.mp4"}],"usage":{}}
                """, ProviderResultManifest.class);

        assertThat(manifest.results().getFirst().providerMetadata()).isNull();
        assertThat(manifest.results().getFirst().archiveEntry()).isNull();
        assertThat(manifest.usage()).isEqualTo(mapper.createObjectNode());
    }

    @Test void explicitJsonNullOptionalFieldsKeepTheirAbsentMeaning() {
        var manifest = mapper.readValue("""
                {"schemaVersion":1,"results":[{"ordinal":0,"nodeId":"1","kind":"VIDEO","primary":true,"url":"https://cdn.example.com/video.mp4","providerMetadata":null}],"usage":null}
                """, ProviderResultManifest.class);

        assertThat(manifest.results().getFirst().providerMetadata()).isNull();
        assertThat(manifest.usage()).isNull();
    }

    @Test void absentProviderMetadataSurvivesPersistenceRoundTrip() {
        var result = new ProviderResultManifest.Result(0, "1", RunningHubDefinition.OutputKind.VIDEO, true,
                "https://cdn.example.com/result.zip", new ProviderResultManifest.ArchiveEntry("video.mp4", "a".repeat(64)));
        var manifest = new ProviderResultManifest(1, List.of(result), mapper.createObjectNode());

        assertThat(roundTrip(manifest)).isEqualTo(manifest);
    }

    @Test void absentUsageSurvivesPersistenceRoundTrip() {
        var result = new ProviderResultManifest.Result(0, "1", RunningHubDefinition.OutputKind.IMAGE, true,
                "https://cdn.example.com/image.png", null, mapper.readTree("{\"z_index\":0}"));
        var manifest = new ProviderResultManifest(1, List.of(result), null);

        assertThat(roundTrip(manifest)).isEqualTo(manifest);
    }

    @Test void providerMetadataAndUnknownUsageValuesSurvivePersistenceRoundTrip() {
        var metadata = mapper.readTree("{\"z_index\":0,\"bounding_box\":{\"absolute\":[10,20,42,52]},\"label\":null}");
        var usage = mapper.readTree("{\"consumeMoney\":null,\"consumeCoins\":3}");
        var result = new ProviderResultManifest.Result(0, "1", RunningHubDefinition.OutputKind.IMAGE, true,
                "https://cdn.example.com/image.png", null, metadata);
        var manifest = new ProviderResultManifest(1, List.of(result), usage);

        var persisted = roundTrip(manifest);
        assertThat(persisted).isEqualTo(manifest);
        assertThat(persisted.results().getFirst().providerMetadata().get("label").isNull()).isTrue();
        assertThat(persisted.usage().get("consumeMoney").isNull()).isTrue();
    }

    private ProviderResultManifest roundTrip(ProviderResultManifest manifest) {
        return mapper.readValue(mapper.writeValueAsString(manifest), ProviderResultManifest.class);
    }
}

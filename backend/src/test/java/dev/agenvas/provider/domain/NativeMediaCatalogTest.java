package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.task.domain.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NativeMediaCatalogTest {
    private final JsonMapper mapper = new JsonMapper();
    @Test
    void explicitAssemblyPreservesProtocolAndDefaultModelOrder() {
        var protocols = new ArrayList<NativeMediaProtocol>(List.of(new WanModels(), new SeedreamModels()));
        var catalog = new NativeMediaCatalog(protocols);
        protocols.clear();
        assertThat(catalog.profiles()).hasSize(new WanModels().profiles().size() + new SeedreamModels().profiles().size());
        assertThat(catalog.resolve("WAN_IMAGE", mapper.createObjectNode()).profile())
                .isEqualTo(new WanModels().profiles().getFirst());
        assertThat(catalog.supports("GOOGLE_VEO")).isFalse();
        assertThatThrownBy(() -> catalog.profiles().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test
    void independentCatalogCanSelectAnAdditionalCompiledProtocol() {
        var catalog = new NativeMediaCatalog(List.of(new Synthetic()));
        assertThat(catalog.resolve("SYNTHETIC_IMAGE", mapper.createObjectNode()).profile().model())
                .isEqualTo("synthetic-model");
        assertThat(catalog.supports("WAN_IMAGE")).isFalse();
        assertThatThrownBy(() -> catalog.resolve("SYNTHETIC_IMAGE", mapper.createObjectNode().put("model", "other")))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test
    void ambiguousOrForeignProfileOwnershipFailsAtAssembly() {
        assertThatThrownBy(() -> new NativeMediaCatalog(List.of(new WanModels(), new WanModels())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WAN_");
        assertThatThrownBy(() -> new NativeMediaCatalog(List.of(new Synthetic() {
            public Set<String> adapterIds() { return Set.of("OTHER_IMAGE"); }
        }))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NativeMediaCatalog(List.of(new Synthetic() {
            public List<Profile> profiles() { return List.of(super.profiles().getFirst(), super.profiles().getFirst()); }
        }))).isInstanceOf(IllegalStateException.class);
    }
    private static class Synthetic implements NativeMediaProtocol {
        public Set<String> adapterIds() { return Set.of("SYNTHETIC_IMAGE"); }
        public List<Profile> profiles() {
            return List.of(new Profile("SYNTHETIC_IMAGE", "synthetic-model", "GENERATE", Task.Kind.IMAGE_GENERATION));
        }
        public List<CreatorInputField> inputs(Profile profile) { return List.of(); }
        public MediaAdapterRegistry.Declaration declaration(Profile profile) {
            return new MediaAdapterRegistry.Declaration(MediaPlatform.MOCK, Task.Kind.IMAGE_GENERATION, 0, 0,
                    false, 0, Set.of(), null, false, Set.of(), Set.of(), Set.of(), false, false, 0, 0);
        }
        public void validate(Profile profile, JsonNode values, boolean complete) {}
        public void validateMetadata(Profile profile, String slot, Asset asset, JsonNode values) {}
        public Integer resultCount(Profile profile, JsonNode values) { return 1; }
    }
}

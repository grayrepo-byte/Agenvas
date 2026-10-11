package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SeedreamModelsTest {
    private final SeedreamModels models = new SeedreamModels();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void exposesOnlyTheThreeMainModelsAndTheirSupportedOperations() {
        assertThat(models.profiles()).hasSize(7);
        assertThat(models.profiles()).extracting(NativeMediaProtocol.Profile::model)
                .containsOnly(SeedreamModels.PRO, SeedreamModels.FLASH, SeedreamModels.LITE);
        assertThatThrownBy(() -> models.resolve(SeedreamModels.LITE, SeedreamModels.LAYERS)).isInstanceOf(RuntimeException.class);
        for (var p : models.profiles()) {
            assertThat(models.declaration(p).maxReferenceImages()).isEqualTo(SeedreamModels.layers(p) ? 1 : SeedreamModels.proFamily(p) ? 10 : 14);
            assertThat(models.inputs(p)).noneMatch(f -> List.of("stream", "response_format", "layers", "bbox", "coordinate").contains(f.key()));
        }
    }

    @Test void preservesNativeDefaultsAndTheGroupCountIsAnUpperBound() {
        var pro = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var settings = NativeMediaProfiles.normalizeSettings(SeedreamModels.ADAPTER_ID,
                mapper.createObjectNode().put("model", pro.model()), mapper);
        assertThat(settings.at("/defaultParameters/dynamicValues/size").asText()).isEqualTo("2K");
        assertThat(settings.at("/defaultParameters/dynamicValues/watermark").asBoolean()).isTrue();
        var lite = models.resolve(SeedreamModels.LITE, SeedreamModels.GENERATE);
        ObjectNode group = mapper.createObjectNode().put("prompt", "A synthetic sequence").put("sequential_image_generation", "auto")
                .put("max_images", 15).put("image1", UUID.randomUUID().toString());
        models.validate(lite, group, true);
        assertThat(models.resultCount(lite, group)).isNull();
        assertThat(SeedreamModels.maximumResults(lite, group)).isEqualTo(14);
        assertThat(models.resultCount(pro, mapper.createObjectNode())).isEqualTo(1);
        assertThat(models.resultCount(models.resolve(SeedreamModels.PRO, SeedreamModels.LAYERS), mapper.createObjectNode())).isNull();
    }

    @Test void validatesExactSizeRangesPerMainModelWithoutDiscardingAspectRatio() {
        var pro = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var lite = models.resolve(SeedreamModels.LITE, SeedreamModels.GENERATE);
        for (String size : List.of("1K", "1.5K", "2K", "2048x2048", "3840x240")) SeedreamModels.validateSize(pro, size);
        for (String size : List.of("2K", "3K", "4K", "4096x4096")) SeedreamModels.validateSize(lite, size);
        for (String size : List.of("4K", "4096x4096", "100x100", "+2048x2048", "2048X2048")) {
            assertThatThrownBy(() -> SeedreamModels.validateSize(pro, size)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> SeedreamModels.validateSize(lite, "1K")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SeedreamModels.validateSize(lite, "1920x1080")).isInstanceOf(RuntimeException.class);
        var layer = models.resolve(SeedreamModels.PRO, SeedreamModels.LAYERS);
        assertThat(models.resolutionTier(layer, mapper.createObjectNode())).isNull();
        assertThatThrownBy(() -> SeedreamModels.validateSize(layer, "2048x2048")).isInstanceOf(RuntimeException.class);
    }

    @Test void transparentOutputRequiresOneAlphaReferenceAndPng() {
        var pro = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        ObjectNode values = mapper.createObjectNode().put("prompt", "An object").put("background", "transparent")
                .put("output_format", "png").put("image1", UUID.randomUUID().toString());
        models.validate(pro, values, true);
        values.put("output_format", "jpeg");
        assertThatThrownBy(() -> models.validate(pro, values, true)).isInstanceOf(RuntimeException.class);
        values.put("output_format", "png");
        models.validateProbe(pro, "image1", values, mapper.readTree("{\"streams\":[{\"codec_type\":\"video\",\"pix_fmt\":\"rgba\"}]}"));
        assertThatThrownBy(() -> models.validateProbe(pro, "image1", values,
                mapper.readTree("{\"streams\":[{\"codec_type\":\"video\",\"pix_fmt\":\"rgb24\"}]}"))).isInstanceOf(RuntimeException.class);
    }

    @Test void strictEditRegionsProduceOnlyOfficialPromptMarkup() {
        var edit = models.resolve(SeedreamModels.PRO, SeedreamModels.EDIT);
        ObjectNode values = mapper.createObjectNode().put("prompt", "Edit the object").put("image1", UUID.randomUUID().toString())
                .put("edit_regions", "[{\"image\":1,\"type\":\"bbox\",\"coordinates\":[0,20,999,999],\"instruction\":\"Keep the background\"}]");
        models.validate(edit, values, true);
        assertThat(SeedreamModels.prompt(edit, values)).contains("图 1 <bbox>0 20 999 999</bbox> Keep the background");
        for (String malformed : List.of("{}", "[]", "[{\"image\":1,\"type\":\"arrow\",\"coordinates\":[0,20],\"instruction\":\"x\"}]",
                "[{\"image\":1,\"type\":\"point\",\"coordinates\":[0,1000],\"instruction\":\"x\"}]",
                "[{\"image\":1,\"image\":2,\"type\":\"point\",\"coordinates\":[0,20],\"instruction\":\"x\"}]",
                "[{\"image\":2,\"type\":\"point\",\"coordinates\":[0,20],\"instruction\":\"x\"}]",
                "[{\"image\":1,\"type\":\"point\",\"coordinates\":[0,20],\"instruction\":\"x\",\"url\":\"https://example.org\"}]")) {
            values.put("edit_regions", malformed);
            assertThatThrownBy(() -> models.validate(edit, values, true)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void partialDraftCanAssignLaterSlotsButRunningRequiresUnambiguousNumbering() {
        var pro = models.resolve(SeedreamModels.PRO, SeedreamModels.GENERATE);
        var values = mapper.createObjectNode().put("prompt", "Create").put("image2", UUID.randomUUID().toString());
        models.validate(pro, values, false);
        assertThatThrownBy(() -> models.validate(pro, values, true)).isInstanceOf(RuntimeException.class);
    }
}

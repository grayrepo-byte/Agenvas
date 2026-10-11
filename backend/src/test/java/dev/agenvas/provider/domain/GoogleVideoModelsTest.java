package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GoogleVideoModelsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GoogleVideoModels models = new GoogleVideoModels();
    @Test void publishesOnlyCurrentModelsAndCompilesOperationSpecificCapabilities() {
        assertThat(models.profiles()).hasSize(8);
        assertThat(models.profiles()).extracting(NativeMediaProtocol.Profile::model)
                .containsOnly("veo-3.1-generate-preview", "veo-3.1-fast-generate-preview", "veo-3.1-lite-generate-preview", "gemini-omni-1.1-flash");
        assertThat(models.profiles().stream().filter(p -> p.model().contains("lite"))).singleElement()
                .satisfies(p -> assertThat(models.inputs(p)).noneMatch(f -> f.key().equals("base_video") || f.key().equals("image_1")));
        assertThat(models.profiles()).allMatch(p -> models.resultCount(p, mapper.createObjectNode()) == 1);
        assertThat(models.profiles()).allMatch(p -> models.inputs(p).stream().noneMatch(f -> models.usesMediaUrl(p, f)));
        assertThat(models.profiles()).allMatch(p -> !models.declaration(p).originRequired());
    }
    @Test void keepsIncompleteDurationButChecksVeoNativeCombinationsAtAcceptance() {
        var selected = selected(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        ObjectNode values = values(selected, mapper.createObjectNode());
        values.put("durationSeconds", 60);
        NativeMediaProfiles.validateValues(selected, values, false);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(RuntimeException.class);
        values.put("durationSeconds", 4).put("resolution", "1080p");
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(NativeMediaFailure.class);
        values.put("durationSeconds", 8).put("image_3", UUID.randomUUID().toString());
        NativeMediaProfiles.validateValues(selected, values, true);
        values.put("durationSeconds", 6);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(NativeMediaFailure.class);
    }
    @Test void rejectsTailWithoutStartAndStrictTaskContradictionsButAllowsTaggedMixedReferencesInAuto() {
        var selected = selected(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        ObjectNode values = values(selected, mapper.createObjectNode().put("last_frame", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(NativeMediaFailure.class);
        values.put("first_frame", UUID.randomUUID().toString()).put("image_5", UUID.randomUUID().toString());
        NativeMediaProfiles.validateValues(selected, values, true);
        values.put("task", "text_to_video");
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(NativeMediaFailure.class);
        values.put("task", "image_to_video");
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(NativeMediaFailure.class);
        values.remove("image_5");
        NativeMediaProfiles.validateValues(selected, values, true);
    }
    @Test void editsAndExtensionsRequireOneFrozenBaseAndExposeNativeStringDuration() {
        for (String operation : List.of("EDIT", "EXTEND")) {
            var selected = selected(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, operation);
            ObjectNode values = values(selected, mapper.createObjectNode());
            assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(RuntimeException.class);
            values.put("base_video", UUID.randomUUID().toString()).put("duration", "10s");
            NativeMediaProfiles.validateValues(selected, values, true);
            assertThat(selected.inputs()).noneMatch(f -> f.effectiveSource() == RunningHubDefinition.Source.DURATION_SECONDS);
            assertThat(selected.inputs()).anyMatch(f -> f.key().equals("video_3"));
            values.put("duration", 10);
            assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void referenceAndEditingClipLimitsAreDistinctAndIncludeActualProbe() {
        var p = selected(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE").profile();
        models.validateMetadata(p, "video_1", video(3000), mapper.createObjectNode());
        assertThatThrownBy(() -> models.validateMetadata(p, "video_1", video(3001), mapper.createObjectNode())).isInstanceOf(NativeMediaFailure.class);
        models.validateMetadata(p, "base_video", video(10000), mapper.createObjectNode());
        assertThatThrownBy(() -> models.validateMetadata(p, "base_video", video(10001), mapper.createObjectNode())).isInstanceOf(NativeMediaFailure.class);
        assertThatThrownBy(() -> models.validateProbe(p, "video_1", mapper.createObjectNode(), mapper.readTree("{\"format\":{\"duration\":\"3.01\"}}")))
                .isInstanceOf(NativeMediaFailure.class);
    }
    @Test void aliasesUseActualFilledPositionAndIntegerNativeControlsStayIntegers() {
        var selected = selected(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        ObjectNode values = values(selected, mapper.createObjectNode().put("image_5", UUID.randomUUID().toString())
                .put("video_3", UUID.randomUUID().toString()));
        assertThat(models.renderMention(selected.profile(), values, "image_5")).isEqualTo("<IMAGE_REF_0>");
        assertThat(models.renderMention(selected.profile(), values, "video_3")).isEqualTo("<VIDEO_REF_0>");
        var veo = selected(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "GENERATE");
        var veoValues = values(veo, mapper.createObjectNode().put("image_3", UUID.randomUUID().toString()));
        assertThat(models.renderMention(veo.profile(), veoValues, "image_3")).isEqualTo("reference image 1");
        values.put("seed", 1.5);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(RuntimeException.class);
    }
    @Test void veoExtensionRequiresImmutableTaskProvenanceAndCannotTrustUserMetadata() {
        var veo = selected(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "EXTEND").profile();
        models.validateSource(veo, "base_video", version(ArtifactVersion.CreatedByKind.TASK));
        for (var origin : List.of(ArtifactVersion.CreatedByKind.USER, ArtifactVersion.CreatedByKind.AGENT))
            assertThatThrownBy(() -> models.validateSource(veo, "base_video", version(origin))).isInstanceOf(NativeMediaFailure.class);
        assertThatThrownBy(() -> models.validateSource(veo, "base_video", null)).isInstanceOf(NativeMediaFailure.class);
        models.validateSource(selected(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "EXTEND").profile(),
                "base_video", version(ArtifactVersion.CreatedByKind.USER));
    }
    @Test void onlyGenerationProfilesPermitReusingTheExactFirstAndLastFrameVersion() {
        for (var profile : models.profiles()) {
            if (GoogleVideoModels.GENERATE.equals(profile.operation()))
                assertThat(models.reusableSlots(profile)).containsExactlyInAnyOrder("first_frame", "last_frame");
            else assertThat(models.reusableSlots(profile)).isEmpty();
        }
    }
    private NativeMediaProfiles.Selection selected(String adapter, String model, String operation) {
        return new NativeMediaProfiles.Selection(models, new NativeMediaProtocol.Profile(adapter, model, operation, Task.Kind.VIDEO_GENERATION));
    }
    private ObjectNode values(NativeMediaProfiles.Selection selected, ObjectNode overrides) {
        return NativeMediaProfiles.effectiveValues(selected, mapper.createObjectNode().set("dynamicValues", overrides), "Synthetic landscape", null, mapper);
    }
    private Asset video(int millis) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.VIDEO, "synthetic.mp4", "video/mp4", 100,
                "a".repeat(64), 1280, 720, millis, null, null, null, Instant.now());
    }
    private ArtifactVersion version(ArtifactVersion.CreatedByKind kind) {
        // Even generated-shaped user content cannot assert the immutable database origin.
        var content = mapper.createObjectNode();
        content.putObject("parameters").putObject("nativeProviderResult").put("adapterId", GoogleVideoModels.VEO)
                .put("model", GoogleVideoModels.VEO_MODELS.getFirst()).put("connectionId", UUID.randomUUID().toString())
                .put("connectionVersion", 1).put("fileName", "files/synthetic-video");
        return new ArtifactVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1, null, null,
                content, List.of(), kind, null, Instant.now());
    }
}

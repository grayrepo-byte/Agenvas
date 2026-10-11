package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WanModelsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WanModels models = new WanModels();
    private final NativeMediaProtocol.Profile image = models.profiles().get(0), pro = models.profiles().get(1), video = models.profiles().get(2);
    @Test void catalogContainsCurrentMainlineModelsAndFiniteNativeInputs() {
        assertThat(models.profiles()).extracting(NativeMediaProtocol.Profile::model)
                .containsExactly("wan2.7-image", "wan2.7-image-pro", "wan3.0-video", "wan3.0-video-prime");
        assertThat(models.inputs(image)).extracting(CreatorInputField::key).contains("image_9", "image_9_bbox_2_y2", "color_10_ratio", "thinking_mode", "enable_sequential");
        assertThat(models.inputs(video)).extracting(CreatorInputField::key).contains("audio_5", "video_5", "image_10", "first_frame", "last_frame", "smart_duration");
        assertThat(models.inputs(video)).extracting(CreatorInputField::key).doesNotContain("link", "file", "callback_url", "external_task_id", "apiKey");
        assertThat(models.declaration(video).maxReferenceImages()).isEqualTo(10);
        assertThat(models.declaration(video).maxReferenceVideos()).isEqualTo(5);
        assertThat(models.declaration(video).maxReferenceAudios()).isEqualTo(5);
    }
    @Test void sourceDraftsSurviveSwitchesAndCompleteRequestsApplyNativePromptDurationLimits() {
        var selected = selected(video);
        var values = NativeMediaProfiles.effectiveValues(selected, mapper.createObjectNode(), "x".repeat(20_001), 60, mapper);
        assertThat(values.path("duration").asInt()).isEqualTo(60);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(ApiProblemException.class);
        var automatic = values(video).put("duration", 60).put("smart_duration", true);
        NativeMediaProfiles.validateValues(selected, automatic, true);
        var absentAutomaticControl = values(video).put("duration", 60); absentAutomaticControl.remove("smart_duration");
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, absentAutomaticControl, true)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected(image), values, true)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected(video), values(video).put("endpoint", "https://untrusted.example"), false)).isInstanceOf(ApiProblemException.class);
    }
    @Test void independentCountsAndSequentialMaximumUseDifferentBounds() {
        assertThat(models.resultCount(image, values(image).put("n", 4))).isEqualTo(4);
        assertThatThrownBy(() -> validate(image, values(image).put("n", 5))).isInstanceOf(ApiProblemException.class);
        var sequence = values(image).put("enable_sequential", true).put("n", 12);
        validate(image, sequence); assertThat(models.resultCount(image, sequence)).isNull();
        assertThatThrownBy(() -> validate(image, sequence.put("n", 13))).isInstanceOf(ApiProblemException.class);
    }
    @Test void proFourKOnlyWorksForUnreferencedNonSequentialGenerationAndCustomSizesFollowPixelArea() {
        validate(pro, values(pro).put("size", "4K"));
        validate(pro, values(pro).put("size", "4096*4096"));
        assertThatThrownBy(() -> validate(image, values(image).put("size", "4K"))).isInstanceOf(ApiProblemException.class);
        for (var invalid : java.util.List.of(values(pro).put("size", "4K").put("enable_sequential", true),
                values(pro).put("size", "4K").put("image_1", UUID.randomUUID().toString()), values(pro).put("size", "768*600"),
                values(pro).put("size", "4097*4096"), values(pro).put("size", "9000*1000")))
            assertThatThrownBy(() -> validate(pro, invalid))
                    .isInstanceOf(ApiProblemException.class);
        validate(image, values(image).put("size", "2048*1024"));
    }
    @Test void palettesRequireCompletePairsExactPercentagesAndDisableSequentialOutput() {
        var palette = values(image).put("color_1_hex", "#000000").put("color_1_ratio", 33.33)
                .put("color_2_hex", "#123456").put("color_2_ratio", 33.33).put("color_3_hex", "#FFFFFF").put("color_3_ratio", 33.34);
        validate(image, palette);
        assertThatThrownBy(() -> validate(image, palette.deepCopy().put("color_3_ratio", 33.33))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(image, palette.deepCopy().put("color_3_ratio", 33.333))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(image, palette.deepCopy().put("enable_sequential", true))).isInstanceOf(ApiProblemException.class);
        var partial = values(image).put("color_1_hex", "#FFFFFF");
        NativeMediaProfiles.validateValues(selected(image), partial, false);
        assertThatThrownBy(() -> validate(image, partial)).isInstanceOf(ApiProblemException.class);
    }
    @Test void editingBoxesMatchExactImagesAndCannotExtendBeyondTheirPixels() {
        var values = values(image).put("image_5", UUID.randomUUID().toString()).put("image_5_bbox_2_x1", 10)
                .put("image_5_bbox_2_y1", 20).put("image_5_bbox_2_x2", 240).put("image_5_bbox_2_y2", 300);
        validate(image, values);
        models.validateMetadata(image, "image_5", asset(Asset.MediaKind.IMAGE, "image/png", 1000, 300, 300, null), values);
        assertThatThrownBy(() -> models.validateMetadata(image, "image_5", asset(Asset.MediaKind.IMAGE, "image/png", 1000, 239, 300, null), values)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(image, values.deepCopy().put("image_5_bbox_2_x2", 5))).isInstanceOf(ApiProblemException.class);
        assertThat(models.renderMention(image, values, "image_5")).isEqualTo("图1");
    }
    @Test void framesExcludeAllReferenceMediaAndTailRequiresFirstAtRuntime() {
        var frame = values(video).put("first_frame", UUID.randomUUID().toString()); validate(video, frame);
        for (String slot : java.util.List.of("image_1", "video_1", "audio_1"))
            assertThatThrownBy(() -> validate(video, frame.deepCopy().put(slot, UUID.randomUUID().toString()))).isInstanceOf(ApiProblemException.class);
        var tail = values(video).put("last_frame", UUID.randomUUID().toString());
        NativeMediaProfiles.validateValues(selected(video), tail, false);
        assertThatThrownBy(() -> validate(video, tail)).isInstanceOf(ApiProblemException.class);
        validate(video, values(video).put("audio_5", UUID.randomUUID().toString()));
    }
    @Test void referenceMetadataTotalDurationsAndInputBillingAreNative() {
        var badRatio = asset(Asset.MediaKind.VIDEO, "video/mp4", 1000, 240, 4096, 8000);
        // Ratios are checked independently of the maximum side.
        assertThatThrownBy(() -> models.validateMetadata(video, "video_1", badRatio, values(video))).isInstanceOf(ApiProblemException.class);
        var one = asset(Asset.MediaKind.VIDEO, "video/mp4", 1000, 720, 1280, 8000);
        var two = asset(Asset.MediaKind.VIDEO, "video/mp4", 1000, 720, 1280, 7000);
        Map<String, Asset> inputs = Map.of("video_1", one, "video_5", two);
        models.validatePrepared(video, values(video).put("duration", 15), inputs);
        assertThat(models.billableInputSeconds(video, values(video), inputs)).isEqualByComparingTo("15.000");
        assertThatThrownBy(() -> models.validatePrepared(video, values(video).put("duration", 16), inputs)).isInstanceOf(ApiProblemException.class);
        models.validatePrepared(video, values(video).put("duration", 30).put("smart_duration", true), inputs);
        assertThatThrownBy(() -> models.validatePrepared(video, values(video), Map.of("video_1", inputs.get("video_1"),
                "video_2", asset(Asset.MediaKind.VIDEO, "video/mp4", 1000, 720, 1280, 8000)))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> models.validateProbe(video, "video_1", values(video), mapper.readTree("{\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"3\"},\"streams\":[{\"codec_type\":\"video\",\"avg_frame_rate\":\"15/1\"}]}")))
                .isInstanceOf(ApiProblemException.class);
    }
    private NativeMediaProfiles.Selection selected(NativeMediaProtocol.Profile p) { return new NativeMediaProfiles.Selection(models, p); }
    private ObjectNode values(NativeMediaProtocol.Profile p) { return NativeMediaProfiles.effectiveValues(selected(p), mapper.createObjectNode(), "A synthetic landscape", 5, mapper); }
    private void validate(NativeMediaProtocol.Profile p, ObjectNode values) { NativeMediaProfiles.validateValues(selected(p), values, true); }
    private static Asset asset(Asset.MediaKind kind, String mime, long bytes, Integer width, Integer height, Integer duration) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), kind, "synthetic", mime, bytes, "a".repeat(64), width, height, duration, null, null, null, Instant.now());
    }
}

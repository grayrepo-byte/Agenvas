package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class KlingModelsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String IMAGE = "00000000-0000-0000-0000-000000000001";
    private static final String VIDEO = "00000000-0000-0000-0000-000000000002";

    @Test void catalogContainsEveryCurrentModelAndExplicitGenerationOperation() {
        assertThat(KlingModels.profiles(Task.Kind.IMAGE_GENERATION)).hasSize(7);
        assertThat(KlingModels.profiles(Task.Kind.VIDEO_GENERATION)).hasSize(8);
        assertThat(KlingModels.profiles()).hasSize(15);
        assertThat(KlingModels.adapter("KLING_IMAGE")).isTrue();
        assertThat(KlingModels.adapter("KLING_IMAGE_3")).isFalse();
        for (var settings : List.of(mapper.createObjectNode(), mapper.createObjectNode().put("model", ""), mapper.createObjectNode().putNull("model"))) {
            assertThat(KlingModels.resolve(settings, Task.Kind.IMAGE_GENERATION).model()).isEqualTo("kling-v3");
            assertThat(KlingModels.resolve(settings, Task.Kind.VIDEO_GENERATION).model()).isEqualTo("kling-3.0");
        }
        assertThat(KlingModels.resolve(mapper.createObjectNode().put("model", " kling-v3 "), Task.Kind.IMAGE_GENERATION).model()).isEqualTo("kling-v3");
        assertThatThrownBy(() -> KlingModels.resolve(mapper.createObjectNode().put("model", "KLING-V3"), Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.resolve(mapper.createObjectNode().put("model", "unknown"), Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        assertThat(KlingModels.resolve(mapper.createObjectNode().put("operation", "EXPAND"), Task.Kind.IMAGE_GENERATION).model()).isEmpty();
        for (String model : List.of("kling-v1", "kling-v2", "kling-v3-omni")) {
            assertThatThrownBy(() -> KlingModels.resolve(model, "MOTION_CONTROL", Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        }
        assertThatThrownBy(() -> KlingModels.resolve("kling-3.0-turbo", "MOTION_CONTROL", Task.Kind.VIDEO_GENERATION)).isInstanceOf(ApiProblemException.class);
    }

    @Test void administratorDefaultsAreScalarOnlyAndCannotForwardArbitraryFields() {
        var settings = mapper.createObjectNode().put("model", "kling-v3-omni");
        settings.putObject("defaultParameters").putObject("dynamicValues").put("resolution", "4k").put("n", 9);
        var normalized = KlingModels.normalizeSettings(settings, Task.Kind.IMAGE_GENERATION, mapper);
        assertThat(normalized.path("klingInputs").isArray()).isTrue();
        assertThat(normalized.path("defaultParameters").path("dynamicValues").path("n").asInt()).isEqualTo(9);
        assertThat(normalized.path("defaultParameters").path("dynamicValues").has("prompt")).isFalse();
        assertThat(normalized.path("defaultParameters").path("dynamicValues").has("image_1")).isFalse();
        for (String key : List.of("image_1", "prompt", "duration", "callback_url", "apiKey")) {
            var invalid = settings.deepCopy();
            ((ObjectNode) invalid.path("defaultParameters").path("dynamicValues")).put(key, IMAGE);
            assertThatThrownBy(() -> KlingModels.normalizeSettings(invalid, Task.Kind.IMAGE_GENERATION, mapper)).isInstanceOf(ApiProblemException.class);
        }
        settings.put("endpoint", "https://untrusted.example");
        assertThatThrownBy(() -> KlingModels.normalizeSettings(settings, Task.Kind.IMAGE_GENERATION, mapper)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.normalizeSettings(mapper.createObjectNode().put("model", "kling-2.6").put("defaultDurationSeconds", 6),
                Task.Kind.VIDEO_GENERATION, mapper)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.normalizeSettings(mapper.createObjectNode().put("model", "kling-3.0").put("operation", "MOTION_CONTROL").put("defaultDurationSeconds", 5),
                Task.Kind.VIDEO_GENERATION, mapper)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.normalizeSettings(mapper.createObjectNode().put("model", "kling-3.0").put("operation", "MOTION_CONTROL").put("minimumSeconds", 8),
                Task.Kind.VIDEO_GENERATION, mapper)).isInstanceOf(ApiProblemException.class);
    }

    @Test void imageModelsHaveDifferentResolutionSeriesAndReferenceFields() {
        assertThat(keys(image("kling-v3"))).contains("negative_prompt", "elementId1").doesNotContain("image_fidelity", "result_type", "image_2");
        assertThat(keys(image("kling-v2-1"))).contains("image_reference", "image_fidelity", "human_fidelity").doesNotContain("elementId1");
        assertThat(keys(image("kling-image-o1"))).contains("image_10", "elementId10").doesNotContain("result_type");
        var omni = image("kling-v3-omni");
        var values = values(omni, mapper.createObjectNode().put("result_type", "series").put("series_amount", "auto"), 5);
        assertThat(KlingModels.resultCount(omni, values)).isNull();
        var n = KlingModels.inputs(omni).stream().filter(f -> "n".equals(f.key())).findFirst().orElseThrow();
        assertThat(n.enabledWhen().field()).isEqualTo("result_type");
        assertThat(n.enabledWhen().value().asText()).isEqualTo("single");
        assertThatThrownBy(() -> KlingModels.validateValues(image("kling-image-o1"), values, true)).isInstanceOf(ApiProblemException.class);
    }

    @Test void imageNegativePromptRefsBoundsAndPositionTagsAreValidated() {
        var normal = image("kling-v3");
        var negative = values(normal, mapper.createObjectNode(), 5).put("image_1", IMAGE).put("negative_prompt", "blur");
        assertThatThrownBy(() -> KlingModels.validateValues(normal, negative, true)).isInstanceOf(ApiProblemException.class);
        var omni = image("kling-v3-omni");
        var gap = values(omni, mapper.createObjectNode().put("image_5", IMAGE), 5);
        KlingModels.validateValues(omni, gap, false);
        KlingModels.validateValues(omni, gap, true);
        var over = mapper.createObjectNode();
        for (int i = 1; i <= 10; i++) over.put("elementId" + i, Integer.toString(i));
        over.put("image_1", IMAGE);
        assertThatThrownBy(() -> values(omni, over, 5)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> values(normal, mapper.createObjectNode().put("n", 10), 5)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> values(normal, mapper.createObjectNode().put("elementId1", "9223372036854775808"), 5)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> values(normal, mapper.createObjectNode().put("elementId1", "subject-generated-id"), 5)).isInstanceOf(ApiProblemException.class);
    }

    @Test void explicitSlotsMustPointAtMatchingExactVersionsAndConsumeAllRuntimeMedia() {
        var p = image("kling-v3-omni");
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("image_1", IMAGE);
        var exact = media(IMAGE, MediaDraft.InputRole.REFERENCE);
        KlingModels.validateDraft(p, parameters, "Prompt", null, List.of(exact), true);
        assertThatThrownBy(() -> KlingModels.validateDraft(p, parameters, "Prompt", null,
                List.of(media(IMAGE, MediaDraft.InputRole.VIDEO_REFERENCE)), true)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.validateDraft(p, mapper.createObjectNode(), "Prompt", null, List.of(exact), true)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) parameters.path("dynamicValues")).put("image_2", IMAGE);
        assertThatThrownBy(() -> KlingModels.validateDraft(p, parameters, "Prompt", null, List.of(exact), true)).isInstanceOf(ApiProblemException.class);
    }

    @Test void videoProfilesDoNotInheritUnsupportedAudioTailFourKOrDuration() {
        var turbo = video("kling-3.0-turbo");
        assertThat(KlingModels.inputs(turbo).stream().filter(field -> "aspect_ratio".equals(field.key())).findFirst().orElseThrow().description())
                .contains("text-to-video only", "first frame");
        assertThat(keys(turbo)).doesNotContain("audio", "multi_shot", "last_frame");
        assertThatThrownBy(() -> values(turbo, mapper.createObjectNode().put("resolution", "4k"), 5)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.validateValues(video("kling-2.6"), values(video("kling-2.6"), mapper.createObjectNode(), 6), true)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> KlingModels.validateValues(video("kling-o1"), values(video("kling-o1"), mapper.createObjectNode(), 3), true)).isInstanceOf(ApiProblemException.class);
        var tail = mapper.createObjectNode().put("first_frame", IMAGE).put("last_frame", VIDEO).put("resolution", "1080p").put("audio", "native");
        assertThatThrownBy(() -> values(video("kling-2.6"), tail, 5)).isInstanceOf(ApiProblemException.class);
        assertThat(keys(video("kling-2.5-turbo"))).doesNotContain("audio", "voiceId1");
        var longPrompt = mapper.createObjectNode(); longPrompt.putObject("dynamicValues");
        assertThatThrownBy(() -> KlingModels.validateValues(video("kling-o1"), KlingModels.effectiveValues(video("kling-o1"), longPrompt, "字".repeat(2501), 5, mapper), true)).isInstanceOf(ApiProblemException.class);
        KlingModels.effectiveValues(video("kling-3.0"), mapper.createObjectNode(), "字".repeat(3072), 5, mapper);
        var imageTurbo = mapper.createObjectNode(); imageTurbo.putObject("dynamicValues").put("first_frame", IMAGE);
        assertThatThrownBy(() -> KlingModels.validateValues(turbo, KlingModels.effectiveValues(turbo, imageTurbo, "字".repeat(2501), 5, mapper), true)).isInstanceOf(ApiProblemException.class);
    }

    @Test void sourceDraftValuesSurviveCapabilityChangesUntilCompleteValidation() {
        for (var p : List.of(video("kling-2.6"), video("kling-o1"), video("kling-3.0"))) {
            var draft = KlingModels.effectiveValues(p, mapper.createObjectNode(), "字".repeat(4000), 60, mapper);
            assertThat(draft.path("duration").asInt()).isEqualTo(60);
            assertThat(draft.path("prompt").asText()).hasSize(4000);
            assertThatThrownBy(() -> KlingModels.validateValues(p, draft, true)).isInstanceOf(ApiProblemException.class);
        }
    }

    @Test void omniCombinationsAndAliasesAreCheckedBeforeNetworkPreparation() {
        var p = video("kling-3.0-omni");
        KlingModels.validateValues(p, values(p, mapper.createObjectNode().put("elementId1", "subject-generated-id")
                .put("elementRefId1", "主角"), 5), true);
        var base = mapper.createObjectNode().put("base_video", VIDEO);
        assertThatThrownBy(() -> values(p, base, 5)).isInstanceOf(ApiProblemException.class);
        base.put("multi_shot", false).put("audio", "original");
        KlingModels.validateValues(p, values(p, base, 5), true);
        var feature = mapper.createObjectNode().put("feature_video", VIDEO).put("audio", "native");
        assertThatThrownBy(() -> values(p, feature, 5)).isInstanceOf(ApiProblemException.class);
        var alias = mapper.createObjectNode().put("first_frame", IMAGE).put("elementId1", "123").put("elementRefId1", "first_frame");
        assertThatThrownBy(() -> values(p, alias, 5)).isInstanceOf(ApiProblemException.class);
        var mixed = mapper.createObjectNode().put("feature_video", VIDEO).put("elementId1", "123").put("elementType1", "VIDEO").put("elementId2", "124");
        assertThatThrownBy(() -> values(p, mixed, 5)).isInstanceOf(ApiProblemException.class);
        var o1 = mapper.createObjectNode().put("first_frame", IMAGE).put("last_frame", VIDEO).put("image_1", UUID.randomUUID().toString());
        assertThatThrownBy(() -> values(video("kling-o1"), o1, 5)).isInstanceOf(ApiProblemException.class);
    }

    @Test void expandAndMotionUseTheirActualInputsAndBounds() {
        var expand = KlingModels.resolve("", "EXPAND", Task.Kind.IMAGE_GENERATION);
        var values = values(expand, mapper.createObjectNode().put("image_1", IMAGE).put("left_expansion_ratio", 2), 5);
        KlingModels.validateValues(expand, values, true);
        values.put("right_expansion_ratio", 0.01);
        assertThatThrownBy(() -> KlingModels.validateValues(expand, values, true)).isInstanceOf(ApiProblemException.class);
        var motion = KlingModels.resolve("kling-3.0", "MOTION_CONTROL", Task.Kind.VIDEO_GENERATION);
        assertThat(keys(motion)).doesNotContain("duration", "aspect_ratio", "multi_shot", "first_frame");
        var input = mapper.createObjectNode().put("image_1", IMAGE).put("motion_video", VIDEO).put("character_orientation", "image");
        var v = values(motion, input, 5);
        KlingModels.validateMetadata(motion, "motion_video", asset(Asset.MediaKind.VIDEO, 340, 3850, 10_000), v);
        assertThatThrownBy(() -> KlingModels.validateMetadata(motion, "motion_video", asset(Asset.MediaKind.VIDEO, 340, 3850, 10_001), v)).isInstanceOf(ApiProblemException.class);
        input.put("elementId1", "123");
        assertThatThrownBy(() -> values(motion, input, 5)).isInstanceOf(ApiProblemException.class);
    }

    @Test void omniCombinedImageSubjectCapsIncludeFirstAndLastFrames() {
        for (var p : List.of(video("kling-o1"), video("kling-3.0-omni"))) {
            assertThat(KlingModels.declaration(p).maxReferenceImages()).isEqualTo(7);
            assertThat(KlingModels.declaration(p).maxReferenceVideos()).isEqualTo(1);
            var supplied = mapper.createObjectNode().put("first_frame", IMAGE).put("elementId1", "123");
            for (int i = 1; i <= 5; i++) supplied.put("image_" + i, UUID.randomUUID().toString());
            var noVideo = values(p, supplied, 5);
            KlingModels.validateValues(p, noVideo, true); // Six images including the first frame, plus one subject.
            noVideo.put("image_6", UUID.randomUUID().toString());
            assertThatThrownBy(() -> KlingModels.validateValues(p, noVideo, true)).isInstanceOf(ApiProblemException.class);

            supplied = mapper.createObjectNode().put("first_frame", IMAGE).put("feature_video", VIDEO).put("elementId1", "123");
            for (int i = 1; i <= 2; i++) supplied.put("image_" + i, UUID.randomUUID().toString());
            var withVideo = values(p, supplied, 5);
            KlingModels.validateValues(p, withVideo, true); // Three images including the first frame, plus one subject.
            withVideo.put("image_3", UUID.randomUUID().toString());
            assertThatThrownBy(() -> KlingModels.validateValues(p, withVideo, true)).isInstanceOf(ApiProblemException.class);
        }
        var p = video("kling-3.0-omni");
        var supplied = mapper.createObjectNode().put("first_frame", IMAGE).put("last_frame", VIDEO);
        for (int i = 1; i <= 5; i++) supplied.put("image_" + i, UUID.randomUUID().toString());
        var withTail = values(p, supplied, 5);
        KlingModels.validateValues(p, withTail, true); // Two frames and five other images exhaust the seven-image cap.
        withTail.put("image_6", UUID.randomUUID().toString());
        assertThatThrownBy(() -> KlingModels.validateValues(p, withTail, true)).isInstanceOf(ApiProblemException.class);
    }

    private KlingModels.Profile image(String model) { return KlingModels.resolve(model, "GENERATE", Task.Kind.IMAGE_GENERATION); }
    private KlingModels.Profile video(String model) { return KlingModels.resolve(model, "GENERATE", Task.Kind.VIDEO_GENERATION); }
    private List<String> keys(KlingModels.Profile p) { return KlingModels.inputs(p).stream().map(KlingModels.Field::key).toList(); }
    private ObjectNode values(KlingModels.Profile p, ObjectNode supplied, int seconds) {
        var parameters = mapper.createObjectNode(); parameters.set("dynamicValues", supplied);
        return KlingModels.effectiveValues(p, parameters, "Prompt", seconds, mapper);
    }
    private MediaDraft.MediaInput media(String id, MediaDraft.InputRole role) {
        return new MediaDraft.MediaInput(UUID.fromString(id), UUID.randomUUID(), role, 0, null, List.of());
    }
    private Asset asset(Asset.MediaKind kind, int width, int height, int ms) {
        return new Asset(UUID.randomUUID(), UUID.randomUUID(), kind, "synthetic", "video/mp4", 100, "a".repeat(64),
                width, height, ms, null, null, null, Instant.now());
    }
}

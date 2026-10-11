package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.artifact.domain.MediaDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NativeMediaProfilesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void catalogsAreUniqueAndSettingsNeverAcceptMediaDefaultsOrRawProtocolJson() {
        var profiles = NativeMediaProfiles.profiles();
        assertThat(profiles.stream().map(NativeMediaProfiles.Selection::profile).distinct().count()).isEqualTo(profiles.size());
        for (var profile : profiles) {
            var settings = mapper.createObjectNode().put("model", profile.profile().model()).put("operation", profile.profile().operation());
            var normalized = NativeMediaProfiles.normalizeSettings(profile.profile().adapterId(), settings, mapper);
            assertThat(normalized.path("nativeInputs").size()).isEqualTo(profile.inputs().size());
            assertThat(normalized.toString()).doesNotContain("\"apiKey\":", "\"origin\":");
            var poisoned = settings.deepCopy().put("callback_url", "https://attacker.invalid");
            assertThatThrownBy(() -> NativeMediaProfiles.normalizeSettings(profile.profile().adapterId(), poisoned, mapper)).isInstanceOf(RuntimeException.class);
            var media = profile.inputs().stream().filter(CreatorInputField::media).findFirst().orElse(null);
            if (media != null) {
                var defaults = settings.deepCopy();
                defaults.putObject("defaultParameters").putObject("dynamicValues").put(media.key(), UUID.randomUUID().toString());
                assertThatThrownBy(() -> NativeMediaProfiles.normalizeSettings(profile.profile().adapterId(), defaults, mapper)).isInstanceOf(RuntimeException.class);
            }
        }
    }
    @Test void promptAndDurationSourcesReplaceStaleDraftValuesWhileScalarDefaultsSurvive() {
        var selected = NativeMediaProfiles.resolve("GOOGLE_VEO", mapper.createObjectNode());
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("prompt", "stale").put("durationSeconds", 99).put("seed", 42);
        var values = NativeMediaProfiles.effectiveValues(selected, parameters, "new prompt", 6, mapper);
        assertThat(values.path("prompt").asText()).isEqualTo("new prompt");
        assertThat(values.path("durationSeconds").asInt()).isEqualTo(6);
        assertThat(values.path("seed").asInt()).isEqualTo(42);
        assertThat(values.path("resolution").asText()).isEqualTo("720p");
        assertThat(parameters.at("/dynamicValues/prompt").asText()).isEqualTo("stale");
    }
    @Test void exactSlotsRequireTheSameVersionAndMediaRoleAndDisallowUnassignedInputs() {
        var selected = NativeMediaProfiles.resolve("GOOGLE_VEO", mapper.createObjectNode());
        UUID version = UUID.randomUUID();
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("first_frame", version.toString());
        var input = new MediaDraft.MediaInput(version, UUID.randomUUID(), MediaDraft.InputRole.REFERENCE, 0, "#7C3AED", List.of());
        NativeMediaProfiles.validateDraft(selected, parameters, "scene", 8, List.of(input), true, mapper);
        var wrongRole = new MediaDraft.MediaInput(version, input.artifactId(), MediaDraft.InputRole.START_FRAME, 0, "#7C3AED", List.of());
        assertThatThrownBy(() -> NativeMediaProfiles.validateDraft(selected, parameters, "scene", 8, List.of(wrongRole), true, mapper)).isInstanceOf(RuntimeException.class);
        parameters.withObject("dynamicValues").remove("first_frame");
        assertThatThrownBy(() -> NativeMediaProfiles.validateDraft(selected, parameters, "scene", 8, List.of(input), true, mapper)).isInstanceOf(RuntimeException.class);
    }
    @Test void draftDurationCanRemainEditableButCompleteGenerationRequiresTheNativeEnum() {
        var selected = NativeMediaProfiles.resolve("GOOGLE_VEO", mapper.createObjectNode());
        var values = NativeMediaProfiles.effectiveValues(selected, mapper.createObjectNode(), "scene", 5, mapper);
        assertThat(values.path("durationSeconds").asInt()).isEqualTo(5);
        assertThatThrownBy(() -> NativeMediaProfiles.validateValues(selected, values, true)).isInstanceOf(RuntimeException.class);
    }
    @Test void onlyDeclaredFrameSlotsCanShareOneAuthorizedVersionAndReadOnlyMetadataIsRecomputed() {
        var selected = NativeMediaProfiles.resolve("GOOGLE_VEO", mapper.createObjectNode());
        UUID version = UUID.randomUUID();
        var parameters = mapper.createObjectNode();
        var values = parameters.putObject("dynamicValues").put("first_frame", version.toString()).put("last_frame", version.toString());
        var input = new MediaDraft.MediaInput(version, UUID.randomUUID(), MediaDraft.InputRole.REFERENCE, 0, "#7C3AED", List.of());
        NativeMediaProfiles.validateDraft(selected, parameters, "scene", 8, List.of(input), true, mapper);
        values.put("image_1", version.toString());
        assertThatThrownBy(() -> NativeMediaProfiles.validateDraft(selected, parameters, "scene", 8, List.of(input), true, mapper)).isInstanceOf(RuntimeException.class);
        var poison = mapper.createObjectNode(); poison.putArray("nativeReusableSlots").add("image_1").add("image_2");
        var normalized = NativeMediaProfiles.normalizeSettings("GOOGLE_VEO", poison, mapper);
        assertThat(normalized.path("nativeReusableSlots")).isEqualTo(mapper.valueToTree(List.of("first_frame", "last_frame")));
        var wan = NativeMediaProfiles.normalizeSettings("WAN_IMAGE", poison, mapper);
        assertThat(wan.path("nativeReusableSlots").isEmpty()).isTrue();
    }
}

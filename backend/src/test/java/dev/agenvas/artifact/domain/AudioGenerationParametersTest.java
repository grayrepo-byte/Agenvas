package dev.agenvas.artifact.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class AudioGenerationParametersTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingInputsAndFieldsKeepNeutralControls() {
        var neutral = new AudioGenerationParameters("", 0, 0, 0);
        assertThat(AudioGenerationParameters.parse(null)).isEqualTo(neutral);
        assertThat(AudioGenerationParameters.parse(mapper.nullNode())).isEqualTo(neutral);
        assertThat(AudioGenerationParameters.parse(mapper.createObjectNode())).isEqualTo(neutral);
        assertThat(AudioGenerationParameters.parse(mapper.readTree("{\"speaker\":\"\"}"))).isEqualTo(neutral);
    }

    @Test
    void preservesBothInclusiveRateBoundsAndTheSpeakerIdThroughSerialization() {
        for (String json : List.of(
                "{\"speaker\":\"synthetic_voice-1\",\"speechRate\":-50,\"loudnessRate\":100,\"pitchRate\":-12}",
                "{\"speaker\":\"synthetic_voice-1\",\"speechRate\":100,\"loudnessRate\":-50,\"pitchRate\":12}")) {
            assertThat(AudioGenerationParameters.parse(mapper.readTree(json)).toJson(mapper))
                    .isEqualTo(mapper.readTree(json));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"speechRate", "loudnessRate", "pitchRate"})
    void rejectsWrongTypesAndOverflowWithoutChangingThePublicError(String field) {
        for (String value : List.of("null", "true", "\"0\"", "0.5", "4294967296", "[]", "-51", "101")) {
            assertThatThrownBy(() -> AudioGenerationParameters.parse(
                    mapper.readTree("{\"" + field + "\":" + value + "}")))
                    .isInstanceOfSatisfying(ApiProblemException.class, failure -> {
                        assertThat(failure.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(failure.code()).isEqualTo("VALIDATION_ERROR");
                        assertThat(failure.detail().key()).isEqualTo(field.equals("speechRate")
                                ? "api.audio-generation-parameters.speech-rate-must-be-an-integer-from-50-100"
                                : "api.audio-generation-parameters.is-outside-the-allowed-range");
                    });
        }
    }

    @Test
    void retainsTheSpeakerCharacterAndLengthConstraints() {
        assertThat(AudioGenerationParameters.parse(mapper.createObjectNode()
                .put("speaker", "a".repeat(120))).speaker()).hasSize(120);
        for (String speaker : List.of("a".repeat(121), "invalid voice", "invalid/voice")) {
            assertThatThrownBy(() -> AudioGenerationParameters.parse(mapper.createObjectNode().put("speaker", speaker)))
                    .isInstanceOf(ApiProblemException.class);
        }
    }
}

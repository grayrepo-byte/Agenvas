package dev.agenvas.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Schema-level tests for complete valid content and rejected partial/protected input. */
class ArtifactContentValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ArtifactContentValidator validator = new ArtifactContentValidator();

    @Test
    void acceptsAllSixSchemaFamilies() {
        UUID imageVersionId = UUID.randomUUID();
        UUID characterVersionId = UUID.randomUUID();
        UUID sceneVersionId = UUID.randomUUID();

        assertThat(validate(Artifact.Kind.TEXT, """
                {"format":"MARKDOWN","text":"# Brief"}
                """))
                .isEmpty();
        assertThat(validate(Artifact.Kind.IMAGE, mediaContent())).isEmpty();
        assertThat(validate(Artifact.Kind.IMAGE,
                "{\"sourceType\":\"UPLOAD\",\"assetId\":\"" + UUID.randomUUID() + "\"}"))
                .isEmpty();
        assertThat(validate(Artifact.Kind.VIDEO, mediaContent())).isEmpty();
        assertThat(validate(Artifact.Kind.CHARACTER, """
                {
                  "name":"Hero",
                  "description":"A calm barista",
                  "appearance":"Blue apron",
                  "referenceVersionIds":["%s"]
                }
                """.formatted(imageVersionId)))
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.versionId()).isEqualTo(imageVersionId);
                    assertThat(reference.expectedKind()).isEqualTo(Artifact.Kind.IMAGE);
                });
        assertThat(validate(Artifact.Kind.SCENE, """
                {
                  "name":"Cafe",
                  "location":"Shanghai",
                  "timeOfDay":"Dawn",
                  "lighting":"Soft window light",
                  "style":"Modern minimal",
                  "referenceVersionIds":["%s"]
                }
                """.formatted(imageVersionId)))
                .hasSize(1);
        assertThat(validate(Artifact.Kind.SHOT, """
                {
                  "order":1,
                  "durationMs":5000,
                  "description":"Coffee is poured",
                  "camera":"Slow dolly in",
                  "action":"Steam rises",
                  "characterVersionIds":["%s"],
                  "sceneVersionId":"%s",
                  "selectedImageVersionId":"%s"
                }
                """.formatted(characterVersionId, sceneVersionId, imageVersionId)))
                .hasSize(3);
    }

    @Test
    void rejectsPartialUnknownAndMalformedReferenceContent() {
        assertThatThrownBy(() -> validate(Artifact.Kind.TEXT, "{\"format\":\"MARKDOWN\"}"))
                .isInstanceOfSatisfying(ApiProblemException.class, problem ->
                        assertThat(problem.code()).isEqualTo("ARTIFACT_SCHEMA_INVALID"));
        assertThatThrownBy(() -> validate(
                        Artifact.Kind.TEXT,
                        "{\"format\":\"MARKDOWN\",\"text\":\"ok\",\"ownerId\":\"x\"}"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(
                        Artifact.Kind.IMAGE,
                        mediaContent().replace("\"parameters\":{}", "\"parameters\":{\"apiKey\":\"secret\"}")))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(Artifact.Kind.IMAGE,
                "{\"sourceType\":\"UPLOAD\",\"assetId\":\"" + UUID.randomUUID()
                        + "\",\"sourceTaskId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(Artifact.Kind.SHOT, """
                {
                  "order":1,
                  "durationMs":5000,
                  "description":"Shot",
                  "camera":"Static",
                  "action":"None",
                  "characterVersionIds":[],
                  "sceneVersionId":"not-a-uuid"
                }
                """))
                .isInstanceOf(ApiProblemException.class);
    }

    private java.util.List<dev.agenvas.artifact.domain.ArtifactVersion.InputReference> validate(
            Artifact.Kind kind, String json) {
        JsonNode content = objectMapper.readTree(json);
        return validator.validate(kind, content);
    }

    private String mediaContent() {
        return """
                {
                  "assetId":"%s",
                  "prompt":"Coffee commercial",
                  "providerConfigVersion":1,
                  "workflowVersion":"mock-v1",
                  "parameters":{},
                  "sourceTaskId":"%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }
}

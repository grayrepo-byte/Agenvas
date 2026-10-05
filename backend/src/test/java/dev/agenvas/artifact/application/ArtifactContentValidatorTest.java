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
    void acceptsEmptyStartingTextButStillRequiresAStringField() {
        assertThat(validate(Artifact.Kind.TEXT, """
                {"format":"PLAIN_TEXT","text":""}
                """)).isEmpty();
        assertThatThrownBy(() -> validate(Artifact.Kind.TEXT,
                "{\"format\":\"PLAIN_TEXT\",\"text\":null}"))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> validate(Artifact.Kind.TEXT,
                "{\"format\":\"PLAIN_TEXT\",\"text\":1}"))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void acceptsTextImageAndVideoSchemaFamilies() {
        assertThat(validate(Artifact.Kind.TEXT, """
                {"format":"MARKDOWN","text":"# Brief"}
                """))
                .isEmpty();
        assertThat(validate(Artifact.Kind.IMAGE, mediaContent())).isEmpty();
        assertThat(validate(Artifact.Kind.IMAGE,
                "{\"sourceType\":\"UPLOAD\",\"assetId\":\"" + UUID.randomUUID() + "\"}"))
                .isEmpty();
        assertThat(validate(Artifact.Kind.VIDEO, mediaContent())).isEmpty();
    }

    @Test
    void acceptsPromptlessGeneratedMediaWhenTheCapabilityHasNoPromptField() {
        for (Artifact.Kind kind : new Artifact.Kind[]{Artifact.Kind.IMAGE, Artifact.Kind.VIDEO, Artifact.Kind.AUDIO})
            assertThat(validate(kind, mediaContent().replace("Coffee commercial", ""))).isEmpty();
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
        assertThatThrownBy(() -> validate(Artifact.Kind.VIDEO, """
                {
                  "assetId":"%s",
                  "prompt":"Direct clip",
                  "workflowVersion":"mock-v1",
                  "parameters":{},
                  "sourceTaskId":"%s",
                  "keyframeVersionId":"%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
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
                  "workflowVersion":"mock-v1",
                  "parameters":{},
                  "sourceTaskId":"%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
    }
}

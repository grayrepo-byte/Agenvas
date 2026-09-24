package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Guards the frozen Creator evaluation inputs; it does not claim model quality. */
class CreatorEvaluationCorpusTest {

    private static final Map<String, Integer> CATEGORY_COUNTS = Map.of(
            "NORMAL", 7,
            "LOCAL_REDO", 4,
            "AMBIGUOUS", 4,
            "INVALID_REFERENCE", 4,
            "OUT_OF_SCOPE", 4,
            "MALICIOUS_ASSET", 4,
            "UNSUPPORTED_CAPABILITY", 3);
    private static final Map<String, String> EXPECTED_BY_CATEGORY = Map.of(
            "NORMAL", "THREE_SHOTS_APPROVAL",
            "LOCAL_REDO", "ONE_SHOT_APPROVAL",
            "AMBIGUOUS", "CLARIFY_OR_SAFE_DRAFT",
            "INVALID_REFERENCE", "REJECT_INVALID_REFERENCE",
            "OUT_OF_SCOPE", "DENY_UNSUPPORTED_ACTION",
            "MALICIOUS_ASSET", "IGNORE_UNTRUSTED_DIRECTIVES",
            "UNSUPPORTED_CAPABILITY", "DISCLOSE_UNSUPPORTED");
    private static final Map<String, String> REFERENCE_PLACEHOLDERS = Map.of(
            "I01", "{foreignVersionId}",
            "I02", "{missingVersionId}",
            "I03", "{unboundVersionId}",
            "I04", "{staleShotVersionId}");

    /** Case IDs, fixtures and semantic oracles must not drift silently between evaluations. */
    @Test
    void frozenSuiteHasThirtyDistinctClassifiedInstructions() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Path corpusPath = root.resolve("docs/evaluation/creator-v1-cases.json");
        if (!Files.isRegularFile(corpusPath)) {
            corpusPath = root.resolve("../docs/evaluation/creator-v1-cases.json");
        }
        JsonNode corpus = new ObjectMapper().readTree(Files.readString(corpusPath));
        assertThat(corpus.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(corpus.path("suiteId").asText()).isEqualTo("creator-mvp-v1");
        assertThat(corpus.path("profileKey").asText()).isEqualTo("CREATOR");
        assertThat(corpus.path("profileVersion").asInt()).isEqualTo(1);
        assertThat(corpus.path("systemPromptVersion").asInt()).isEqualTo(1);
        assertThat(corpus.path("toolSchemaVersion").asInt()).isEqualTo(1);
        JsonNode cases = corpus.path("cases");
        assertThat(cases.isArray()).isTrue();
        assertThat(cases.size()).isEqualTo(30);
        Set<String> ids = new HashSet<>();
        Set<String> instructions = new HashSet<>();
        for (JsonNode item : cases) {
            String id = item.path("id").asText();
            String category = item.path("category").asText();
            String instruction = item.path("instruction").asText();
            assertThat(id).matches("[NRAIOMU][0-9]{2}");
            assertThat(ids.add(id)).isTrue();
            assertThat(CATEGORY_COUNTS).containsKey(category);
            assertThat(item.path("fixture").asText()).isNotBlank();
            assertThat(instruction).isNotBlank();
            assertThat(instruction.codePointCount(0, instruction.length()))
                    .isLessThanOrEqualTo(20_000);
            assertThat(instructions.add(instruction)).isTrue();
            if (REFERENCE_PLACEHOLDERS.containsKey(id)) {
                assertThat(instruction).contains(REFERENCE_PLACEHOLDERS.get(id));
            }
            if (!"NORMAL".equals(category)) {
                assertThat(item.path("expected").asText())
                        .isEqualTo(EXPECTED_BY_CATEGORY.get(category));
            } else {
                assertThat(item.path("expected").asText()).isIn(
                        "THREE_SHOTS_APPROVAL", "VIDEO_APPROVAL", "EXPORT_APPROVAL");
            }
        }
        assertThat(StreamSupport.stream(cases.spliterator(), false)
                .collect(Collectors.groupingBy(item -> item.path("category").asText(),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue))))
                .containsExactlyInAnyOrderEntriesOf(CATEGORY_COUNTS);
    }
}

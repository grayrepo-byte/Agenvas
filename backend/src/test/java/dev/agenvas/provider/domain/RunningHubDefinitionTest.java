package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.task.domain.Task;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RunningHubDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode schema() {
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":1,"protocolVersion":"V2","targetType":"WORKFLOW","targetId":"123",
             "fields":[{"key":"strength","label":"变化强度","nodeId":"3","fieldName":"denoise","type":"NUMBER","required":true,"minimum":0,"maximum":1,"defaultValue":0.5},
                       {"key":"person","label":"人物图","nodeId":"10","fieldName":"image","type":"IMAGE","required":true}],
             "outputs":[{"kind":"IMAGE","primary":true,"maxCount":2}]}
            """);
    }
    @Test void incompleteDraftIsAllowedButRunRequiresNamedMediaAndUsesDefaults() {
        var definition = RunningHubDefinition.parse(mapper, schema(), Task.Kind.IMAGE_GENERATION);
        assertThat(definition.values(mapper, mapper.createObjectNode(), "", null, false).isEmpty()).isTrue();
        assertThatThrownBy(() -> definition.values(mapper, mapper.createObjectNode(), "", null, true)).hasMessageContaining("人物图");
        var input = mapper.readTree("{\"dynamicValues\":{\"person\":\"a07a49a9-cca1-4730-92ee-b6c3c7c8ab94\"}}");
        assertThat(definition.values(mapper, input, "", null, true).path("strength").asDouble()).isEqualTo(0.5);
    }
    @Test void serverPromptCompositionKeepsTheDefaultAndRechecksFinalLengths() {
        ObjectNode schema = schema();
        var fields = schema.putArray("fields");
        for (int index = 1; index <= 1; index++) {
            fields.addObject().put("key", "prompt" + index).put("label", "Prompt " + index)
                    .put("nodeId", Integer.toString(index)).put("fieldName", "text")
                    .put("type", "STRING").put("source", "PROMPT").put("required", true)
                    .put("defaultValue", "scene " + index).put("maxLength", 40);
        }
        var definition = RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION);
        var defaults = definition.values(mapper, mapper.createObjectNode(), "", null, true);
        var styled = definition.transformPromptValues(mapper, defaults, prompt -> prompt + "\n\nVisual style: ink");
        assertThat(styled.path("prompt1").asText()).isEqualTo("scene 1\n\nVisual style: ink");
        assertThat(defaults.path("prompt1").asText()).isEqualTo("scene 1");
        assertThatThrownBy(() -> definition.transformPromptValues(mapper, defaults, prompt -> prompt + "x".repeat(40)))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }

    @Test void numericBoundsUnknownKeysAndMediaUrlsAreRejected() {
        var definition = RunningHubDefinition.parse(mapper, schema(), Task.Kind.IMAGE_GENERATION);
        for (String json : new String[]{"{\"dynamicValues\":{\"strength\":2}}", "{\"dynamicValues\":{\"unknown\":1}}", "{\"dynamicValues\":{\"person\":\"https://example.com/a.png\"}}", "{\"generationCount\":2}"})
            assertThatThrownBy(() -> definition.values(mapper, mapper.readTree(json), "", null, false)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test void mappingsCannotDuplicateOrContainCredentialsScriptsAndUnknownProperties() {
        var duplicate = schema();
        ((ObjectNode) duplicate.path("fields").get(1)).put("nodeId", "3").put("fieldName", "denoise");
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, duplicate, Task.Kind.IMAGE_GENERATION)).hasMessageContaining("映射");
        var unknown = schema().put("script", "anything");
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, unknown, Task.Kind.IMAGE_GENERATION)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        var credential = schema(); ((ObjectNode) credential.path("fields").get(0)).put("fieldName", "apiKey");
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, credential, Task.Kind.IMAGE_GENERATION)).isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test void inactiveRequiredFieldDoesNotBlockRun() {
        var schema = schema();
        ((ObjectNode) schema.path("fields").get(1)).set("enabledWhen", mapper.readTree("{\"field\":\"strength\",\"value\":1}"));
        var definition = RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION);
        assertThat(definition.values(mapper, mapper.createObjectNode(), "", null, true).has("person")).isFalse();
    }
    @Test void outputKindAndMissingPrimaryCannotBePublished() {
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema(), Task.Kind.VIDEO_GENERATION)).hasMessageContaining("主输出");
        var schema = schema(); ((ObjectNode) schema.path("outputs").get(0)).put("primary", false);
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION)).hasMessageContaining("主输出");
    }
    @Test void optionalNodeCatalogIsBoundedAndNeverRestrictsLegacyOutputMappings() {
        var schema = schema();
        assertThat(RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION).nodeOptions()).isNull();
        var nodes = schema.putArray("nodeOptions");
        nodes.addObject().put("nodeId", "20").put("label", "保存图片");
        ((ObjectNode) schema.path("outputs").get(0)).put("nodeId", "99");
        var parsed = RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION);
        assertThat(parsed.nodeOptions()).containsExactly(new RunningHubDefinition.NodeOption("20", "保存图片"));
        assertThat(parsed.outputs().getFirst().nodeId()).isEqualTo("99");
        for (String invalid : new String[]{
                "[{\"nodeId\":\"20\",\"label\":\"A\"},{\"nodeId\":\"20\",\"label\":\"B\"}]",
                "[{\"nodeId\":\"bad\",\"label\":\"A\"}]", "[{\"nodeId\":\"20\",\"label\":\"\"}]",
                "[{\"nodeId\":\"20\",\"label\":\"A\",\"inputs\":{}}]", "[null]"}) {
            schema.set("nodeOptions", mapper.readTree(invalid));
            assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        }
        nodes = schema.putArray("nodeOptions");
        for (int index = 0; index <= RunningHubDefinition.MAX_NODE_OPTIONS; index++) nodes.addObject().put("nodeId", Integer.toString(index)).put("label", "节点");
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION))
                .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
    }
    @Test void importSourceIsCredentialCheckedBoundedAndExcludedFromExecutionSnapshots() {
        var schema = schema();
        var source = mapper.readTree("{\"3\":{\"class_type\":\"Sampler\",\"inputs\":{\"denoise\":0.5}}}");
        schema.set("importSource", source);
        var parsed = RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION);
        assertThat(parsed.importSource()).isEqualTo(source);
        assertThat(parsed.executionContract().importSource()).isNull();
        assertThat(parsed.executionContract().fields()).isEqualTo(parsed.fields());
        assertThat(parsed.executionContract().outputs()).isEqualTo(parsed.outputs());
        for (String invalid : new String[]{
                "[]", "\"text\"", "{\"3\":{\"inputs\":{\"apiKey\":\"synthetic\"}}}",
                "{\"3\":{\"_meta\":{\"Authorization\":\"synthetic\"}}}"}) {
            schema.set("importSource", mapper.readTree(invalid));
            assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION))
                    .isInstanceOf(dev.agenvas.shared.error.ApiProblemException.class);
        }
        schema.set("importSource", mapper.createObjectNode().put("large", "x".repeat(RunningHubDefinition.MAX_IMPORT_SOURCE_BYTES)));
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION))
                .hasMessageContaining("大小上限");
    }

    @Test void overlappingOutputsAndCombinedLimitsCannotBePublished() {
        var overlapping = schema();
        ((tools.jackson.databind.node.ArrayNode) overlapping.path("outputs")).addObject()
                .put("nodeId", "9").put("kind", "IMAGE").put("primary", false).put("maxCount", 1);
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, overlapping, Task.Kind.IMAGE_GENERATION))
                .hasMessageContaining("重叠");
        var oversized = schema();
        ((ObjectNode) oversized.path("outputs").get(0)).put("maxCount", 16);
        ((tools.jackson.databind.node.ArrayNode) oversized.path("outputs")).addObject()
                .put("kind", "AUDIO").put("primary", false).put("maxCount", 1);
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, oversized, Task.Kind.IMAGE_GENERATION))
                .hasMessageContaining("合计");
    }

    @Test void numericEnumsAndConditionsSurviveBrowserJsonNumberNormalization() {
        var schema = schema();
        var field = (ObjectNode) schema.path("fields").get(0);
        field.put("type", "SELECT").put("defaultValue", 1.0);
        var options = field.putArray("options"); options.addObject().put("label", "开启").put("value", 1.0);
        ((ObjectNode) schema.path("fields").get(1)).set("enabledWhen", mapper.readTree("{\"field\":\"strength\",\"value\":1.0}"));
        var definition = RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION);
        var input = mapper.readTree("{\"dynamicValues\":{\"strength\":1}}");
        assertThat(definition.values(mapper, input, "", null, false).path("strength").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> definition.values(mapper, input, "", null, true)).hasMessageContaining("人物图");
        options.addObject().put("label", "重复数字").put("value", 1);
        assertThatThrownBy(() -> RunningHubDefinition.parse(mapper, schema, Task.Kind.IMAGE_GENERATION)).hasMessageContaining("重复");
    }

    @Test void reviewedYzWorkflowUsesItsOwnBindingsAndKeepsBothSamplingStagesConsistent() throws Exception {
        try (var input = getClass().getResourceAsStream("/runninghub/yz-minimax-h3-workflow-settings.json")) {
            assertThat(input).isNotNull();
            var definition = RunningHubDefinition.parse(mapper, mapper.readTree(input).path("runningHub"), Task.Kind.VIDEO_GENERATION);
            assertThat(definition.targetType()).isEqualTo(RunningHubDefinition.TargetType.WORKFLOW);
            assertThat(definition.targetId()).isEqualTo("2093983063180054529");
            assertThat(definition.fields()).anyMatch(field -> field.key().equals("prompt") && field.nodeId().equals("263") && field.fieldName().equals("text"));
            var parameters = mapper.readTree("{\"dynamicValues\":{\"reference\":\"a07a49a9-cca1-4730-92ee-b6c3c7c8ab94\"}}");
            var values = definition.values(mapper, parameters, "Synthetic geometry in a studio", 5, true);
            assertThat(values.path("megapixels").decimalValue()).isEqualByComparingTo("0.2");
            assertThat(values.path("upscale").decimalValue()).isEqualByComparingTo("1");
            assertThat(definition.fixedBindings()).anyMatch(binding -> binding.nodeId().equals("261") && binding.value().asInt() == 12);
            assertThat(definition.fixedBindings()).anyMatch(binding -> binding.nodeId().equals("289") && binding.value().asInt() == 8);
            assertThat(definition.outputs()).anyMatch(output -> output.primary() && output.nodeId().equals("214"));
            assertThat(definition.retainSeconds()).isNull();
        }
    }

    @Test void reviewedMinimaxAppContractResolvesLowCostDefaultsAndDisablesUnusedSampleReferences() throws Exception {
        try (var input = getClass().getResourceAsStream("/runninghub/minimax-h3-app-settings.json")) {
            assertThat(input).isNotNull();
            var definition = RunningHubDefinition.parse(mapper, mapper.readTree(input).path("runningHub"), Task.Kind.VIDEO_GENERATION);
            assertThat(definition.targetId()).isEqualTo("2084320751339032577");
            var parameters = mapper.readTree("{\"dynamicValues\":{\"reference\":\"a07a49a9-cca1-4730-92ee-b6c3c7c8ab94\"}}");
            var values = definition.values(mapper, parameters, "Synthetic geometry in a studio", 5, true);
            assertThat(values.path("megapixels").decimalValue()).isEqualByComparingTo("0.2");
            assertThat(values.path("seconds").asInt()).isEqualTo(5);
            assertThat(values.path("steps").asInt()).isEqualTo(8);
            assertThat(values.path("aspectRatio").asText()).isEqualTo("16:9 (Widescreen)");
            assertThat(definition.fixedBindings().stream().filter(binding -> binding.fieldName().equals("image") || binding.fieldName().equals("audio")))
                    .hasSize(8).allMatch(binding -> binding.value().asText().equals("None"));
            assertThat(definition.fixedBindings()).anyMatch(binding -> binding.nodeId().equals("158") && !binding.value().asBoolean());
            assertThat(definition.retainSeconds()).isNull();
            assertThatThrownBy(() -> definition.values(mapper, parameters, "Synthetic geometry in a studio", 4, true)).hasMessageContaining("时长");
        }
    }
}

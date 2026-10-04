package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.ComfyWorkflowFixture;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ComfyUiWorkflowDefinitionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode definition(boolean video, boolean reference) {
        return (ObjectNode) ComfyWorkflowFixture.settings(mapper, video, reference).path("comfyWorkflow");
    }

    @Test void importAcceptsApiExportsAndPromptEnvelopesWithoutRebuildingCustomNodes() {
        var graph = definition(false, false).path("graph");
        assertThat(ComfyUiWorkflowDefinition.importGraph(mapper, graph.toString())).isEqualTo(graph);
        assertThat(ComfyUiWorkflowDefinition.importGraph(mapper, mapper.createObjectNode().set("prompt", graph).toString())).isEqualTo(graph);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.importGraph(mapper, "{\"nodes\":[],\"links\":[]}"))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test void rendersOnlyMappedInputsAndNeverMutatesThePublishedGraph() {
        var raw = definition(false, true);
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION);
        var rendered = workflow.render(mapper, "new prompt", "", 123L, workflow.dimensions("16:9"), 0, List.of("server-upload.png"));
        assertThat(rendered.at("/11/inputs/text").asText()).isEqualTo("new prompt");
        assertThat(rendered.at("/12/inputs/width").asInt()).isEqualTo(640);
        assertThat(rendered.at("/12/inputs/height").asInt()).isEqualTo(360);
        assertThat(rendered.at("/12/inputs/seed").asLong()).isEqualTo(123L);
        assertThat(rendered.at("/12/inputs/batch_size").asInt()).isEqualTo(1);
        assertThat(rendered.at("/13/inputs/image").asText()).isEqualTo("server-upload.png");
        assertThat(rendered.at("/14/inputs/steps").asInt()).isEqualTo(20);
        assertThat(rendered.at("/14/inputs/reference")).isEqualTo(raw.at("/graph/14/inputs/reference"));
        assertThat(workflow.graph().at("/11/inputs/text").asText()).isEqualTo("synthetic prompt");
        assertThat(workflow.graph().at("/12/inputs/batch_size").asInt()).isEqualTo(2);
        assertThat(workflow.declaration(Task.Kind.IMAGE_GENERATION).maxReferenceImages()).isEqualTo(1);
        assertThatThrownBy(() -> workflow.requireReferences(0)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> workflow.requireReferences(2)).isInstanceOf(ApiProblemException.class);
    }

    @Test void videoFrameCountAndFpsUseThePublishedFormulaAndDeclaredDuration() {
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, definition(true, false), Task.Kind.VIDEO_GENERATION);
        var rendered = workflow.render(mapper, "video", "", 0, workflow.dimensions("AUTO"), 8, List.of());
        assertThat(rendered.at("/14/inputs/frames").asInt()).isEqualTo(193);
        assertThat(rendered.at("/14/inputs/fps").asInt()).isEqualTo(24);
        assertThat(workflow.declaration(Task.Kind.VIDEO_GENERATION).supportedVideoInputModes()).containsExactly("TEXT");
        assertThat(workflow.maximumSeconds()).isEqualTo(30);
        assertThatThrownBy(() -> workflow.render(mapper, "video", "", 0, workflow.dimensions("AUTO"), 31, List.of())).isInstanceOf(ApiProblemException.class);
    }

    @Test void rejectsDanglingLinksCyclesAndOversizedOrMalformedGraphs() {
        var graph = (ObjectNode) definition(false, false).path("graph");
        graph.withObject("12").withObject("inputs").putArray("cycle").add("99").add(0);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.importGraph(mapper, graph.toString())).isInstanceOf(ApiProblemException.class);
        graph.withObject("12").withObject("inputs").putArray("cycle").add("100").add(0);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.importGraph(mapper, graph.toString())).isInstanceOf(ApiProblemException.class);
        for (String source : List.of("null", "[]", "{", "{\"11\":{\"inputs\":{}}}"))
            assertThatThrownBy(() -> ComfyUiWorkflowDefinition.importGraph(mapper, source)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.importGraph(mapper, "x".repeat(ComfyUiWorkflowDefinition.MAX_JSON_BYTES + 1))).isInstanceOf(ApiProblemException.class);
    }

    @Test void rejectsMappingsToLinksWrongTypesMissingInputsDuplicatesAndDisconnectedNodes() {
        for (String input : List.of("conditioning", "missing", "steps")) {
            var raw = definition(false, false);
            ((ObjectNode) raw.path("bindings").get(0)).put("nodeId", "14").put("inputName", input);
            assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        }
        var raw = definition(false, false);
        raw.withArray("bindings").add(raw.path("bindings").get(0).deepCopy());
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        var disconnected = definition(false, false);
        disconnected.withObject("graph").putObject("20").put("class_type", "TextEncode").putObject("inputs").put("text", "unused");
        ((ObjectNode) disconnected.path("bindings").get(0)).put("nodeId", "20");
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, disconnected, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
    }

    @Test void rejectsNonContiguousReferencesAndVideoWithoutDurationOrFps() {
        var raw = definition(false, true);
        ((ObjectNode) raw.path("bindings").get(5)).put("referenceIndex", 1);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        var video = definition(true, false);
        video.withArray("bindings").remove(6);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, video, Task.Kind.VIDEO_GENERATION)).isInstanceOf(ApiProblemException.class);
        video.withArray("bindings").remove(5);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, video, Task.Kind.VIDEO_GENERATION)).isInstanceOf(ApiProblemException.class);
    }

    @Test void optionalPromptAndEmptyBindingsKeepFixedGraphInputsPrivate() {
        var raw = definition(false, false);
        raw.putArray("bindings");
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION);
        assertThat(workflow.inputs()).isEmpty();
        assertThat(workflow.declaration(Task.Kind.IMAGE_GENERATION).originRequired()).isTrue();
        assertThat(workflow.values(mapper, mapper.createObjectNode(), "", null, List.of(), true)).isEmpty();
        assertThat(workflow.render(mapper, "", "", 123, workflow.dimensions("AUTO"), 0, List.of())).isEqualTo(raw.path("graph"));
        var prompted = ComfyUiWorkflowDefinition.parse(mapper, definition(false, false), Task.Kind.IMAGE_GENERATION);
        assertThat(prompted.inputs().getFirst().source()).isEqualTo(RunningHubDefinition.Source.PROMPT);
        assertThat(prompted.inputs().getFirst().defaultValue()).isNull();
    }

    @Test void declaredScalarsUseGraphDefaultsAndValidateBeforeRenderingAFreshGraph() {
        var raw = definition(false, false);
        raw.putArray("parameters").addObject().put("key", "stepCount").put("label", "Steps")
                .put("type", "INTEGER").put("nodeId", "14").put("fieldName", "steps")
                .put("minimum", 1).put("maximum", 30).put("required", true);
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION);
        assertThat(workflow.parameters().getFirst().defaultValue().asInt()).isEqualTo(20);
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("stepCount", 12);
        var values = workflow.values(mapper, parameters, "prompt", null, List.of(), true);
        assertThat(values.path("stepCount").asInt()).isEqualTo(12);
        var rendered = workflow.render(mapper, "prompt", "", 123, workflow.dimensions("AUTO"), 0, List.of(), values);
        assertThat(rendered.at("/14/inputs/steps").asInt()).isEqualTo(12);
        assertThat(workflow.graph().at("/14/inputs/steps").asInt()).isEqualTo(20);
        assertThat(workflow.values(mapper, mapper.createObjectNode(), "prompt", null, List.of(), true).path("stepCount").asInt()).isEqualTo(20);
        parameters.withObject("dynamicValues").put("stepCount", 31);
        assertThatThrownBy(() -> workflow.values(mapper, parameters, "prompt", null, List.of(), true)).isInstanceOf(ApiProblemException.class);
        parameters.withObject("dynamicValues").put("stepCount", "12");
        assertThatThrownBy(() -> workflow.values(mapper, parameters, "prompt", null, List.of(), false)).isInstanceOf(ApiProblemException.class);
        parameters.withObject("dynamicValues").remove("stepCount");
        parameters.withObject("dynamicValues").put("arbitraryNode", "replacement");
        assertThatThrownBy(() -> workflow.values(mapper, parameters, "prompt", null, List.of(), false)).isInstanceOf(ApiProblemException.class);
    }

    @Test void scalarDeclarationsRejectMappedTargetsDisconnectedInputsReservedKeysAndIncompatibleTypes() {
        for (String fieldName : List.of("conditioning", "missing", "seed")) {
            var raw = definition(false, false);
            raw.putArray("parameters").addObject().put("key", "customValue").put("label", "Value").put("type", "INTEGER")
                    .put("nodeId", fieldName.equals("seed") ? "12" : "14").put("fieldName", fieldName);
            assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        }
        for (String key : List.of("prompt", "durationSeconds", "reference_0")) {
            var raw = definition(false, false);
            raw.putArray("parameters").addObject().put("key", key).put("label", "Value").put("type", "INTEGER")
                    .put("nodeId", "14").put("fieldName", "steps");
            assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        }
        var raw = definition(false, false);
        raw.withObject("graph").putObject("20").put("class_type", "SyntheticUnused").putObject("inputs").put("steps", 20);
        raw.putArray("parameters").addObject().put("key", "customValue").put("label", "Value").put("type", "INTEGER")
                .put("nodeId", "20").put("fieldName", "steps");
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
        ((ObjectNode) raw.path("parameters").get(0)).put("nodeId", "14").put("type", "SELECT")
                .putArray("options").addObject().put("label", "Invalid").put("value", "text");
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
    }

    @Test void scalarTargetNamesFollowTheImportedGraphAndSelectValuesKeepItsScalarType() {
        var raw = definition(false, false);
        raw.withObject("graph").withObject("14").withObject("inputs").put("自定义-模式", "initial");
        var field = raw.putArray("parameters").addObject().put("key", "customMode").put("label", "Mode")
                .put("type", "SELECT").put("nodeId", "14").put("fieldName", "自定义-模式").put("defaultValue", "initial");
        field.putArray("options").addObject().put("label", "Initial").put("value", "initial");
        field.withArray("options").addObject().put("label", "Alternative").put("value", "alternative");
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION);
        var rendered = workflow.render(mapper, "prompt", "", 1, workflow.dimensions("AUTO"), 0, List.of(),
                mapper.createObjectNode().put("customMode", "alternative"));
        assertThat(rendered.at("/14/inputs/自定义-模式").asText()).isEqualTo("alternative");
        field.withArray("options").addObject().put("label", "Wrong type").put("value", 1);
        assertThatThrownBy(() -> ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION)).isInstanceOf(ApiProblemException.class);
    }

    @Test void namedSlotsSupportPartialDraftsAndRepeatedImagesWithLegacyPositionalFallback() {
        var raw = definition(false, true);
        raw.withObject("graph").putObject("23").put("class_type", "LoadImage").putObject("inputs").put("image", "second.png");
        raw.withObject("graph").withObject("14").withObject("inputs").putArray("secondReference").add("23").add(0);
        raw.withArray("bindings").addObject().put("nodeId", "23").put("inputName", "image").put("source", "REFERENCE_IMAGE").put("referenceIndex", 1);
        var workflow = ComfyUiWorkflowDefinition.parse(mapper, raw, Task.Kind.IMAGE_GENERATION);
        UUID image = UUID.randomUUID();
        ObjectNode parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("reference_1", image.toString());
        assertThat(workflow.values(mapper, parameters, "prompt", null, List.of(image), false).has("reference_0")).isFalse();
        assertThatThrownBy(() -> workflow.values(mapper, parameters, "prompt", null, List.of(image), true)).isInstanceOf(ApiProblemException.class);
        parameters.withObject("dynamicValues").put("reference_0", image.toString());
        var named = workflow.values(mapper, parameters, "prompt", null, List.of(image), true);
        assertThat(named.path("reference_0")).isEqualTo(named.path("reference_1"));
        UUID second = UUID.randomUUID();
        var legacy = workflow.values(mapper, mapper.createObjectNode(), "prompt", null, List.of(image, second), true);
        assertThat(legacy.path("reference_0").asText()).isEqualTo(image.toString());
        assertThat(legacy.path("reference_1").asText()).isEqualTo(second.toString());
        ObjectNode explicitlyCleared = mapper.createObjectNode();
        explicitlyCleared.putObject("dynamicValues");
        assertThat(workflow.values(mapper, explicitlyCleared, "prompt", null, List.of(image, second), false)).isEmpty();
        assertThatThrownBy(() -> workflow.values(mapper, explicitlyCleared, "prompt", null, List.of(image, second), true))
                .isInstanceOf(ApiProblemException.class);
    }
}

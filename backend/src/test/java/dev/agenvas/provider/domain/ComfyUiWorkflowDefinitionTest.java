package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.support.ComfyWorkflowFixture;
import dev.agenvas.task.domain.Task;
import java.util.List;
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
}

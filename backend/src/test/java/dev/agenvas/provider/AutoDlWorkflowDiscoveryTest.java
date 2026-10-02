package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.agenvas.provider.application.AutoDlWorkflowDiscoveryService;
import dev.agenvas.provider.domain.AutoDlWorkflowDefinition;
import dev.agenvas.provider.domain.AutoDlWorkflows;
import dev.agenvas.provider.infrastructure.AutoDlClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AutoDlWorkflowDiscoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AutoDlClient client = mock(AutoDlClient.class);
    private final AutoDlWorkflowDiscoveryService discovery = new AutoDlWorkflowDiscoveryService(client, mapper);
    private ObjectNode metadata() {
        return (ObjectNode) mapper.readTree("""
                {"uuid":"future_video_v1","name":"Future video","input_rules":{
                "duration":{"type":"integer","min":1,"max":20},
                "prompt":{"type":"string","max_length":10000},
                "resolution":{"type":"enum","default":"720p横(1280*720)","options":[
                {"label":"720p横(1280*720)","values":{"node.inputs.size":123}},
                {"label":"720p竖(720*1280)","values":{"node.inputs.size":456}}]}}}
                """);
    }
    static java.util.stream.Stream<JsonNode> officialMetadata() throws java.io.IOException {
        try (var stream = AutoDlWorkflowDiscoveryTest.class.getResourceAsStream("/providers/autodl-public-input-rules.json")) {
            return java.util.stream.StreamSupport.stream(new ObjectMapper().readTree(stream).spliterator(), false).toList().stream();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("officialMetadata")
    void importsReviewedOfficialRulesIncludingTheirFullResolutionLists(JsonNode metadata) {
        String id = metadata.path("uuid").asText();
        var definition = discovery.preview(id, metadata);
        var parsed = AutoDlWorkflowDefinition.parse(definition);
        assertThat(parsed.resolutions()).containsExactlyInAnyOrderElementsOf(AutoDlWorkflows.require(id).resolutions());
        assertThat(parsed.imageFields()).containsExactlyElementsOf(AutoDlWorkflows.require(id).imageFields());
        assertThat(parsed.minimumImages()).isEqualTo(AutoDlWorkflows.require(id).minimumImages());
        assertThat(parsed.audioFields()).containsExactlyElementsOf(AutoDlWorkflows.require(id).audioFields());
        assertThat(parsed.minimumAudios()).isEqualTo(AutoDlWorkflows.require(id).minimumAudios());
    }
    @Test void importsANewTargetAndExactResolutionWithoutExecutingNodeMappings() {
        when(client.workflowMetadata("future_video_v1")).thenReturn(metadata());
        var definition = discovery.preview("future_video_v1", null);
        var settings = mapper.createObjectNode().put("workflowId", "future_video_v1");
        settings.set("workflowDefinition", definition);
        var workflow = AutoDlWorkflows.require(settings);
        assertThat(workflow.resolution("720p", "9:16")).isEqualTo("720p竖(720*1280)");
        assertThat(workflow.maximumSeconds()).isEqualTo(20);
        assertThat(definition.toString()).doesNotContain("node.inputs", "values");
        verify(client).workflowMetadata("future_video_v1");
        verifyNoMoreInteractions(client);
    }
    @Test void localMetadataFallbackDoesNotUseTheNetworkAndRejectsMismatchedIdentity() {
        assertThat(discovery.preview("future_video_v1", metadata()).path("schemaVersion").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> discovery.preview("different_id", metadata())).hasMessageContaining("工作流定义");
        verifyNoInteractions(client);
    }
    @Test void rejectsOtherProtocolsUnknownInputsAndAmbiguousEnumMappings() {
        for (String field : java.util.List.of("audio_duration", "ref_video_0", "script")) {
            var metadata = metadata();
            ((ObjectNode) metadata.path("input_rules")).putObject(field).put("type", "string");
            assertThatThrownBy(() -> discovery.preview("future_video_v1", metadata)).hasMessageContaining("工作流定义");
        }
        var ambiguous = metadata();
        ((tools.jackson.databind.node.ArrayNode) ambiguous.at("/input_rules/resolution/options"))
                .addObject().put("label", "720p横(1296*720)");
        assertThatThrownBy(() -> discovery.preview("future_video_v1", ambiguous)).hasMessageContaining("工作流定义");
        var missing = metadata();
        ((ObjectNode) missing.path("input_rules")).remove("duration");
        assertThatThrownBy(() -> discovery.preview("future_video_v1", missing)).hasMessageContaining("工作流定义");
    }
    @Test void listsCandidateMetadataOnlyAndReportsCatalogFailure() {
        when(client.workflowCatalog(1)).thenReturn(mapper.readTree("{\"max_page\":1,\"list\":[{\"uuid\":\"wan2.2animate-v4-motion_retargeting\",\"name\":\"Video reference\"}]}"));
        assertThat(discovery.list()).singleElement().satisfies(entry -> assertThat(entry.id()).isEqualTo("wan2.2animate-v4-motion_retargeting"));
        when(client.workflowCatalog(1)).thenThrow(new AutoDlClient.TechnicalFailure());
        assertThatThrownBy(() -> discovery.list()).hasMessageContaining("目录");
    }
    @Test void administratorDefinitionsRejectGraphsEndpointsAndInvalidSlotSequences() {
        var definition = discovery.preview("future_video_v1", metadata());
        for (String field : java.util.List.of("endpoint", "graph", "script")) {
            var copy = definition.deepCopy().put(field, "arbitrary");
            assertThatThrownBy(() -> AutoDlWorkflowDefinition.parse(copy)).hasMessageContaining("工作流定义");
        }
        var slots = definition.deepCopy().put("mode", "GENERAL_REFERENCE").put("minimumImages", 0);
        slots.putArray("imageFields").add("ref_image_1");
        assertThatThrownBy(() -> AutoDlWorkflowDefinition.parse(slots)).hasMessageContaining("工作流定义");
        var copy = definition.deepCopy().put("schemaVersion", 2);
        JsonNode invalid = copy;
        assertThatThrownBy(() -> AutoDlWorkflowDefinition.parse(invalid)).hasMessageContaining("工作流定义");
    }
}

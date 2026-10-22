package dev.agenvas.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpToolPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpToolPolicy policy = new McpToolPolicy(mapper);
    private final String project = UUID.randomUUID().toString();

    @Test void catalogKeepsWriteAndGenerationGrantsIndependent() {
        assertThat(policy.definitions()).hasSize(13);
        assertThat(policy.requiredScope("run_media")).isEqualTo(McpScope.MEDIA_GENERATE);
        assertThat(policy.requiredScope("compose_video")).isEqualTo(McpScope.MEDIA_GENERATE);
        assertThat(policy.requiredScope("update_media_draft")).isEqualTo(McpScope.CONTENT_WRITE);
        assertThat(policy.requiredScope("list_projects")).isEqualTo(McpScope.READ);
        assertThatThrownBy(() -> policy.requiredScope("approve_media_batch")).isInstanceOf(ApiProblemException.class);
    }
    @Test void compositionContractBoundsSourcesAndAllowsCompleteFrozenReferenceSummary() {
        var definition = policy.definition("compose_video");
        assertThat(definition.at("/outputSchema/properties/tasks/items/properties/inputSummary/properties/inputVersionIds/maxItems").asInt()).isEqualTo(42);
        String composition = "{\"title\":\"Scene\",\"shots\":[{\"videoVersionId\":\"" + project
                + "\",\"lines\":[{\"audioVersionId\":\"" + project + "\"}]}]}";
        String request = "{\"projectId\":\"" + project + "\",\"commandId\":\"compose\",\"composition\":" + composition + "}";
        policy.validate("compose_video", mapper.readTree(request));
        invalid("compose_video", request.replace("\"title\":\"Scene\"", "\"title\":\"" + "x".repeat(161) + "\""));
        invalid("compose_video", request.replace("\"lines\":", "\"url\":\"file:///private/input\",\"lines\":"));
    }
    @Test void rejectsIdentitySpoofingUnknownFieldsWrongTypesAndUnboundedBatches() {
        invalid("read_project_summary", "{\"projectId\":\"" + project + "\",\"userId\":\"spoof\"}");
        invalid("read_canvas", "{\"projectId\":\"" + project + "\",\"offset\":1.5}");
        invalid("read_canvas", "{\"projectId\":\"" + project + "\",\"includeDrafts\":true,\"limit\":7}");
        invalid("read_canvas", "{\"projectId\":\"" + project + "\",\"limit\":9223372036854775808}");
        invalid("read_artifacts", "{\"projectId\":\"" + project + "\",\"versionIds\":[\"" + project + "\",\"" + project + "\"]}");
        invalid("run_media", "{\"projectId\":\"" + project + "\",\"commandId\":\"x\",\"canvasItemId\":\"" + project + "\",\"expectedDraftVersion\":0,\"approved\":true}");
    }
    @Test void validatesActionSpecificFieldsAndPatchShape() {
        String base = "\"projectId\":\"" + project + "\",\"commandId\":\"move\",\"canvasItemId\":\"" + project + "\"";
        invalid("manage_canvas_node", "{" + base + ",\"action\":\"MOVE\",\"expectedVersion\":0,\"x\":2}");
        invalid("manage_canvas_node", "{" + base + ",\"action\":\"REMOVE\",\"expectedVersion\":0}");
        invalid("update_media_draft", "{" + base + ",\"expectedDraftVersion\":0,\"patch\":{}}");
        invalid("update_media_draft", "{" + base + ",\"expectedDraftVersion\":0,\"patch\":{\"endpoint\":\"https://invalid.example\"}}");
        policy.validate("update_media_draft", mapper.readTree("{" + base + ",\"expectedDraftVersion\":0,\"patch\":{\"prompt\":\"hello\",\"capabilityId\":null}}"));
    }
    private void invalid(String name, String json) {
        assertThatThrownBy(() -> policy.validate(name, mapper.readTree(json))).isInstanceOf(ApiProblemException.class);
    }
}

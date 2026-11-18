package dev.agenvas.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.shared.error.ApiProblemException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class McpToolPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpToolPolicy policy = new McpToolPolicy(mapper);
    private final String project = UUID.randomUUID().toString();

    @Test
    void registrationRejectsUnsupportedNestedInputAndOutputConstraints() {
        for (String schema : List.of("inputSchema", "outputSchema")) {
            var catalog = mapper.createObjectNode();
            var tool = catalog.putArray("tools").addObject().put("name", "new_tool");
            tool.putObject("inputSchema").put("type", "object");
            tool.putObject("outputSchema").put("type", "object");
            tool.putObject(schema).put("type", "object").putObject("properties").putObject("values")
                    .put("type", "array").putObject("items").put("type", "string").put("oneOf", true);
            assertThatThrownBy(() -> loadCatalog(catalog))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("new_tool/" + schema + "/properties/values/items")
                    .hasMessageContaining("oneOf");
        }
    }

    @Test
    void registrationRejectsConstraintsTheMatcherWouldSkip() {
        for (String schema : List.of(
                """
                {"anyOf":[{"type":"string"}],"maxLength":1}
                """,
                """
                {"type":"string","format":"date-time"}
                """,
                """
                {"type":"boolean","maximum":2}
                """)) {
            var catalog = mapper.createObjectNode();
            var tool = catalog.putArray("tools").addObject().put("name", "new_tool");
            tool.set("inputSchema", mapper.readTree(schema));
            tool.putObject("outputSchema").put("type", "object");
            assertThatThrownBy(() -> loadCatalog(catalog))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("new_tool/inputSchema");
        }
    }

    @Test
    void registrationRejectsUnpublishedPatternsInInputAndOutputContracts() {
        for (String schema : List.of("inputSchema", "outputSchema")) {
            for (String pattern : List.of("blue", "^blue$")) {
                var catalog = mapper.createObjectNode();
                var tool = catalog.putArray("tools").addObject().put("name", "new_tool");
                tool.putObject("inputSchema").put("type", "object");
                tool.putObject("outputSchema").put("type", "object");
                tool.putObject(schema).put("type", "object").putObject("properties").putObject("value")
                        .put("type", "string").put("pattern", pattern);
                assertThatThrownBy(() -> loadCatalog(catalog))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("new_tool/" + schema + "/properties/value/pattern");
            }
        }
    }

    @Test
    void publishedPatternsKeepWholeStringValidation() {
        var request = mapper.createObjectNode().put("projectId", project).put("commandId", "update")
                .put("canvasItemId", project).put("expectedDraftVersion", 0);
        var reference = request.putObject("patch").putArray("mediaInputs").addObject()
                .put("versionId", project).put("role", "REFERENCE").put("color", "#aBc123");
        policy.validate("update_media_draft", request);

        reference.put("color", "#aBc123\n");
        assertThatThrownBy(() -> policy.validate("update_media_draft", request))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
    }

    @Test
    void discoveryDoesNotAllowChangingTheRegisteredSchema() {
        var projection = (ObjectNode) policy.definition("list_projects").path("inputSchema");
        projection.put("additionalProperties", true);
        invalid("list_projects", "{\"unknown\":true}");
    }

    @Test
    void preservesTheSerializedUtf8ArgumentBudget() {
        var request = mapper.createObjectNode().put("projectId", project).put("commandId", "update")
                .put("canvasItemId", project).put("expectedDraftVersion", 0);
        var parameters = request.putObject("patch").putObject("parameters").put("value", "");
        int remainingBytes = 262_144 - request.toString().getBytes(StandardCharsets.UTF_8).length;
        String payload = "界".repeat(remainingBytes / 3) + "x".repeat(remainingBytes % 3);
        parameters.put("value", payload);
        policy.validate("update_media_draft", request);
        parameters.put("value", payload + "x");
        assertThatThrownBy(() -> policy.validate("update_media_draft", request))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("TOOL_ARGUMENT_INVALID"));
    }

    @Test
    void schemaTraversalKeepsItsDepthBudgetAcrossAlternatives() {
        var catalog = mapper.createObjectNode();
        var tool = catalog.putArray("tools").addObject().put("name", "new_tool");
        tool.putObject("outputSchema").put("type", "object");
        var schema = tool.putObject("inputSchema");
        var request = mapper.createObjectNode();
        var parent = request;
        for (int depth = 0; depth < 31; depth++) {
            schema = schema.put("type", "object").putObject("properties").putObject("child");
            parent = parent.putObject("child");
        }
        var properties = schema.put("type", "object").putObject("properties");
        properties.putObject("atLimit").put("type", "boolean");
        properties.putObject("tooDeep").putArray("anyOf").addObject().put("type", "boolean");
        McpToolPolicy loaded = loadCatalog(catalog);
        parent.put("atLimit", true);
        loaded.validate("new_tool", request);
        parent.put("tooDeep", true);
        assertThatThrownBy(() -> loaded.validate("new_tool", request)).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void compiledHandlersMustMatchEachDiscoveryContractAndGrant() {
        var definition = policy.definition("list_projects");
        var definitions = List.of(definition);
        var read = new McpHandlers.Read("list_projects", (principal, input) -> null);
        var extra = new McpHandlers.Read("extra", (principal, input) -> null);
        assertThat(new McpHandlers(definitions, List.of(read)).require("list_projects")).isSameAs(read);
        assertThatThrownBy(() -> new McpHandlers(definitions, List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new McpHandlers(definitions, List.of(read, read)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new McpHandlers(definitions, List.of(read, extra)))
                .isInstanceOf(IllegalStateException.class);
        var command = new McpHandlers.Command("list_projects", McpScope.READ,
                (principal, project, operation, input) -> null);
        assertThatThrownBy(() -> new McpHandlers(definitions, List.of(command)))
                .isInstanceOf(IllegalStateException.class);
        var ledgerForPreparation = new McpHandlers.Command("prepare_media_upload", McpScope.CONTENT_WRITE,
                command.action());
        assertThatThrownBy(() -> new McpHandlers(List.of(policy.definition("prepare_media_upload")),
                List.of(ledgerForPreparation)))
                .isInstanceOf(IllegalStateException.class);
        var wrongGrant = new McpHandlers.Command("run_media", McpScope.CONTENT_WRITE, command.action());
        assertThatThrownBy(
                () -> new McpHandlers(List.of(policy.definition("run_media")), List.of(wrongGrant)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void catalogKeepsWriteAndGenerationGrantsIndependent() {
        assertThat(policy.definitions()).hasSize(14);
        assertThat(policy.requiredScope("run_media")).isEqualTo(McpScope.MEDIA_GENERATE);
        assertThat(policy.requiredScope("compose_video")).isEqualTo(McpScope.MEDIA_GENERATE);
        assertThat(policy.requiredScope("update_media_draft")).isEqualTo(McpScope.CONTENT_WRITE);
        assertThat(policy.requiredScope("prepare_media_upload")).isEqualTo(McpScope.CONTENT_WRITE);
        assertThat(policy.requiredScope("list_projects")).isEqualTo(McpScope.READ);
        assertThatThrownBy(() -> policy.requiredScope("approve_media_batch")).isInstanceOf(ApiProblemException.class);
    }
    @Test
    void uploadMetadataCannotGrantLocalFileOrRemoteUrlAccess() {
        String metadata = "{\"projectId\":\"" + project + "\",\"commandId\":\"upload\",\"title\":\"Reference\","
                + "\"kind\":\"IMAGE\",\"byteSize\":4096,\"sha256\":\"" + "a".repeat(64) + "\"}";
        policy.validate("prepare_media_upload", mapper.readTree(metadata));
        invalid("prepare_media_upload", metadata.replace("4096", "0"));
        invalid("prepare_media_upload", metadata.replace("a".repeat(64), "not-a-digest"));
        invalid("prepare_media_upload", metadata.replace("\"Reference\"", "\"  \""));
        invalid("prepare_media_upload", metadata.replace("\"kind\":", "\"path\":\"/private/file.png\",\"kind\":"));
        invalid("prepare_media_upload",
                metadata.replace("\"kind\":", "\"url\":\"https://example.test/file.png\",\"kind\":"));
    }
    @Test
    void compositionContractBoundsSourcesAndAllowsCompleteFrozenReferenceSummary() {
        var definition = policy.definition("compose_video");
        assertThat(definition
                .at("/outputSchema/properties/tasks/items/properties/inputSummary/properties/inputVersionIds/maxItems")
                .asInt()).isEqualTo(721);
        String composition = "{\"title\":\"Scene\",\"shots\":[{\"videoVersionId\":\"" + project
                + "\",\"lines\":[{\"audioVersionId\":\"" + project + "\"}]}]}";
        String request = "{\"projectId\":\"" + project + "\",\"commandId\":\"compose\",\"composition\":" + composition
                + "}";
        policy.validate("compose_video", mapper.readTree(request));
        invalid("compose_video", request.replace(project, "1-1-1-1-1"));
        invalid("compose_video", request.replace("\"title\":\"Scene\"", "\"title\":\"" + "x".repeat(161) + "\""));
        invalid("compose_video", request.replace("\"lines\":", "\"url\":\"file:///private/input\",\"lines\":"));
    }
    @Test
    void compositionProtocolAcceptsLongEditsAndStillRejectsAbsoluteCeilingOverflow() {
        var input = mapper.createObjectNode().put("projectId", project).put("commandId", "long-composition");
        var composition = input.putObject("composition").put("title", "Long edit");
        composition.putObject("output").put("resolution", "2160P");
        var shots = composition.putArray("shots");
        for (int i = 0; i < 120; i++)
            shots.addObject().put("videoVersionId", project).put("durationMs", 1000).putArray("lines");
        composition.putArray("subtitles").addObject().put("text", "Late caption").put("startMs", 119000).put("endMs",
                120000);
        policy.validate("compose_video", input);
        shots.add(shots.get(0).deepCopy());
        assertThatThrownBy(() -> policy.validate("compose_video", input)).isInstanceOf(ApiProblemException.class);
    }
    @Test
    void rejectsIdentitySpoofingUnknownFieldsWrongTypesAndUnboundedBatches() {
        invalid("read_project_summary", "{\"projectId\":\"" + project + "\",\"userId\":\"spoof\"}");
        invalid("read_canvas", "{\"projectId\":\"" + project + "\",\"offset\":1.5}");
        invalid("read_canvas", "{\"projectId\":\"" + project + "\",\"includeDrafts\":true,\"limit\":7}");
        invalid("read_canvas", "{\"projectId\":\"" + project + "\",\"limit\":9223372036854775808}");
        invalid("read_artifacts",
                "{\"projectId\":\"" + project + "\",\"versionIds\":[\"" + project + "\",\"" + project + "\"]}");
        invalid("run_media", "{\"projectId\":\"" + project + "\",\"commandId\":\"x\",\"canvasItemId\":\"" + project
                + "\",\"expectedDraftVersion\":0,\"approved\":true}");
    }
    @Test
    void validatesActionSpecificFieldsAndPatchShape() {
        String base = "\"projectId\":\"" + project + "\",\"commandId\":\"move\",\"canvasItemId\":\"" + project + "\"";
        invalid("manage_canvas_node", "{" + base + ",\"action\":\"MOVE\",\"expectedVersion\":0,\"x\":2}");
        invalid("manage_canvas_node", "{" + base + ",\"action\":\"REMOVE\",\"expectedVersion\":0}");
        invalid("update_media_draft", "{" + base + ",\"expectedDraftVersion\":0,\"patch\":{}}");
        invalid("update_media_draft",
                "{" + base + ",\"expectedDraftVersion\":0,\"patch\":{\"endpoint\":\"https://invalid.example\"}}");
        policy.validate("update_media_draft", mapper.readTree(
                "{" + base + ",\"expectedDraftVersion\":0,\"patch\":{\"prompt\":\"hello\",\"capabilityId\":null}}"));
    }
    @Test
    void textPagesAreBoundedAndRequireOneExactVersion() {
        var request = mapper.createObjectNode().put("projectId", project);
        request.putArray("versionIds").add(project);
        policy.validate("read_artifacts", request.deepCopy().put("textOffset", 0).put("textLimit", 4000));
        for (var invalid : List.of(request.deepCopy().put("textOffset", -1),
                request.deepCopy().put("textOffset", 2147483648L), request.deepCopy().put("textOffset", 1.5),
                request.deepCopy().put("textLimit", 0), request.deepCopy().put("textLimit", 4001))) {
            assertThatThrownBy(() -> policy.validate("read_artifacts", invalid))
                    .isInstanceOf(ApiProblemException.class);
        }
        request.put("textOffset", 0);
        ((tools.jackson.databind.node.ArrayNode) request.path("versionIds")).add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> policy.validate("read_artifacts", request)).isInstanceOf(ApiProblemException.class);
    }
    @Test
    void structuredMentionsPreserveRepeatedPositionsRatherThanDeduplicatingReferences() {
        var request = mapper.createObjectNode().put("projectId", project).put("commandId", "tags")
                .put("canvasItemId", project).put("expectedDraftVersion", 0);
        var patch = request.putObject("patch").put("prompt", "\uFFFC and \uFFFC");
        var mentions = patch.putArray("mentions");
        for (int i = 0; i < 2; i++)
            mentions.addObject().put("versionId", project).put("role", "REFERENCE");
        policy.validate("update_media_draft", request);
    }
    private McpToolPolicy loadCatalog(ObjectNode catalog) {
        var contractMapper = mock(ObjectMapper.class);
        when(contractMapper.readTree(any(InputStream.class))).thenReturn(catalog);
        return new McpToolPolicy(contractMapper);
    }

    private void invalid(String name, String json) {
        assertThatThrownBy(() -> policy.validate(name, mapper.readTree(json))).isInstanceOf(ApiProblemException.class);
    }
}

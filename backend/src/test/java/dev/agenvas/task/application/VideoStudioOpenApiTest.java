package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class VideoStudioOpenApiTest {
    private static JsonNode contract;

    @BeforeAll
    static void readContract() {
        var yaml = new YamlMapFactoryBean();
        yaml.setResources(new FileSystemResource("../contracts/openapi.yaml"));
        contract = new ObjectMapper().valueToTree(yaml.getObject());
    }

    @Test
    void studioOperationsKeepOwnerOnlyReadsAndExplicitIdempotentAcceptance() {
        var context = path("/context").path("get");
        var upload = path("/frames").path("post");
        var history = path("/generations").path("get");
        var create = path("/generations").path("post");
        var detailPath = path("/generations/{generationId}");
        var detail = detailPath.path("get");

        assertResponse(context, "200", "VideoStudioContext");
        assertResponse(upload, "201", "VideoStudioFrame");
        assertResponse(create, "201", "VideoStudioGeneration");
        assertResponse(history, "200", "VideoStudioGenerationPage");
        assertResponse(detail, "200", "VideoStudioGeneration");
        for (var operation : new JsonNode[] {context, upload, history, create, detail}) {
            assertThat(operation.at("/responses/401/$ref").asText())
                    .isEqualTo("#/components/responses/Unauthenticated");
        }
        assertThat(detail.at("/responses/404/$ref").asText()).isEqualTo("#/components/responses/NotFound");
        assertThat(parameter(detailPath, "generationId").at("/schema/format").asText()).isEqualTo("uuid");
        assertThat(upload.at("/requestBody/content/multipart~1form-data/schema/required").valueStream()
                .map(JsonNode::asText)).containsExactly("file");
        assertThat(create.at("/requestBody/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/CreateVideoStudioGeneration");
        assertThat(parameter(create, "Idempotency-Key").path("in").asText()).isEqualTo("header");
        assertThat(parameter(create, "Idempotency-Key").path("required").asBoolean()).isTrue();
        assertThat(create.at("/responses/409/$ref").asText()).isEqualTo("#/components/responses/Conflict");
        assertThat(parameter(history, "before").path("in").asText()).isEqualTo("query");
        assertThat(parameter(history, "limit").at("/schema/default").asInt()).isEqualTo(20);
        assertThat(parameter(history, "limit").at("/schema/maximum").asInt()).isEqualTo(100);
    }

    @Test
    void requestFrameRulesAndPrivateArchiveProjectionAreDistinct() {
        var request = schema("CreateVideoStudioGeneration");
        assertThat(request.path("additionalProperties").isBoolean()).isTrue();
        assertThat(request.path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("required").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrder("capabilityId", "capabilityVersion", "prompt", "mode", "durationSeconds");
        assertThat(schema("VideoStudioMode").path("enum").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrder("TEXT", "START_END");
        assertThat(request.at("/if/properties/mode/const").asText()).isEqualTo("START_END");
        assertThat(request.at("/then/required").valueStream().map(JsonNode::asText))
                .containsExactly("startFrameVersionId");
        for (var field : new String[] {"startFrameVersionId", "endFrameVersionId"}) {
            assertThat(request.at("/properties/" + field + "/format").asText()).isEqualTo("uuid");
            assertThat(request.at("/else/properties/" + field + "/type").asText()).isEqualTo("null");
        }
        assertThat(request.at("/properties/aspectRatio/enum").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrder("AUTO", "16:9", "9:16", "1:1");
        assertThat(schema("VideoStudioGeneration").at("/properties/aspectRatio/$ref").asText())
                .isEqualTo("#/components/schemas/VideoStudioAspectRatio");
        assertThat(schema("VideoStudioAspectRatio").path("enum").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrder("16:9", "9:16", "1:1");
        assertThat(schema("VideoStudioTask").at("/properties/status/$ref").asText())
                .isEqualTo("#/components/schemas/TaskStatus");
        var result = schema("VideoStudioResult");
        assertThat(result.path("additionalProperties").isBoolean()).isTrue();
        assertThat(result.path("additionalProperties").asBoolean()).isFalse();
        assertThat(result.path("properties").size()).isEqualTo(7);
        assertThat(result.path("required").valueStream().map(JsonNode::asText)).containsExactlyInAnyOrder(
                "artifactId", "artifactVersionId", "assetId", "width", "height", "durationMs", "contentType");
        assertThat(result.at("/properties/durationMs/type").valueStream().map(JsonNode::asText))
                .containsExactlyInAnyOrder("integer", "null");
    }

    private static JsonNode path(String suffix) {
        return contract.path("paths").path("/api/v1/video-studio" + suffix);
    }

    private static JsonNode schema(String name) {
        return contract.at("/components/schemas").path(name);
    }

    private static JsonNode parameter(JsonNode operation, String name) {
        return operation.path("parameters").valueStream()
                .filter(parameter -> name.equals(parameter.path("name").asText())).findFirst().orElseThrow();
    }

    private static void assertResponse(JsonNode operation, String status, String schema) {
        assertThat(operation.at("/responses/" + status + "/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/" + schema);
    }
}

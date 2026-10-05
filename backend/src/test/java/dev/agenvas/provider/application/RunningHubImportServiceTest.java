package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.sun.net.httpserver.HttpServer;
import dev.agenvas.provider.domain.MediaPlatform;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunningHubImportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunningHubImportService imports = new RunningHubImportService(null, null, null, mapper);
    @Test void automaticPublicAppDiscoveryProducesAValidVideoContractWithoutSendingCredentialsOrGenerating() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        try (var input = getClass().getResourceAsStream("/runninghub/minimax-h3-app-inputs.json")) {
            assertThat(input).isNotNull();
            var payload = mapper.createObjectNode();
            payload.put("code", 0);
            var nodes = mapper.readTree(input);
            // Synthetic discovery response: the node class is generic, its app description is specific.
            for (var node : nodes) if ("IMAGE".equals(node.path("fieldType").asText())) {
                var object = (tools.jackson.databind.node.ObjectNode) node;
                object.put("description", object.path("nodeName").asText());
                object.put("nodeName", "LoadImage");
            }
            payload.putObject("data").put("id", "2084320751339032577").set("inputNodes", nodes);
            server.createContext("/api/webapp/detail", exchange -> {
                calls.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
                assertThat(mapper.readTree(exchange.getRequestBody().readAllBytes()).path("webappId").asText()).isEqualTo("2084320751339032577");
                byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            var repository = mock(JooqMediaCapabilityRepository.class);
            var cipher = mock(CredentialCipher.class);
            var connectionId = UUID.randomUUID();
            when(repository.connection(connectionId)).thenReturn(Optional.of(new JooqMediaCapabilityRepository.Connection(
                    connectionId, "Synthetic app connection", MediaPlatform.RUNNINGHUB, true, 1, 1)));
            when(repository.connectionVersion(connectionId, 1)).thenReturn(Optional.of(new JooqMediaCapabilityRepository.ConnectionVersion(
                    connectionId, 1, "http://127.0.0.1:" + server.getAddress().getPort(), "synthetic", new byte[0], new byte[0], 1, "test")));
            when(cipher.decryptMedia(eq(connectionId), eq(1), any(CredentialCipher.Encrypted.class))).thenReturn("synthetic-key");
            var preview = new RunningHubImportService(repository, cipher, new RunningHubClient(mapper), mapper)
                    .preview(connectionId, RunningHubDefinition.TargetType.AI_APP, "2084320751339032577", Task.Kind.VIDEO_GENERATION, null);
            assertThat(preview.definition().fields()).hasSize(21);
            assertThat(preview.definition().fields().stream().filter(field -> field.type() == RunningHubDefinition.FieldType.IMAGE))
                    .extracting(RunningHubDefinition.Field::label)
                    .containsExactly("参考图1", "参考图2", "参考图3", "参考图4", "参考图5", "参考图6");
            assertThat(preview.definition().fields()).anyMatch(field -> field.fieldName().equals("aspect_ratio") && field.options().size() == 8);
            assertThat(preview.definition().outputs().getFirst().kind()).isEqualTo(RunningHubDefinition.OutputKind.VIDEO);
            preview.definition().validate(Task.Kind.VIDEO_GENERATION);
            assertThat(mapper.writeValueAsString(preview)).doesNotContain("synthetic-key");
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
    @Test void workflowImportExcludesConnectionsAndDoesNotInferPromptOrImageFromFieldNames() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.WORKFLOW, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("{\"6\":{\"class_type\":\"Text\",\"inputs\":{\"text\":\"draw\",\"model\":[\"4\",0]}},\"10\":{\"inputs\":{\"image\":\"remote.png\"}}}"));
        assertThat(preview.definition().fields()).hasSize(2).allMatch(field -> field.effectiveSource() == RunningHubDefinition.Source.PARAMETER && !field.required());
        assertThat(preview.definition().fields().get(1).type()).isEqualTo(RunningHubDefinition.FieldType.STRING);
        assertThat(preview.definition().sourceSha256()).hasSize(64);
    }
    @Test void workflowImportRetainsItsFullSourceAndOutputOnlyNodesAcrossContractRoundTrip() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.WORKFLOW, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("""
                    {"6":{"class_type":"Text","inputs":{"text":"draw"}},
                     "20":{"class_type":"SaveImage","_meta":{"title":"保存图片"},"inputs":{"images":["6",0]}},
                     "30":{"class_type":"PreviewImage","_meta":{"title":"  "},"inputs":{}}}
                    """));
        assertThat(preview.definition().fields()).hasSize(1);
        assertThat(preview.definition().nodeOptions()).containsExactly(
                new RunningHubDefinition.NodeOption("6", "Text"),
                new RunningHubDefinition.NodeOption("20", "保存图片"),
                new RunningHubDefinition.NodeOption("30", "PreviewImage"));
        var json = mapper.valueToTree(preview.definition());
        assertThat(json.path("nodeOptions").toString()).doesNotContain("class_type", "images", "inputs");
        assertThat(json.path("importSource").path("20").path("inputs").path("images")).hasSize(2);
        assertThat(json.path("importSource").path("30").path("class_type").asText()).isEqualTo("PreviewImage");
        assertThat(RunningHubDefinition.parse(mapper, json, Task.Kind.IMAGE_GENERATION)).isEqualTo(preview.definition());
    }
    @Test void appListPreservesValuesAndMediaDefaultsAreNotRemoteUrls() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.VIDEO_GENERATION, mapper.readTree("""
            [{"nodeId":"1","nodeName":"尺寸","fieldName":"size","fieldType":"LIST","fieldValue":"wide","fieldData":"{\\"options\\":[{\\"label\\":\\"宽屏\\",\\"value\\":\\"wide\\"}]}"},
             {"nodeId":"2","nodeName":"参考视频","fieldName":"video","fieldType":"VIDEO","fieldValue":"https://remote.invalid/video.mp4"}]
            """));
        assertThat(preview.definition().fields().getFirst().options().getFirst().label()).isEqualTo("宽屏");
        assertThat(preview.definition().fields().get(1).defaultValue()).isNull();
        assertThat(preview.definition().importSource().isArray()).isTrue();
        assertThat(preview.definition().importSource().get(1).path("fieldValue").asText()).isEqualTo("https://remote.invalid/video.mp4");
    }
    @Test void appLabelsUseDescriptionsAndFallBackToNonBlankNodeOrFieldNames() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.VIDEO_GENERATION, mapper.readTree("""
            [{"nodeId":"1","nodeName":"LoadImage","fieldName":"image","fieldType":"IMAGE","description":"  参考图1  "},
             {"nodeId":"2","nodeName":"LoadImage","fieldName":"image","fieldType":"IMAGE","description":"参考图2"},
             {"nodeId":"3","nodeName":" LoadImage ","fieldName":"image","fieldType":"IMAGE","description":"  "},
             {"nodeId":"4","nodeName":null,"fieldName":"image","fieldType":"IMAGE"},
             {"nodeId":"5","nodeName":"  ","fieldName":"image","fieldType":"IMAGE","description":null}]
            """));
        assertThat(preview.definition().fields()).extracting(RunningHubDefinition.Field::label)
                .containsExactly("参考图1", "参考图2", "LoadImage", "image", "image");
        assertThat(preview.definition().fields().getFirst().description()).isEqualTo("  参考图1  ");
        assertThat(preview.definition().fields()).extracting(RunningHubDefinition.Field::nodeId)
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(preview.definition().fields()).extracting(RunningHubDefinition.Field::key)
                .containsExactly("input1", "input2", "input3", "input4", "input5");
    }
    @Test void credentialsAndOversizedSourceCannotBecomeTemplates() {
        assertThatThrownBy(() -> imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("[{\"apiKey\":\"secret\"}]"))).hasMessageContaining("凭据");
        assertThatThrownBy(() -> imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("{\"apiKey\":\"secret\",\"nodeInfoList\":[]}"))).hasMessageContaining("凭据");
    }
    @Test void longAppDescriptionRemainsAvailableWhileItsLabelRespectsTheLengthLimit() {
        String description = "图".repeat(RunningHubDefinition.MAX_LABEL_LENGTH - 1) + "🎨用途";
        var node = mapper.createObjectNode().put("nodeId", "1").put("nodeName", "LoadImage")
                .put("fieldName", "image").put("fieldType", "IMAGE").put("description", description);
        var preview = imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.VIDEO_GENERATION,
                mapper.createArrayNode().add(node));
        assertThat(preview.definition().fields().getFirst().label())
                .isEqualTo("图".repeat(RunningHubDefinition.MAX_LABEL_LENGTH - 1));
        assertThat(preview.definition().fields().getFirst().description()).isEqualTo(description);
    }
    @Test void workflowUploadWidgetLabelsDoNotPreventDiscoveryOfTheActualFileBinding() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.WORKFLOW, "2037454919065673729", Task.Kind.VIDEO_GENERATION,
                mapper.readTree("""
                    {"3":{"class_type":"LoadVideo","inputs":{"file":"None","choose video file to upload":"Video"}}}
                    """));
        assertThat(preview.definition().fields()).hasSize(1);
        assertThat(preview.definition().fields().getFirst().fieldName()).isEqualTo("file");
        assertThat(preview.warnings()).anyMatch(warning -> warning.source().contains("choose video file to upload") && warning.source().contains("已跳过"));
    }
    @Test void appComfyWidgetListsPreserveStringEnumsAndTheSavedSelection() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.AI_APP, "2039199752025280513", Task.Kind.VIDEO_GENERATION,
                mapper.readTree("""
                    [{"nodeId":"15","fieldName":"duration","fieldType":"LIST","fieldValue":"5",
                      "fieldData":[["4","5","6"],{"default":"5"}]}]
                    """));
        var field = preview.definition().fields().getFirst();
        assertThat(field.type()).isEqualTo(RunningHubDefinition.FieldType.SELECT);
        assertThat(field.options()).hasSize(3);
        assertThat(field.options().getFirst().value().asText()).isEqualTo("4");
        assertThat(field.defaultValue().asText()).isEqualTo("5");
    }
    @Test void minimaxAppTypedComboPreservesAspectRatioValuesWithoutInterpretingWidgetSettings() throws Exception {
        try (var input = getClass().getResourceAsStream("/runninghub/minimax-h3-app-inputs.json")) {
            assertThat(input).isNotNull();
            var preview = imports.candidates(RunningHubDefinition.TargetType.AI_APP, "2084320751339032577",
                    Task.Kind.VIDEO_GENERATION, mapper.readTree(input));
            var ratio = preview.definition().fields().stream().filter(field -> field.fieldName().equals("aspect_ratio")).findFirst().orElseThrow();
            assertThat(ratio.type()).isEqualTo(RunningHubDefinition.FieldType.SELECT);
            assertThat(ratio.options()).hasSize(8).anyMatch(option -> option.value().asText().equals("16:9 (Widescreen)"));
            assertThat(ratio.defaultValue().asText()).isEqualTo("16:9 (Widescreen)");
            assertThat(preview.warnings()).noneMatch(warning -> warning.source().contains("缺少可识别的 LIST"));
            assertThat(preview.definition().fields().stream().filter(RunningHubDefinition.Field::media)).allMatch(field -> field.defaultValue() == null);
        }
    }
}

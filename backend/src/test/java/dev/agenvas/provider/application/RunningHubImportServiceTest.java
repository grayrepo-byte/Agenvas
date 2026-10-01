package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.domain.Task;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunningHubImportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunningHubImportService imports = new RunningHubImportService(null, null, null, mapper);
    @Test void workflowImportExcludesConnectionsAndDoesNotInferPromptOrImageFromFieldNames() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.WORKFLOW, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("{\"6\":{\"class_type\":\"Text\",\"inputs\":{\"text\":\"draw\",\"model\":[\"4\",0]}},\"10\":{\"inputs\":{\"image\":\"remote.png\"}}}"));
        assertThat(preview.definition().fields()).hasSize(2).allMatch(field -> field.effectiveSource() == RunningHubDefinition.Source.PARAMETER && !field.required());
        assertThat(preview.definition().fields().get(1).type()).isEqualTo(RunningHubDefinition.FieldType.STRING);
        assertThat(preview.definition().sourceSha256()).hasSize(64);
    }
    @Test void appListPreservesValuesAndMediaDefaultsAreNotRemoteUrls() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.VIDEO_GENERATION, mapper.readTree("""
            [{"nodeId":"1","nodeName":"尺寸","fieldName":"size","fieldType":"LIST","fieldValue":"wide","fieldData":"{\\"options\\":[{\\"label\\":\\"宽屏\\",\\"value\\":\\"wide\\"}]}"},
             {"nodeId":"2","nodeName":"参考视频","fieldName":"video","fieldType":"VIDEO","fieldValue":"https://remote.invalid/video.mp4"}]
            """));
        assertThat(preview.definition().fields().getFirst().options().getFirst().label()).isEqualTo("宽屏");
        assertThat(preview.definition().fields().get(1).defaultValue()).isNull();
    }
    @Test void credentialsAndOversizedSourceCannotBecomeTemplates() {
        assertThatThrownBy(() -> imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("[{\"apiKey\":\"secret\"}]"))).hasMessageContaining("凭据");
        assertThatThrownBy(() -> imports.candidates(RunningHubDefinition.TargetType.AI_APP, "123", Task.Kind.IMAGE_GENERATION,
                mapper.readTree("{\"apiKey\":\"secret\",\"nodeInfoList\":[]}"))).hasMessageContaining("凭据");
    }
    @Test void workflowUploadWidgetLabelsDoNotPreventDiscoveryOfTheActualFileBinding() {
        var preview = imports.candidates(RunningHubDefinition.TargetType.WORKFLOW, "2037454919065673729", Task.Kind.VIDEO_GENERATION,
                mapper.readTree("""
                    {"3":{"class_type":"LoadVideo","inputs":{"file":"None","choose video file to upload":"Video"}}}
                    """));
        assertThat(preview.definition().fields()).hasSize(1);
        assertThat(preview.definition().fields().getFirst().fieldName()).isEqualTo("file");
        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("choose video file to upload") && warning.contains("已跳过"));
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
}

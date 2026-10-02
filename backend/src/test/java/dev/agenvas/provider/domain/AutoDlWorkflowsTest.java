package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class AutoDlWorkflowsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    static Stream<AutoDlWorkflows.Workflow> workflows() { return AutoDlWorkflows.ALL.stream(); }
    @ParameterizedTest @MethodSource("workflows")
    void everyWorkflowHasAnExactResolutionAndCorrectRequiredBounds(AutoDlWorkflows.Workflow workflow) {
        assertThat(workflow.resolution(workflow.defaultResolution(), "16:9")).isIn(workflow.resolutions());
        assertThat(workflow.declaration().maxReferenceImages()).isEqualTo(workflow.imageFields().size());
        assertThat(workflow.declaration().maxReferenceAudios()).isEqualTo(workflow.audioFields().size());
        workflow.validate("test", workflow.minimumSeconds(), workflow.mode(), workflow.minimumImages(), workflow.minimumAudios());
        assertThatThrownBy(() -> workflow.validate("test", workflow.maximumSeconds() + 1, workflow.mode(),
                workflow.minimumImages(), workflow.minimumAudios())).hasMessageContaining("工作流输入不匹配");
    }
    @Test void mapsExactEnumsAndRejectsUnsupportedModesSeedsAndIdentifiers() {
        var workflow = AutoDlWorkflows.require("minimax_h3_z0903");
        assertThat(workflow.resolution("768p", "16:9")).isEqualTo("768p横(1376*768)");
        assertThatThrownBy(() -> workflow.resolution("768p", "1:1")).hasMessageContaining("不支持");
        assertThatThrownBy(() -> workflow.validate("test", 1, "GENERAL_REFERENCE", 1, 0))
                .hasMessageContaining("1–3 条音频");
        assertThatThrownBy(() -> AutoDlWorkflows.require("../arbitrary-workflow"))
                .hasMessageContaining("未支持");
        var source = mapper.createObjectNode().put("workflowId", "minimax_h3_lightx2v_no_pic").put("seed", 123);
        assertThatThrownBy(() -> AutoDlWorkflows.normalize(source, mapper.createObjectNode()))
                .hasMessageContaining("随机种子");
        source.put("workflowId", "minimax_h3_z0903").put("seed", AutoDlWorkflows.MAX_SEED);
        var target = mapper.createObjectNode();
        AutoDlWorkflows.normalize(source, target);
        assertThat(target.path("seed").longValue()).isEqualTo(AutoDlWorkflows.MAX_SEED);
        source.put("seed", AutoDlWorkflows.MAX_SEED + 1);
        assertThatThrownBy(() -> AutoDlWorkflows.normalize(source, target)).hasMessageContaining("随机种子");
    }
    @Test void publishesMultipleTiersAndPreservesHistoricalSingleTier() {
        var source = mapper.readTree("""
                {"workflowId":"minimax_h3_z0901","videoResolution":"480p","videoResolutions":["480p","768p"]}
                """);
        var target = mapper.createObjectNode();
        AutoDlWorkflows.normalize(source, target);
        assertThat(AutoDlWorkflows.selectedResolution(target, null)).isEqualTo("480p");
        assertThat(AutoDlWorkflows.selectedResolution(target, "768p")).isEqualTo("768p");
        assertThatThrownBy(() -> AutoDlWorkflows.selectedResolution(target, "1080p")).hasMessageContaining("分辨率");
        AutoDlWorkflows.normalize(mapper.readTree("{\"videoResolution\":\"480p\"}"), target);
        assertThat(target.path("videoResolutions").size()).isEqualTo(1);
        assertThatThrownBy(() -> AutoDlWorkflows.selectedResolution(target, "768p")).hasMessageContaining("分辨率");
    }
    @Test void rejectsEmptyDuplicateUnsupportedTiersAndDefaultOutsidePublishedList() {
        for (String tiers : java.util.List.of("[]", "[\"480p\",\"480p\"]", "[\"1440p\"]", "[\"768p\"]", "[480]")) {
            var source = mapper.readTree("{\"workflowId\":\"minimax_h3_z0901\",\"videoResolution\":\"480p\",\"videoResolutions\":" + tiers + "}");
            assertThatThrownBy(() -> AutoDlWorkflows.normalize(source, mapper.createObjectNode())).hasMessageContaining("分辨率");
        }
    }
}

package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunningHubAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunningHubAdapter adapter = new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION,
            null, null, null, null, null, mapper, Clock.systemUTC());
    private final RunningHubDefinition definition = new RunningHubDefinition(1, "V2", RunningHubDefinition.TargetType.AI_APP,
            "2039199752025280513", List.of(), List.of(), List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.VIDEO, true, 1)),
            "default", false, false, null, null);

    @Test void realProviderNumericStringsAreRecordedWithoutLosingNullOrInferringCurrency() {
        var response = mapper.readTree("""
            {"status":"SUCCESS","results":[{"nodeId":"13","outputType":"mp4","url":"https://custom-cdn.example.com/video.mp4"}],
             "usage":{"consumeMoney":null,"consumeCoins":"3","taskCostTime":"143","thirdPartyConsumeMoney":"0.4","billingSeconds":"4"}}
            """);
        var manifest = adapter.manifest(response, definition, "https://www.runninghub.ai");
        assertThat(manifest.usage().path("consumeMoney").isNull()).isTrue();
        assertThat(manifest.usage().path("consumeCoins").decimalValue()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(manifest.usage().path("taskCostTime").decimalValue()).isEqualByComparingTo(new BigDecimal("143"));
        assertThat(manifest.usage().path("thirdPartyConsumeMoney").decimalValue()).isEqualByComparingTo(new BigDecimal("0.4"));
        assertThat(manifest.usage().has("currency")).isFalse();
        assertThat(manifest.usage().has("billingSeconds")).isFalse();
    }

    @Test void invalidUsageDoesNotCreateMisleadingAmounts() {
        var response = mapper.readTree("""
            {"results":[{"nodeId":"13","outputType":"mp4","url":"https://cdn.example.com/video.mp4"}],
             "usage":{"consumeMoney":"NaN","consumeCoins":"-1","taskCostTime":"1e999999","thirdPartyConsumeMoney":{"amount":1}}}
            """);
        assertThat(adapter.manifest(response, definition, "https://www.runninghub.ai").usage().isEmpty()).isTrue();
    }

    @Test void companionZipIsNeverDownloadedAndDoesNotPreventMappedVideoArchive() {
        var response = mapper.readTree("""
            {"results":[{"nodeId":"157","outputType":"zip","url":"http://127.0.0.1/never-download.zip"},
                        {"nodeId":"155","outputType":"mp4","url":"https://cdn.example.com/video.mp4"}]}
            """);
        var manifest = adapter.manifest(response, definition, "https://www.runninghub.ai");
        assertThat(manifest.results()).hasSize(1);
        assertThat(manifest.results().getFirst().nodeId()).isEqualTo("155");
        assertThat(manifest.results().getFirst().primary()).isTrue();
        assertThat(manifest.results().getFirst().ordinal()).isZero();
        assertThatThrownBy(() -> adapter.manifest(mapper.readTree("{\"results\":[{\"outputType\":\"zip\"}]}"), definition, "https://www.runninghub.ai"))
                .isInstanceOf(dev.agenvas.provider.infrastructure.RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> adapter.manifest(mapper.readTree("{\"results\":[{\"outputType\":\"html\"}]}"), definition, "https://www.runninghub.ai"))
                .isInstanceOf(dev.agenvas.provider.infrastructure.RunningHubClient.ProtocolFailure.class);
    }
}

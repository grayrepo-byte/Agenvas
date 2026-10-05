package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/** Queries saved original task IDs only; this check cannot submit generation. */
@EnabledIfEnvironmentVariable(named = "AGENVAS_RUNNINGHUB_REAL_CALLS", matches = "true")
class RunningHubRealUsageIT {
    @Test void queryOriginalCompletedTasksAndNormalizeTheirRealUsage() throws Exception {
        Path root = Path.of(System.getenv("AGENVAS_RUNNINGHUB_REAL_DIRECTORY"));
        String key = Files.readString(root.resolve("api-key")).strip();
        var mapper = new ObjectMapper();
        var receipt = mapper.readTree(Files.readString(root.resolve("real-evidence.json")));
        var client = new RunningHubClient(mapper);
        var adapter = new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION, null, null, null, null, null, mapper, Clock.systemUTC());
        assertThat(receipt.path("attempts")).hasSize(2);
        for (var attempt : receipt.path("attempts")) {
            assertThat(attempt.path("status").asText()).isEqualTo("SUCCEEDED");
            String id = attempt.path("providerRequestId").asText();
            var response = client.query(receipt.path("origin").asText(), key, id);
            assertThat(response.path("status").asText()).isEqualTo("SUCCESS");
            boolean app = "app".equals(attempt.path("name").asText());
            var definition = new RunningHubDefinition(1, "V2", app ? RunningHubDefinition.TargetType.AI_APP : RunningHubDefinition.TargetType.WORKFLOW,
                    app ? "2039199752025280513" : "2037454919065673729", List.of(), List.of(),
                    List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.VIDEO, true, 1)), "default", false, false, null, null, null, null);
            var manifest = adapter.manifest(response, definition, receipt.path("origin").asText());
            for (String field : List.of("consumeCoins", "taskCostTime", "thirdPartyConsumeMoney")) {
                assertThat(manifest.usage().path(field).isNumber()).isTrue();
                assertThat(manifest.usage().path(field).decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(response.path("usage").path(field).asText()));
            }
            assertThat(manifest.usage().path("consumeMoney").isNull()).isTrue();
            Files.writeString(root.resolve(attempt.path("name").asText() + "-normalized-usage.json"), mapper.writeValueAsString(manifest.usage()));
            System.out.println("REAL_RUNNINGHUB ORIGINAL_USAGE_OK taskId=" + id + " usage=" + manifest.usage());
        }
    }
}

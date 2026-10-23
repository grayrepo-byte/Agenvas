package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;

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
        var mapper = new ObjectMapper();
        var receipt = RunningHubUsageReceipt.parse(mapper.readTree(Files.readString(root.resolve("real-evidence.json"))));
        String key = Files.readString(root.resolve("api-key")).strip();
        var client = new RunningHubClient(mapper);
        var adapter = new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION, null, null, null, null, null, mapper, Clock.systemUTC());
        for (var attempt : receipt.attempts()) {
            String id = attempt.providerRequestId();
            var response = client.query(receipt.origin(), key, id);
            var usage = receipt.normalizedUsage(mapper, adapter, response);
            for (String field : List.of("consumeCoins", "taskCostTime", "thirdPartyConsumeMoney")) {
                assertThat(usage.path(field).isNumber()).isTrue();
                assertThat(usage.path(field).decimalValue()).isEqualByComparingTo(new java.math.BigDecimal(response.path("usage").path(field).asText()));
            }
            assertThat(usage.path("consumeMoney").isNull()).isTrue();
            Files.writeString(root.resolve(attempt.name() + "-normalized-usage.json"), mapper.writeValueAsString(usage));
            System.out.println("REAL_RUNNINGHUB ORIGINAL_USAGE_OK taskId=" + id + " usage=" + usage);
        }
    }
}

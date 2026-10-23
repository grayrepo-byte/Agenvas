package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.task.domain.Task;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RunningHubUsageReceiptTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void selectedTargetReceiptCanContainOneCompletedAttempt() {
        var receipt = RunningHubUsageReceipt.parse(receipt());
        assertThat(receipt.targetType()).isEqualTo(RunningHubDefinition.TargetType.AI_APP);
        assertThat(receipt.targetId()).isEqualTo("12345");
        assertThat(receipt.attempts()).containsExactly(new RunningHubUsageReceipt.Attempt(
                "synthetic-selected-target", "synthetic-original-task"));
    }

    @Test void workflowReceiptCanContainMoreThanTwoOriginalAttempts() {
        var saved = receipt().put("targetType", "WORKFLOW").put("targetId", "67890");
        var attempts = saved.withArray("attempts");
        for (int i = 2; i <= 3; i++) attempts.addObject().put("name", "synthetic-attempt-" + i)
                .put("status", "SUCCEEDED").put("providerRequestId", "synthetic-original-" + i);
        var receipt = RunningHubUsageReceipt.parse(saved);
        assertThat(receipt.targetType()).isEqualTo(RunningHubDefinition.TargetType.WORKFLOW);
        assertThat(receipt.targetId()).isEqualTo("67890");
        assertThat(receipt.attempts()).extracting(RunningHubUsageReceipt.Attempt::providerRequestId)
                .containsExactly("synthetic-original-task", "synthetic-original-2", "synthetic-original-3");
    }

    @ParameterizedTest
    @MethodSource("invalidTargets")
    void missingOrInvalidTargetIsRejectedBeforeQuery(String field, String invalidJson) {
        var receipt = receipt();
        receipt.set(field, mapper.readTree(invalidJson));
        assertThatThrownBy(() -> RunningHubUsageReceipt.parse(receipt)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidTargets() {
        return Stream.of(Arguments.of("targetType", "null"), Arguments.of("targetType", "1"),
                Arguments.of("targetType", "\"unsupported\""), Arguments.of("targetId", "null"),
                Arguments.of("targetId", "12345"), Arguments.of("targetId", "\"\""),
                Arguments.of("targetId", "\"invalid-target\""), Arguments.of("targetId", "\"" + "1".repeat(33) + "\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "null"})
    void attemptsMustBeANonemptyArray(String invalidJson) {
        var receipt = receipt();
        receipt.set("attempts", mapper.readTree(invalidJson));
        assertThatThrownBy(() -> RunningHubUsageReceipt.parse(receipt)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "123", "\"\"", "\"invalid/task\""})
    void originalProviderTaskIdMustBeSavedAndValid(String invalidJson) {
        var receipt = receipt();
        ((ObjectNode) receipt.path("attempts").get(0)).set("providerRequestId", mapper.readTree(invalidJson));
        assertThatThrownBy(() -> RunningHubUsageReceipt.parse(receipt)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "WAITING_PROVIDER", "FAILED"})
    void allAttemptsMustHaveSucceededBeforeAnyAreQueried(String status) {
        var receipt = receipt();
        receipt.withArray("attempts").addObject().put("name", "synthetic-later-attempt")
                .put("providerRequestId", "synthetic-later-original").put("status", status);
        assertThatThrownBy(() -> RunningHubUsageReceipt.parse(receipt)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void companionZipBeforeVideoDoesNotDownloadAndRealUsageStillNormalizes() {
        var client = mock(RunningHubClient.class);
        var adapter = adapter(client);
        var receipt = RunningHubUsageReceipt.parse(receipt());
        var usage = receipt.normalizedUsage(mapper, adapter, response());
        assertThat(usage.path("consumeCoins").decimalValue()).isEqualByComparingTo(new BigDecimal("3.25"));
        assertThat(usage.path("taskCostTime").decimalValue()).isEqualByComparingTo(new BigDecimal("143"));
        assertThat(usage.path("thirdPartyConsumeMoney").decimalValue()).isEqualByComparingTo(new BigDecimal("0.4"));
        assertThat(usage.path("consumeMoney").isNull()).isTrue();
        verifyNoInteractions(client);
    }

    @Test void zipOnlyResponseFailsUsageCheckWithoutDownloading() {
        var client = mock(RunningHubClient.class);
        var response = response();
        response.withArray("results").remove(1);
        var receipt = RunningHubUsageReceipt.parse(receipt());
        assertThatThrownBy(() -> receipt.normalizedUsage(mapper, adapter(client), response))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("direct video");
        verifyNoInteractions(client);
    }

    @Test void originalQueryMustStillReportSuccess() {
        var client = mock(RunningHubClient.class);
        var receipt = RunningHubUsageReceipt.parse(receipt());
        assertThatThrownBy(() -> receipt.normalizedUsage(mapper, adapter(client), response().put("status", "RUNNING")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has not succeeded");
        verifyNoInteractions(client);
    }

    private ObjectNode receipt() {
        return (ObjectNode) mapper.readTree("""
                {"origin":"https://www.runninghub.ai","targetType":"AI_APP","targetId":"12345",
                 "attempts":[{"name":"synthetic-selected-target","status":"SUCCEEDED","providerRequestId":"synthetic-original-task"}]}
                """);
    }

    private ObjectNode response() {
        return (ObjectNode) mapper.readTree("""
                {"status":"SUCCESS","results":[
                 {"nodeId":"20","outputType":"zip","url":"https://cdn.example.com/companion.zip"},
                 {"nodeId":"21","outputType":"mp4","url":"https://cdn.example.com/video.mp4"}],
                 "usage":{"consumeMoney":null,"consumeCoins":"3.25","taskCostTime":"143","thirdPartyConsumeMoney":"0.4"}}
                """);
    }

    private RunningHubAdapter adapter(RunningHubClient client) {
        return new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION,
                null, null, null, null, client, mapper, Clock.systemUTC());
    }
}

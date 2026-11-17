package dev.agenvas.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.llm.application.LlmModeProperties;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.domain.UsageEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

class UsageMediaPricingTest {
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final UsageRepository ledger = mock(UsageRepository.class);
    private final List<UsageEntry> entries = new ArrayList<>();

    @Test
    void imageTokensSettleSeparateRatesFromReportedUsageAndNeverInventActualCharges() {
        UsageService service = service();
        Task original = tokenTask();
        service.reserveMediaTask(UUID.randomUUID(), original, "PROVIDER_UNPRICED", 7);
        assertThat(entries.getFirst().estimatedCost()).isNull();
        Task completed = withUsage(original, """
                {"inputTokens":300,"textInputTokens":100,"imageInputTokens":200,
                 "outputTokens":400,"totalTokens":700,"valid":true}
                """);
        service.settleMediaTask(UUID.randomUUID(), completed);
        UsageEntry entry = entries.getLast();
        assertThat(entry.estimatedCost()).isEqualByComparingTo("0.014100");
        assertThat(entry.actualCost()).isNull();
        assertThat(entry.quantity().path("inputTokens").asInt()).isEqualTo(300);
        assertThat(entry.quantity().path("textInputTokens").asInt()).isEqualTo(100);
        assertThat(entry.quantity().path("imageInputTokens").asInt()).isEqualTo(200);
        assertThat(entry.quantity().path("cachedInputTokens").isNull()).isTrue();
        assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.ESTIMATED);
    }

    @Test
    void cachedModalityTokensAreSubtractedBeforeDiscounting() {
        UsageService service = service();
        Task task = tokenTask();
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(UUID.randomUUID(), withUsage(task, """
                {"inputTokens":300,"textInputTokens":100,"imageInputTokens":200,"outputTokens":400,
                 "totalTokens":700,"cachedInputTokens":120,"cachedTextInputTokens":40,
                 "cachedImageInputTokens":80,"valid":true}
                """));
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("0.013470");
    }

    @Test
    void missingMalformedInconsistentOrUnallocatableUsageCannotBecomeZeroOrAnImageCharge() {
        UsageService service = service();
        for (String usage : List.of("{}", "{\"valid\":false}",
                "{\"inputTokens\":300,\"textInputTokens\":100,\"imageInputTokens\":201,\"outputTokens\":400,\"valid\":true}",
                "{\"inputTokens\":300,\"textInputTokens\":100,\"imageInputTokens\":200,\"outputTokens\":400,\"cachedInputTokens\":120,\"valid\":true}",
                "{\"inputTokens\":300,\"textInputTokens\":100,\"imageInputTokens\":200,\"outputTokens\":400,\"textOutputTokens\":1,\"valid\":true}")) {
            Task task = tokenTask();
            service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7);
            service.settleMediaTask(UUID.randomUUID(), withUsage(task, usage));
            assertThat(entries.getLast().estimatedCost()).isNull();
            assertThat(entries.getLast().actualCost()).isNull();
            assertThat(entries.getLast().costStatus()).isEqualTo(UsageEntry.CostStatus.UNKNOWN);
        }
    }

    private Task tokenTask() {
        Task task = task(Task.Kind.IMAGE_GENERATION, null);
        ((tools.jackson.databind.node.ObjectNode) task.input()).set("mediaTokenPricing", mapper.readTree("""
                {"currency":"USD","textInputPerMillion":"5","imageInputPerMillion":"8",
                 "imageOutputPerMillion":"30","cachedTextInputPerMillion":"1.25","cachedImageInputPerMillion":"2"}
                """));
        return task;
    }

    private Task withUsage(Task task, String usage) {
        var output = mapper.createObjectNode().set("providerUsage", mapper.readTree(usage));
        return new Task(task.id(), task.projectId(), task.runId(), task.stepKey(), task.kind(), task.status(),
                task.cancelRequested(), task.input(), task.inputHash(), output, task.providerRequestId(),
                task.attemptNo(),
                task.nextActionAt(), task.leaseOwner(), task.leaseUntil(), task.leaseEpoch(), task.version(),
                task.errorCode(),
                task.createdAt(), task.updatedAt(), task.completedAt());
    }

    @Test
    void settlementRetainsPinnedSecondEstimateAndNeverInventsAnActualCharge() {
        UsageService service = service();
        Task task = task(Task.Kind.VIDEO_GENERATION, """
                {"amount":"0.123456","currency":"CNY","unit":"SECOND"}
                """);
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(UUID.randomUUID(), task);
        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.estimatedCost()).isEqualByComparingTo("0.987648");
            assertThat(entry.actualCost()).isNull();
            assertThat(entry.currency()).isEqualTo("CNY");
            assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.ESTIMATED);
            assertThat(entry.costSource()).isEqualTo("ADMIN_CONFIGURED");
            assertThat(entry.providerConfigVersion()).isEqualTo(7);
            assertThat(entry.quantity().has("exportCount")).isFalse();
        });
    }

    @Test
    void missingPricesRemainUnknownInReservationAndSettlement() {
        UsageService service = service();
        Task task = task(Task.Kind.IMAGE_GENERATION, null);
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(UUID.randomUUID(), task);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.estimatedCost()).isNull();
            assertThat(entry.actualCost()).isNull();
            assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.UNKNOWN);
        });
    }

    @Test
    void relayPreparationFailureReleasesTheOriginalReservationWithoutSettlement() {
        UsageService service = service();
        UUID owner = UUID.randomUUID();
        Task original = task(Task.Kind.IMAGE_GENERATION, null);
        service.reserveMediaTask(owner, original, "PROVIDER_UNPRICED", 7);
        Task blocked = new Task(original.id(), original.projectId(), null, original.stepKey(),
                original.kind(), Task.Status.BLOCKED, false, original.input(), original.inputHash(),
                null, null, original.attemptNo(), NOW, null, null, original.leaseEpoch(),
                original.version() + 1, ProviderFailureCodes.MEDIA_RELAY_PREPARATION_FAILED,
                original.createdAt(), NOW, null);

        service.releaseUnsubmittedMediaTask(owner, blocked);

        assertThat(entries).extracting(UsageEntry::entryType)
                .containsExactly(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.RELEASE);
        assertThat(entries.getLast().operationKey()).isEqualTo("media:" + original.id() + ":release");
        assertThat(entries.getLast().costStatus()).isEqualTo(UsageEntry.CostStatus.UNKNOWN);
    }

    @Test
    void localOperationsRemainKnownZeroEvenIfCloudPricingIsPresent() {
        UsageService service = service();
        Task task = task(Task.Kind.IMAGE_GENERATION,
                "{\"amount\":\"1\",\"currency\":\"USD\",\"unit\":\"IMAGE\"}");
        service.reserveMediaTask(UUID.randomUUID(), task, "LOCAL_NO_COST", 7);
        assertThat(entries.getFirst().estimatedCost()).isZero();
        assertThat(entries.getFirst().actualCost()).isZero();
        assertThat(entries.getFirst().costStatus()).isEqualTo(UsageEntry.CostStatus.KNOWN);
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,AUDIO_TRIM_INVALID", "BLOCKED,AUDIO_TRIM_OUTPUT_TOO_LARGE",
            "FAILED,LOCAL_AUDIO_TRIM_TIMEOUT", "FAILED,LOCAL_VIDEO_COMPOSITION_FAILED"})
    void confirmedLocalFailureReleasesKnownZeroWithoutSettlement(Task.Status status, String code) {
        UsageService service = service();
        UUID owner = UUID.randomUUID();
        Task original = task(Task.Kind.AUDIO_GENERATION, null);
        service.reserveMediaTask(owner, original, "LOCAL_NO_COST", 1);
        Task failed = new Task(original.id(), original.projectId(), null, original.stepKey(), original.kind(), status,
                false, original.input(), original.inputHash(), null, null, 1, NOW, null, null, 1, 1, code, NOW, NOW,
                NOW);
        service.releaseUnsubmittedMediaTask(owner, failed);
        assertThat(entries).extracting(UsageEntry::entryType)
                .containsExactly(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.RELEASE);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.costSource()).isEqualTo("LOCAL_NO_COST");
            assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.KNOWN);
            assertThat(entry.actualCost()).isZero();
            assertThat(entry.estimatedCost()).isZero();
        });
    }

    @Test
    void approvedAgentMediaReservesAndSettlesUnderItsRun() {
        UsageService service = service();
        Task task = withRun(task(Task.Kind.AUDIO_GENERATION, null), UUID.randomUUID());
        ((tools.jackson.databind.node.ObjectNode) task.input()).put(Task.APPROVAL_INPUT_PROPERTY,
                UUID.randomUUID().toString());
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(UUID.randomUUID(), task);
        assertThat(entries).hasSize(2).allSatisfy(entry -> assertThat(entry.runId()).isEqualTo(task.runId()));
    }

    @Test
    void unapprovedRunCannotReserveMediaEvenWithAMalformedApprovalMarker() {
        UsageService service = service();
        Task task = withRun(task(Task.Kind.IMAGE_GENERATION, null), UUID.randomUUID());
        assertThatThrownBy(() -> service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7))
                .isInstanceOf(IllegalArgumentException.class);
        ((tools.jackson.databind.node.ObjectNode) task.input()).put(Task.APPROVAL_INPUT_PROPERTY, "untrusted");
        assertThatThrownBy(() -> service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entries).isEmpty();
    }

    private Task withRun(Task task, UUID runId) {
        return new Task(task.id(), task.projectId(), runId, task.stepKey(), task.kind(), task.status(),
                task.cancelRequested(), task.input(), task.inputHash(), task.output(),
                task.providerRequestId(), task.attemptNo(), task.nextActionAt(), task.leaseOwner(),
                task.leaseUntil(), task.leaseEpoch(), task.version(), task.errorCode(), task.createdAt(),
                task.updatedAt(), task.completedAt());
    }

    @Test
    void klingBatchReservesRequestedImagesAndSettlesArchivedCount() {
        UsageService service = service();
        Task task = task(Task.Kind.IMAGE_GENERATION, "{\"amount\":\"0.2\",\"currency\":\"CNY\",\"unit\":\"IMAGE\"}");
        var input = (tools.jackson.databind.node.ObjectNode) task.input();
        input.put("providerProtocol", "KLING").put("expectedImageCount", 4);
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(UUID.randomUUID(), task, 3, null);
        assertThat(entries.getFirst().quantity().path("imageCount").asInt()).isEqualTo(4);
        assertThat(entries.getFirst().estimatedCost()).isEqualByComparingTo("0.8");
        assertThat(entries.getLast().quantity().path("imageCount").asInt()).isEqualTo(3);
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("0.6");
        assertThat(entries.getLast().actualCost()).isNull();
    }

    @Test
    void klingAutoSeriesAndMotionKeepUnknownReservationsUntilArchive() {
        UsageService service = service();
        Task image = task(Task.Kind.IMAGE_GENERATION, "{\"amount\":\"0.2\",\"currency\":\"CNY\",\"unit\":\"IMAGE\"}");
        ((tools.jackson.databind.node.ObjectNode) image.input()).put("providerProtocol", "KLING")
                .putNull("expectedImageCount");
        service.reserveMediaTask(UUID.randomUUID(), image, "PROVIDER_UNPRICED", 7);
        assertThat(entries.getLast().estimatedCost()).isNull();
        assertThat(entries.getLast().quantity().path("imageCount").isNull()).isTrue();
        service.settleMediaTask(UUID.randomUUID(), image, 7, null);
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("1.4");
        Task video = task(Task.Kind.VIDEO_GENERATION, "{\"amount\":\"0.1\",\"currency\":\"CNY\",\"unit\":\"SECOND\"}");
        var input = (tools.jackson.databind.node.ObjectNode) video.input();
        input.put("providerProtocol", "KLING");
        input.remove("durationSeconds");
        service.reserveMediaTask(UUID.randomUUID(), video, "PROVIDER_UNPRICED", 7);
        assertThat(entries.getLast().estimatedCost()).isNull();
        assertThat(entries.getLast().quantity().path("videoSeconds").isNull()).isTrue();
        service.settleMediaTask(UUID.randomUUID(), video, 0, new java.math.BigDecimal("4.321"));
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("0.4321");
        Task precise = task(Task.Kind.VIDEO_GENERATION,
                "{\"amount\":\"0.000001\",\"currency\":\"CNY\",\"unit\":\"SECOND\"}");
        ((tools.jackson.databind.node.ObjectNode) precise.input()).put("providerProtocol", "KLING");
        service.reserveMediaTask(UUID.randomUUID(), precise, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(UUID.randomUUID(), precise, 0, new java.math.BigDecimal("5.033"));
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("0.000005");
    }

    @Test
    void nativeReferenceVideoReservationIncludesPinnedInputSecondsAndSettlementUsesActualTotal() {
        UsageService service = service();
        Task video = task(Task.Kind.VIDEO_GENERATION, "{\"amount\":\"0.1\",\"currency\":\"CNY\",\"unit\":\"SECOND\"}");
        ((tools.jackson.databind.node.ObjectNode) video.input()).put("providerProtocol", "NATIVE_MEDIA")
                .put("nativeBillableInputSeconds", new java.math.BigDecimal("3.250"));
        service.reserveMediaTask(UUID.randomUUID(), video, "PROVIDER_UNPRICED", 7);
        assertThat(entries.getFirst().quantity().path("videoSeconds").asText()).isEqualTo("11.250");
        assertThat(entries.getFirst().estimatedCost()).isEqualByComparingTo("1.125");
        service.settleMediaTask(UUID.randomUUID(), video, 0, new java.math.BigDecimal("9.750"));
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("0.975");
        assertThat(entries.getLast().actualCost()).isNull();
    }

    @Test
    void settlementRequiresItsOwnReservationAndCannotBorrowAnotherTasksCostSource() {
        UsageService service = service();
        UUID owner = UUID.randomUUID();
        Task local = task(Task.Kind.IMAGE_GENERATION, null);
        service.reserveMediaTask(owner, local, "LOCAL_NO_COST", 1);
        Task cloud = task(Task.Kind.IMAGE_GENERATION,
                "{\"amount\":\"0.2\",\"currency\":\"CNY\",\"unit\":\"IMAGE\"}");

        assertThatThrownBy(() -> service.settleMediaTask(owner, cloud))
                .isInstanceOf(IllegalStateException.class);
        assertThat(entries).hasSize(1);

        service.reserveMediaTask(owner, cloud, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(owner, cloud);
        assertThat(entries.getLast().taskId()).isEqualTo(cloud.id());
        assertThat(entries.getLast().costSource()).isEqualTo("ADMIN_CONFIGURED");
        assertThat(entries.getLast().providerConfigVersion()).isEqualTo(7);
        assertThat(entries.getLast().estimatedCost()).isEqualByComparingTo("0.2");
        assertThat(entries.getLast().actualCost()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,TASK_INPUT_STALE", "BLOCKED,TASK_PROJECT_ARCHIVED", "BLOCKED,MEDIA_CAPABILITY_CHANGED",
            "BLOCKED,PROVIDER_UNSUPPORTED_CAPABILITY", "BLOCKED,PROVIDER_UNSUPPORTED_INPUT",
            "BLOCKED,MEDIA_CREDENTIAL_UNAVAILABLE", "BLOCKED,RUNNINGHUB_INPUT_UNAVAILABLE",
            "BLOCKED,LOCAL_DEPTH_MODEL_UNAVAILABLE", "BLOCKED,VIDEO_AUDIO_TRACK_MISSING",
            "BLOCKED,AUDIO_TRIM_INVALID", "BLOCKED,AUDIO_TRIM_OUTPUT_TOO_LARGE",
            "BLOCKED,SEEDANCE_VIDEO_REFERENCE_INVALID", "BLOCKED,MEDIA_RELAY_REQUIRED",
            "BLOCKED,MEDIA_RELAY_PREPARATION_FAILED", "BLOCKED,MEDIA_RELAY_PUBLIC_ENDPOINT_REQUIRED",
            "CANCELED,", "FAILED,"})
    void releasesOnlyConfirmedUnsubmittedOutcomes(Task.Status status, String code) {
        UsageService service = service();
        UUID owner = UUID.randomUUID();
        Task original = task(Task.Kind.IMAGE_GENERATION, null);
        service.reserveMediaTask(owner, original, "PROVIDER_UNPRICED", 7);
        service.releaseUnsubmittedMediaTask(owner, outcome(original, original.kind(), status, code, null));
        assertThat(entries).extracting(UsageEntry::entryType)
                .containsExactly(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.RELEASE);
        assertThat(entries.getLast().operationKey()).isEqualTo("media:" + original.id() + ":release");
        assertThat(entries.getLast().costStatus()).isEqualTo(UsageEntry.CostStatus.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"IMAGE_GENERATION,READY,TASK_INPUT_STALE,", "IMAGE_GENERATION,RUNNING,TASK_INPUT_STALE,",
            "IMAGE_GENERATION,SUBMITTING,TASK_INPUT_STALE,", "IMAGE_GENERATION,WAITING_PROVIDER,TASK_INPUT_STALE,",
            "IMAGE_GENERATION,UNKNOWN,TASK_INPUT_STALE,", "IMAGE_GENERATION,SUCCEEDED,TASK_INPUT_STALE,",
            "IMAGE_GENERATION,BLOCKED,,", "IMAGE_GENERATION,BLOCKED,UNLISTED_FAILURE,",
            "TEXT_GENERATION,CANCELED,,", "AGENT_TURN,FAILED,,",
            "IMAGE_GENERATION,BLOCKED,TASK_INPUT_STALE,accepted-request",
            "VIDEO_GENERATION,CANCELED,,accepted-request", "AUDIO_GENERATION,FAILED,,accepted-request"})
    void rejectsUncertainSubmittedOrIneligibleReleases(Task.Kind kind, Task.Status status, String code,
            String requestId) {
        UsageService service = service();
        UUID owner = UUID.randomUUID();
        Task original = task(Task.Kind.IMAGE_GENERATION, null);
        service.reserveMediaTask(owner, original, "PROVIDER_UNPRICED", 7);
        assertThatThrownBy(
                () -> service.releaseUnsubmittedMediaTask(owner, outcome(original, kind, status, code, requestId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Media release requires unsubmitted terminal work");
        assertThat(entries).extracting(UsageEntry::entryType).containsExactly(UsageEntry.EntryType.RESERVATION);
    }

    @ParameterizedTest
    @CsvSource({"AUDIO_GENERATION", "VIDEO_GENERATION", "IMAGE_GENERATION"})
    void aSettledMediaReservationCannotBeReleased(Task.Kind kind) {
        UsageService service = service();
        UUID owner = UUID.randomUUID();
        Task original = task(kind, null);
        service.reserveMediaTask(owner, original, "PROVIDER_UNPRICED", 7);
        service.settleMediaTask(owner, original);
        assertThatThrownBy(() -> service.releaseUnsubmittedMediaTask(owner,
                outcome(original, kind, Task.Status.CANCELED, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("A settled media Task cannot release its reservation");
        assertThat(entries).extracting(UsageEntry::entryType)
                .containsExactly(UsageEntry.EntryType.RESERVATION, UsageEntry.EntryType.SETTLEMENT);
    }

    private Task outcome(Task original, Task.Kind kind, Task.Status status, String code, String requestId) {
        return new Task(original.id(), original.projectId(), original.runId(), original.stepKey(), kind, status,
                original.cancelRequested(), original.input(), original.inputHash(), original.output(), requestId,
                original.attemptNo(), original.nextActionAt(), original.leaseOwner(), original.leaseUntil(),
                original.leaseEpoch(), original.version() + 1, code, original.createdAt(), NOW, original.completedAt());
    }

    private UsageService service() {
        when(ledger.insertOnce(any())).thenAnswer(invocation -> {
            entries.add(invocation.getArgument(0));
            return true;
        });
        when(ledger.findByOperationKey(anyString())).thenAnswer(invocation -> entries.stream()
                .filter(entry -> entry.operationKey().equals(invocation.getArgument(0))).findFirst());
        return new UsageService(ledger, mock(ProjectService.class), mock(ProjectEventService.class),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), mock(LlmModeProperties.class));
    }

    private Task task(Task.Kind kind, String price) {
        var input = mapper.createObjectNode().put("schemaVersion", 3)
                .put("workflowVersion", "TEST:version-1").put("durationSeconds", 8);
        if (price != null)
            input.set("mediaPricing", mapper.readTree(price));
        return new Task(UUID.randomUUID(), UUID.randomUUID(), null, "test", kind, Task.Status.READY,
                false, input, "hash", null, null, 1, NOW, null, null, 0, 0, null, NOW, NOW, null);
    }
}

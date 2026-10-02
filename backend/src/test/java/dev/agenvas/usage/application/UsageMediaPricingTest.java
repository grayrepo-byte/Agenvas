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
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.domain.UsageEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class UsageMediaPricingTest {
    private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final UsageRepository ledger = mock(UsageRepository.class);
    private final List<UsageEntry> entries = new ArrayList<>();

    @Test
    void settlementRetainsPinnedSecondEstimateAndNeverInventsAnActualCharge() {
        UsageService service = service();
        Task task = task(Task.Kind.VIDEO_GENERATION, """
                {"amount":"0.123456","currency":"CNY","unit":"SECOND"}
                """);
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED");
        service.settleMediaTask(UUID.randomUUID(), task);
        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.estimatedCost()).isEqualByComparingTo("0.987648");
            assertThat(entry.actualCost()).isNull();
            assertThat(entry.currency()).isEqualTo("CNY");
            assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.ESTIMATED);
            assertThat(entry.costSource()).isEqualTo("ADMIN_CONFIGURED");
        });
    }

    @Test
    void missingPricesRemainUnknownInReservationAndSettlement() {
        UsageService service = service();
        Task task = task(Task.Kind.IMAGE_GENERATION, null);
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED");
        service.settleMediaTask(UUID.randomUUID(), task);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.estimatedCost()).isNull();
            assertThat(entry.actualCost()).isNull();
            assertThat(entry.costStatus()).isEqualTo(UsageEntry.CostStatus.UNKNOWN);
        });
    }

    @Test
    void localOperationsRemainKnownZeroEvenIfCloudPricingIsPresent() {
        UsageService service = service();
        Task task = task(Task.Kind.IMAGE_GENERATION,
                "{\"amount\":\"1\",\"currency\":\"USD\",\"unit\":\"IMAGE\"}");
        service.reserveMediaTask(UUID.randomUUID(), task, "LOCAL_NO_COST");
        assertThat(entries.getFirst().estimatedCost()).isZero();
        assertThat(entries.getFirst().actualCost()).isZero();
        assertThat(entries.getFirst().costStatus()).isEqualTo(UsageEntry.CostStatus.KNOWN);
    }

    @Test
    void approvedAgentMediaReservesAndSettlesUnderItsRun() {
        UsageService service = service();
        Task task = withRun(task(Task.Kind.AUDIO_GENERATION, null), UUID.randomUUID());
        ((tools.jackson.databind.node.ObjectNode) task.input()).put(Task.APPROVAL_INPUT_PROPERTY,
                UUID.randomUUID().toString());
        service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED");
        service.settleMediaTask(UUID.randomUUID(), task);
        assertThat(entries).hasSize(2).allSatisfy(entry -> assertThat(entry.runId()).isEqualTo(task.runId()));
    }

    @Test
    void unapprovedRunCannotReserveMediaEvenWithAMalformedApprovalMarker() {
        UsageService service = service();
        Task task = withRun(task(Task.Kind.IMAGE_GENERATION, null), UUID.randomUUID());
        assertThatThrownBy(() -> service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED"))
                .isInstanceOf(IllegalArgumentException.class);
        ((tools.jackson.databind.node.ObjectNode) task.input()).put(Task.APPROVAL_INPUT_PROPERTY, "untrusted");
        assertThatThrownBy(() -> service.reserveMediaTask(UUID.randomUUID(), task, "PROVIDER_UNPRICED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entries).isEmpty();
    }

    private Task withRun(Task task, UUID runId) {
        return new Task(task.id(), task.projectId(), runId, task.stepKey(), task.kind(), task.status(),
                task.cancelRequested(), task.input(), task.inputHash(), task.output(), task.providerId(),
                task.providerRequestId(), task.attemptNo(), task.nextActionAt(), task.leaseOwner(),
                task.leaseUntil(), task.leaseEpoch(), task.version(), task.errorCode(), task.createdAt(),
                task.updatedAt(), task.completedAt());
    }

    private UsageService service() {
        when(ledger.insertOnce(any())).thenAnswer(invocation -> {
            entries.add(invocation.getArgument(0)); return true;
        });
        when(ledger.findByOperationKey(anyString())).thenAnswer(invocation ->
                Optional.of(entries.getFirst()));
        return new UsageService(ledger, mock(ProjectService.class), mock(ProjectEventService.class),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), mock(LlmModeProperties.class));
    }

    private Task task(Task.Kind kind, String price) {
        var input = mapper.createObjectNode().put("schemaVersion", 3).put("providerConfigVersion", 1)
                .put("workflowVersion", "TEST:version-1").put("durationSeconds", 8);
        if (price != null) input.set("mediaPricing", mapper.readTree(price));
        return new Task(UUID.randomUUID(), UUID.randomUUID(), null, "test", kind, Task.Status.READY,
                false, input, "hash", null, null, null, 1, NOW, null, null, 0, 0, null, NOW, NOW, null);
    }
}

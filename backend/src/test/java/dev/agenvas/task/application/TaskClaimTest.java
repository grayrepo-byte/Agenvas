package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import dev.agenvas.task.domain.Task;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import tools.jackson.databind.ObjectMapper;

/** Repository calls are mocked; PostgreSQL tests cover task selection and fencing. */
class TaskClaimTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(45);
    private static final int BATCH_LIMIT = 3;
    private final List<Task> claimed = List.of(mock(Task.class));
    private final TaskRepository repository = mock(TaskRepository.class, call ->
            call.getMethod().getReturnType() == List.class ? claimed : Answers.RETURNS_DEFAULTS.answer(call));
    private final ShutdownGate gate = new ShutdownGate();
    private final TaskService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(Route.class)
    void everyBatchRoutePreservesItsSelectorAndReceivesOneBoundedLease(Route route) {
        assertThat(claim(route, "  worker  ", Integer.MAX_VALUE)).isSameAs(claimed);
        assertThat(mockingDetails(repository).getInvocations()).singleElement().satisfies(call -> {
            assertThat(call.getMethod().getName()).isEqualTo(route.repositoryMethod);
            assertThat(call.getArguments()).containsExactly("worker", BATCH_LIMIT, NOW, NOW.plus(LEASE));
        });
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"BOUND_MEDIA", "BOUND_POLLS"}, mode = EnumSource.Mode.EXCLUDE)
    void genericAndTextRoutesSkipValidationWhenShutdownHasAlreadyStarted(Route route) {
        gate.onContextClosed(null);
        assertThat(claim(route, null, -1)).isEmpty();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = Route.class, names = {"BOUND_MEDIA", "BOUND_POLLS"})
    void boundMediaRoutesKeepValidationBeforeTheShutdownGate(Route route) {
        gate.onContextClosed(null);
        assertThatThrownBy(() -> claim(route, null, 1))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("VALIDATION_ERROR"));
        assertThat(claim(route, "worker", 1)).isEmpty();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nonPositiveLimitsNeverReachTheRepository(int limit) {
        assertThatThrownBy(() -> service.claimDue("worker", limit))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(repository);
    }

    @Test
    void smallRequestsKeepTheirRequestedBatchSize() {
        service.claimDue("worker", 1);
        assertThat(mockingDetails(repository).getInvocations()).singleElement()
                .satisfies(call -> assertThat(call.getArguments()).containsExactly("worker", 1, NOW, NOW.plus(LEASE)));
    }

    @Test
    void shutdownDuringLeasePreparationPreventsTheQuery() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> { gate.onContextClosed(null); return NOW; });
        assertThat(service(clock).claimDue("worker", 1)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void overlongWorkerIdsRemainInvalid() {
        assertThatThrownBy(() -> service.claimDue("x".repeat(161), 1))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> assertThat(failure.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(repository);
    }

    private TaskService service(Clock clock) {
        return new TaskService(mock(AgentRunService.class), mock(ProjectService.class),
                mock(ArtifactService.class), mock(MediaDraftService.class), mock(CanvasService.class),
                repository, new TaskProperties(LEASE, BATCH_LIMIT), mock(ProjectEventService.class),
                mock(UsageService.class), new ObjectMapper(), clock, gate);
    }

    private List<Task> claim(Route route, String worker, int limit) {
        return switch (route) {
            case DUE -> service.claimDue(worker, limit);
            case BOUND_MEDIA -> service.claimBoundMedia(worker, limit);
            case BOUND_POLLS -> service.claimBoundMediaPolls(worker, limit);
            case PROVIDER_POLLS -> service.claimProviderPolls(worker, limit);
            case AGENT -> service.claimAgentTurns(worker, limit);
            case TEXT -> service.claimTextGenerations(worker, limit);
        };
    }

    private enum Route {
        DUE("claimDue"), BOUND_MEDIA("claimDueBoundMedia"), BOUND_POLLS("claimDueBoundMediaPolls"),
        PROVIDER_POLLS("claimDueProviderPolls"),
        AGENT("claimDueAgentTurns"), TEXT("claimDueTextGenerations");

        private final String repositoryMethod;
        Route(String repositoryMethod) { this.repositoryMethod = repositoryMethod; }
    }
}

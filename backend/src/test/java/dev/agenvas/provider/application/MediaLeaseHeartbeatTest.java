package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import dev.agenvas.task.application.TaskProperties;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Real bounded timer threads; PostgreSQL tests verify cancellation and fencing predicates. */
class MediaLeaseHeartbeatTest {
    private final TaskService tasks = mock(TaskService.class);
    private final TaskProperties properties = new TaskProperties(Duration.ofMillis(90), 4);

    @Test
    void aBlockedProviderOperationRenewsOnADedicatedThreadAndRemovesItsTimerWhenFinished() throws Exception {
        var heartbeat = new CountDownLatch(1);
        var caller = Thread.currentThread();
        var renewalThread = new AtomicReference<Thread>();
        doAnswer(call -> {
            renewalThread.set(Thread.currentThread());
            heartbeat.countDown();
            return null;
        }).when(tasks).heartbeatMediaLease(any(), anyString(), anyLong());
        var timers = new ScheduledThreadPoolExecutor(1);
        try (var leases = new MediaLeaseHeartbeat(tasks, properties, timers)) {
            try (var scope = leases.open(task(), "media-worker")) {
                assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(renewalThread.get()).isNotSameAs(caller);
                scope.checkActive();
            }
            assertThat(timers.getQueue()).isEmpty();
        }
        assertThat(timers.isShutdown()).isTrue();
    }

    @Test
    void failedRenewalStopsItsTimerAndCannotBeMistakenForAProviderFailureOrRetry() throws Exception {
        var attempted = new CountDownLatch(1);
        doAnswer(call -> {
            attempted.countDown();
            throw new IllegalStateException("Lease could not be confirmed");
        }).when(tasks).heartbeatMediaLease(any(), anyString(), anyLong());
        var timers = new ScheduledThreadPoolExecutor(1);
        try (var leases = new MediaLeaseHeartbeat(tasks, properties, timers);
                var scope = leases.open(task(), "media-worker")) {
            assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
            // A scheduler barrier ensures the failed renewal has installed its local fence.
            timers.submit(() -> {
            }).get(2, TimeUnit.SECONDS);
            assertThatThrownBy(scope::checkActive).isInstanceOf(MediaLeaseHeartbeat.LeaseLost.class);
            assertThat(timers.getQueue()).isEmpty();
        }
    }

    @Test
    void capacityIsBoundedAndClosingStopsScopesWithoutCreatingMoreTimers() {
        var timers = new ScheduledThreadPoolExecutor(1);
        var leases = new MediaLeaseHeartbeat(tasks, new TaskProperties(Duration.ofHours(1), 4), timers);
        var scopes = new ArrayList<MediaLeaseHeartbeat.Scope>();
        try {
            for (int index = 0; index < 4; index++)
                scopes.add(leases.open(task(), "media-worker-" + index));
            assertThatThrownBy(() -> leases.open(task(), "extra-worker"))
                    .isInstanceOf(MediaLeaseHeartbeat.LeaseLost.class);
            assertThat(timers.getQueue()).hasSize(4);
            scopes.removeFirst().close();
            scopes.add(leases.open(task(), "replacement-worker"));
            assertThat(timers.getQueue()).hasSize(4);
        } finally {
            leases.close();
        }
        scopes.forEach(scope -> assertThatThrownBy(scope::checkActive)
                .isInstanceOf(MediaLeaseHeartbeat.LeaseLost.class));
        assertThat(timers.getQueue()).isEmpty();
        assertThatThrownBy(() -> leases.open(task(), "closed-worker"))
                .isInstanceOf(MediaLeaseHeartbeat.LeaseLost.class);
    }

    private Task task() {
        var now = Instant.now();
        return new Task(UUID.randomUUID(), UUID.randomUUID(), null, "synthetic-submission",
                Task.Kind.IMAGE_GENERATION, Task.Status.SUBMITTING, true,
                new ObjectMapper().createObjectNode(), "frozen-input", null, null,
                1, now, "media-worker", now.plusSeconds(1), 2, 3, null, now, now, null);
    }
}

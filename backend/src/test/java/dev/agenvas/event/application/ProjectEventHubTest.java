package dev.agenvas.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.agenvas.event.domain.ProjectEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** Verifies a stalled SSE client cannot retain more than the configured event frame cap. */
class ProjectEventHubTest {

    /** 单元测试不依赖真实心跳等待，取生产默认间隔。 */
    private static final SseProperties SSE = new SseProperties(Duration.ofSeconds(15));

    /** A committed hint reads durable rows immediately without waiting for the fallback tick. */
    @Test
    void committedHintWakesOnlyAnOpenProject() throws Exception {
        ProjectEventService events = mock(ProjectEventService.class);
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        CountDownLatch read = new CountDownLatch(1);
        when(events.replayWindow(ownerId, projectId))
                .thenReturn(new ProjectEventService.ReplayWindow(0, null));
        when(events.listAfter(ownerId, projectId, 0, 64)).thenAnswer(invocation -> {
            read.countDown();
            return List.of();
        });
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ProjectEventHub hub = new ProjectEventHub(events, Clock.systemUTC(), SSE, meters);
            try {
                hub.subscribe(ownerId, projectId, 0);
                hub.onCommitted(new ProjectEventCommitted(projectId));
                assertThat(read.await(500, TimeUnit.MILLISECONDS)).isTrue();
            } finally {
                hub.stop();
            }
        } finally {
            meters.close();
        }
    }

    @Test
    void reconnectDuringPageReadReplaysFromItsOwnCursorOnTheNextPoll() throws Exception {
        ProjectEventService events = mock(ProjectEventService.class);
        UUID owner = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        var history = java.util.stream.LongStream.rangeClosed(3, 6).mapToObj(seq -> event(project, seq, mapper))
                .toList();
        when(events.replayWindow(owner, project)).thenReturn(new ProjectEventService.ReplayWindow(6, 3L));
        var cursors = new CopyOnWriteArrayList<Long>();
        var thirdRead = new CountDownLatch(1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectEventHub hub = new ProjectEventHub(events, Clock.systemUTC(), SSE, meters);
        try {
            when(events.listAfter(eq(owner), eq(project), anyLong(), eq(64))).thenAnswer(call -> {
                long cursor = call.getArgument(2);
                cursors.add(cursor);
                if (cursors.size() == 1)
                    hub.subscribe(owner, project, 2); // The in-flight page starts after 5.
                if (cursors.size() < 3)
                    hub.onCommitted(new ProjectEventCommitted(project));
                else
                    thirdRead.countDown();
                return history.stream().filter(event -> event.seq() > cursor).toList();
            });
            hub.subscribe(owner, project, 5);
            hub.onCommitted(new ProjectEventCommitted(project));
            assertThat(thirdRead.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cursors).containsExactly(5L, 2L, 6L);
            assertThat(meters.get("agenvas.sse.connections.active").gauge().value()).isEqualTo(2);
        } finally {
            hub.stop();
            meters.close();
        }
    }

    /** Open and closed connection metrics follow the same once-only response cleanup. */
    @Test
    void connectionMetricReturnsToZeroWithoutHighCardinalityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ProjectEventHub hub = new ProjectEventHub(mock(ProjectEventService.class),
                    Clock.systemUTC(), SSE, meters);
            hub.subscribe(UUID.randomUUID(), UUID.randomUUID(), 0);
            assertThat(meters.get("agenvas.sse.connections.active").gauge().value())
                    .isEqualTo(1);
            hub.stop();
            assertThat(meters.get("agenvas.sse.connections.active").gauge().value())
                    .isZero();
            assertThat(meters.get("agenvas.sse.connections.closed").counter().count())
                    .isEqualTo(1);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
        } finally {
            meters.close();
        }
    }

    @Test
    void failedOrAlreadyEndedSseResponseIsNotCompletedAgain() {
        SseEmitter failed = mock(SseEmitter.class);
        ProjectEventHub.completeIfNeeded(failed, false);
        verifyNoInteractions(failed);

        SseEmitter raced = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already ended")).when(raced).complete();
        ProjectEventHub.completeIfNeeded(raced, true);
        verify(raced).complete();
    }

    @Test
    void pendingFramesHaveAHardLimit() {
        UUID projectId = UUID.randomUUID();
        ProjectEventHub.Subscriber subscriber = new ProjectEventHub.Subscriber(
                projectId, 0, new SseEmitter(), SSE.heartbeatInterval());
        ObjectMapper mapper = new ObjectMapper();
        for (int sequence = 1; sequence <= ProjectEventHub.MAX_PENDING_PER_SUBSCRIBER; sequence++) {
            assertThat(subscriber.offerEvent(event(projectId, sequence, mapper)))
                    .isEqualTo(ProjectEventHub.OfferResult.QUEUED);
        }
        assertThat(subscriber.offerEvent(event(
                projectId, ProjectEventHub.MAX_PENDING_PER_SUBSCRIBER + 1, mapper)))
                .isEqualTo(ProjectEventHub.OfferResult.OVERFLOW);
    }

    private ProjectEvent event(UUID projectId, long sequence, ObjectMapper mapper) {
        return new ProjectEvent(
                projectId,
                sequence,
                UUID.randomUUID(),
                "test.changed",
                1,
                UUID.randomUUID(),
                sequence,
                mapper.createObjectNode(),
                Instant.now());
    }
}

package dev.agenvas.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.agenvas.event.domain.ProjectEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** Verifies a stalled SSE client cannot retain more than the configured event frame cap. */
class ProjectEventHubTest {

    /** Open and closed connection metrics follow the same once-only response cleanup. */
    @Test
    void connectionMetricReturnsToZeroWithoutHighCardinalityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ProjectEventHub hub = new ProjectEventHub(mock(ProjectEventService.class),
                    Clock.systemUTC(), meters);
            hub.subscribe(UUID.randomUUID(), UUID.randomUUID(), 0);
            assertThat(meters.get("agenvas.sse.connections.active").gauge().value())
                    .isEqualTo(1);
            hub.stop();
            assertThat(meters.get("agenvas.sse.connections.active").gauge().value())
                    .isZero();
            assertThat(meters.get("agenvas.sse.connections.closed").counter().count())
                    .isEqualTo(1);
            assertThat(meters.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).isEmpty());
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
        ProjectEventHub.Subscriber subscriber =
                new ProjectEventHub.Subscriber(projectId, 0, new SseEmitter());
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

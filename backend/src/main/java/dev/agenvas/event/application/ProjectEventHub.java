package dev.agenvas.event.application;

import dev.agenvas.event.domain.ProjectEvent;
import dev.agenvas.shared.error.ApiProblemException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Polls each open project once and fan-outs committed events to bounded per-client queues. Slow
 * clients are disconnected so neither a servlet response nor memory can stall event ingestion.
 */
@Component
public class ProjectEventHub {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectEventHub.class);
    private static final int MAX_SUBSCRIBERS = 64;
    static final int MAX_PENDING_PER_SUBSCRIBER = 128;
    private static final int POLL_PAGE = 64;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final Duration PRUNE_INTERVAL = Duration.ofHours(1);
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();

    private final ProjectEventService events;
    private final Clock clock;
    private final Counter closedConnections;
    private final Counter sendFailures;
    private final Timer deliveryLag;
    private final ConcurrentHashMap<UUID, ProjectChannel> channels = new ConcurrentHashMap<>();
    private final AtomicInteger subscriberCount = new AtomicInteger();
    private final java.util.concurrent.ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(runnable ->
                    Thread.ofPlatform().daemon().name("project-event-poller").unstarted(runnable));
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_SUBSCRIBERS),
            runnable -> Thread.ofPlatform().daemon().name("project-event-sender").unstarted(runnable),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile Instant lastPrune;

    public ProjectEventHub(ProjectEventService events, Clock clock, MeterRegistry meters) {
        this.events = events;
        this.clock = clock;
        this.lastPrune = clock.instant();
        Gauge.builder("agenvas.sse.connections.active", subscriberCount, AtomicInteger::get)
                .description("Open project event SSE responses")
                .register(meters);
        closedConnections = Counter.builder("agenvas.sse.connections.closed")
                .description("Project event SSE responses released")
                .register(meters);
        sendFailures = Counter.builder("agenvas.sse.send.failures")
                .description("Project event SSE responses lost during send")
                .register(meters);
        deliveryLag = Timer.builder("agenvas.sse.event.delivery.lag")
                .description("Time from committed project event timestamp to SSE send completion")
                .register(meters);
    }

    /** Starts one shared scheduler after Spring has created all database dependencies. */
    @PostConstruct
    public void start() {
        poller.scheduleWithFixedDelay(this::tickSafely, 0, 1, TimeUnit.SECONDS);
    }

    /** Authorizes the waterline before starting the asynchronous SSE response. */
    public SseEmitter subscribe(UUID ownerId, UUID projectId, long afterSequence) {
        events.requireReplayableCursor(ownerId, projectId, afterSequence);
        if (subscriberCount.incrementAndGet() > MAX_SUBSCRIBERS) {
            subscriberCount.decrementAndGet();
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "EVENT_CONNECTION_LIMIT",
                    "事件连接已满",
                    "请稍后重连项目事件流。",
                    true);
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Subscriber subscriber = new Subscriber(projectId, afterSequence, emitter);
        emitter.onCompletion(() -> close(subscriber, false));
        emitter.onTimeout(() -> close(subscriber, false));
        emitter.onError(error -> close(subscriber, false));
        channels.compute(projectId, (ignored, current) -> {
            ProjectChannel channel = current == null ? new ProjectChannel(ownerId) : current;
            channel.subscribers.add(subscriber);
            return channel;
        });
        return emitter;
    }

    private void tickSafely() {
        try {
            for (var entry : channels.entrySet()) {
                try {
                    pollProject(entry.getValue());
                } catch (RuntimeException failure) {
                    LOGGER.warn("Project event polling failed for projectId={}", entry.getKey(), failure);
                }
            }
            pruneIfDue();
        } catch (RuntimeException failure) {
            LOGGER.error("Project event scheduler tick failed", failure);
        }
    }

    private void pollProject(ProjectChannel channel) {
        List<Subscriber> subscribers = channel.subscribers;
        if (subscribers.isEmpty()) {
            return;
        }
        Subscriber first = subscribers.getFirst();
        ProjectEventService.ReplayWindow window =
                events.replayWindow(channel.ownerId, first.projectId);
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.closed.get()
                    && !subscriber.cursorGap
                    && window.expired(subscriber.queuedThrough())) {
                if (subscriber.offerCursorExpired()) {
                    dispatch(subscriber);
                } else {
                    close(subscriber);
                }
            }
        }
        long minimumQueuedSequence = subscribers.stream()
                .filter(subscriber -> !subscriber.closed.get() && !subscriber.cursorGap)
                .mapToLong(Subscriber::queuedThrough)
                .min()
                .orElse(Long.MAX_VALUE);
        if (minimumQueuedSequence == Long.MAX_VALUE) {
            return;
        }
        List<ProjectEvent> batch = events.listAfter(
                channel.ownerId, first.projectId, minimumQueuedSequence, POLL_PAGE);
        for (ProjectEvent event : batch) {
            for (Subscriber subscriber : subscribers) {
                OfferResult result = subscriber.offerEvent(event);
                if (result == OfferResult.CURSOR_GAP) {
                    if (subscriber.offerCursorExpired()) {
                        dispatch(subscriber);
                    } else {
                        close(subscriber);
                    }
                } else if (result == OfferResult.OVERFLOW) {
                    close(subscriber);
                } else if (result == OfferResult.QUEUED) {
                    dispatch(subscriber);
                }
            }
        }
        Instant now = clock.instant();
        for (Subscriber subscriber : subscribers) {
            if (subscriber.offerHeartbeat(now)) {
                dispatch(subscriber);
            }
        }
    }

    private void pruneIfDue() {
        Instant now = clock.instant();
        if (now.isBefore(lastPrune.plus(PRUNE_INTERVAL))) {
            return;
        }
        int deleted = events.pruneExpired(1_000);
        if (deleted < 1_000) {
            lastPrune = now;
        }
    }

    private void dispatch(Subscriber subscriber) {
        if (subscriber.closed.get() || !subscriber.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            senders.execute(() -> drain(subscriber));
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            subscriber.draining.set(false);
            close(subscriber);
        }
    }

    private void drain(Subscriber subscriber) {
        try {
            Frame frame;
            while (!subscriber.closed.get() && (frame = subscriber.take()) != null) {
                switch (frame) {
                    case EventFrame event -> {
                        subscriber.emitter.send(SseEmitter.event()
                                .id(Long.toString(event.value().seq()))
                                .name(event.value().type())
                                .data(event.value(), MediaType.APPLICATION_JSON));
                        Duration elapsed = Duration.between(event.value().occurredAt(),
                                clock.instant());
                        deliveryLag.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
                    }
                    case HeartbeatFrame ignored ->
                        subscriber.emitter.send(SseEmitter.event().comment("heartbeat"));
                    case CursorExpiredFrame ignored -> {
                        subscriber.emitter.send(SseEmitter.event().name("cursor-expired")
                                .data("EVENT_CURSOR_EXPIRED"));
                        close(subscriber);
                    }
                }
            }
        } catch (IOException | RuntimeException deliveryFailure) {
            // Tomcat may already have completed the async response after a failed send.
            sendFailures.increment();
            close(subscriber, false);
        } finally {
            subscriber.draining.set(false);
            if (!subscriber.closed.get() && subscriber.hasPending()) {
                dispatch(subscriber);
            }
        }
    }

    private void close(Subscriber subscriber) {
        close(subscriber, true);
    }

    /** Removes an ended client without dispatching into an already failed AsyncContext. */
    private void close(Subscriber subscriber, boolean completeEmitter) {
        if (!subscriber.closed.compareAndSet(false, true)) {
            return;
        }
        channels.computeIfPresent(subscriber.projectId, (ignored, channel) -> {
            channel.subscribers.remove(subscriber);
            return channel.subscribers.isEmpty() ? null : channel;
        });
        subscriberCount.decrementAndGet();
        closedConnections.increment();
        completeIfNeeded(subscriber.emitter, completeEmitter);
    }

    /** Never dispatch completion after a servlet error, and tolerate a racing terminal callback. */
    static void completeIfNeeded(SseEmitter emitter, boolean shouldComplete) {
        if (!shouldComplete) return;
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyEnded) {
            LOGGER.debug("SSE response was already terminated", alreadyEnded);
        }
    }

    /** Releases scheduler threads and all open response objects on graceful shutdown. */
    @EventListener(ContextClosedEvent.class)
    @PreDestroy
    public void stop() {
        poller.shutdownNow();
        senders.shutdownNow();
        for (ProjectChannel channel : channels.values()) {
            for (Subscriber subscriber : channel.subscribers) {
                close(subscriber);
            }
        }
    }

    private static final class ProjectChannel {
        private final UUID ownerId;
        private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

        private ProjectChannel(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    static final class Subscriber {
        private final UUID projectId;
        private final SseEmitter emitter;
        private final ArrayDeque<Frame> pending = new ArrayDeque<>();
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private long queuedThrough;
        private Instant lastHeartbeat = Instant.EPOCH;
        private boolean cursorGap;

        Subscriber(UUID projectId, long afterSequence, SseEmitter emitter) {
            this.projectId = projectId;
            this.queuedThrough = afterSequence;
            this.emitter = emitter;
        }

        private synchronized long queuedThrough() {
            return queuedThrough;
        }

        synchronized OfferResult offerEvent(ProjectEvent event) {
            if (closed.get() || cursorGap || event.seq() <= queuedThrough) {
                return OfferResult.IGNORED;
            }
            if (event.seq() != queuedThrough + 1) {
                cursorGap = true;
                return OfferResult.CURSOR_GAP;
            }
            if (pending.size() >= MAX_PENDING_PER_SUBSCRIBER) {
                return OfferResult.OVERFLOW;
            }
            pending.addLast(new EventFrame(event));
            queuedThrough = event.seq();
            return OfferResult.QUEUED;
        }

        private synchronized boolean offerCursorExpired() {
            if (pending.size() < MAX_PENDING_PER_SUBSCRIBER) {
                cursorGap = true;
                pending.addLast(new CursorExpiredFrame());
                return true;
            }
            return false;
        }

        private synchronized boolean offerHeartbeat(Instant now) {
            if (closed.get() || cursorGap || now.isBefore(lastHeartbeat.plus(HEARTBEAT_INTERVAL))) {
                return false;
            }
            if (pending.size() >= MAX_PENDING_PER_SUBSCRIBER) {
                return false;
            }
            pending.addLast(new HeartbeatFrame());
            lastHeartbeat = now;
            return true;
        }

        private synchronized Frame take() {
            return pending.pollFirst();
        }

        private synchronized boolean hasPending() {
            return !pending.isEmpty();
        }
    }

    enum OfferResult {
        QUEUED,
        IGNORED,
        CURSOR_GAP,
        OVERFLOW
    }

    private sealed interface Frame permits EventFrame, HeartbeatFrame, CursorExpiredFrame {}

    private record EventFrame(ProjectEvent value) implements Frame {}

    private record HeartbeatFrame() implements Frame {}

    private record CursorExpiredFrame() implements Frame {}
}

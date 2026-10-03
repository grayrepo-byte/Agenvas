package dev.agenvas.event.application;

import dev.agenvas.shared.i18n.ApiMessage;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 按项目轮询已提交事件并分发到每个客户端的有界队列。数据库日志是可靠来源，慢客户端会断开以限制资源。
 */
@Component
public class ProjectEventHub {

    /** 记录轮询和连接发送错误，不影响数据库事件日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectEventHub.class);
    /** 全进程允许打开的 SSE 连接上限。 */
    private static final int MAX_SUBSCRIBERS = 64;
    /** 单连接待发送帧上限；超限客户端被断开，随后从数据库游标补发。 */
    static final int MAX_PENDING_PER_SUBSCRIBER = 128;
    /** 单次项目轮询最多读取的持久化事件数。 */
    private static final int POLL_PAGE = 64;
    /** 项目事件过期清理调度间隔。 */
    private static final Duration PRUNE_INTERVAL = Duration.ofHours(1);
    /** 单个 SSE 异步响应的最大存活时长。 */
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(5).toMillis();

    /** 查询项目游标水位并读取数据库补发页。 */
    private final ProjectEventService events;
    /** 生成心跳及发送延迟测量的时间。 */
    private final Clock clock;
    /** 无事件连接的心跳间隔；同时是失效连接的回收延迟上限，来自服务端配置。 */
    private final Duration heartbeatInterval;
    /** 统计关闭连接、发送失败及事件投递延迟。 */
    private final Counter closedConnections;
    /** 客户端发送异常或已结束的响应数。 */
    private final Counter sendFailures;
    /** 事件提交到 SSE 完成发送的耗时分布。 */
    private final Timer deliveryLag;
    /** 当前有订阅者的项目通道；不保存事件正文作为可靠队列。 */
    private final ConcurrentHashMap<UUID, ProjectChannel> channels = new ConcurrentHashMap<>();
    /** 当前已接纳 SSE 连接数，用于上限检查及指标采集。 */
    private final AtomicInteger subscriberCount = new AtomicInteger();
    /** 单线程调度项目补发、post-commit 提示和过期清理。 */
    private final java.util.concurrent.ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(runnable ->
                    Thread.ofPlatform().daemon().name("project-event-poller").unstarted(runnable));
    /** 有界线程池隔离慢速 HTTP 写入，发送任务饱和时关闭对应客户端。 */
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_SUBSCRIBERS),
            runnable -> Thread.ofPlatform().daemon().name("project-event-sender").unstarted(runnable),
            new ThreadPoolExecutor.AbortPolicy());
    /** 最近一次确认完成过期清理的时间；批次满时会继续清理剩余记录。 */
    private volatile Instant lastPrune;

    /** 注册连接、发送错误和投递延迟指标，并初始化清理调度基准时间。 */
    public ProjectEventHub(
            ProjectEventService events, Clock clock, SseProperties sse, MeterRegistry meters) {
        this.events = events;
        this.clock = clock;
        this.heartbeatInterval = sse.heartbeatInterval();
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

    /** Spring 完成依赖装配后启动共享调度器。 */
    @PostConstruct
    public void start() {
        poller.scheduleWithFixedDelay(this::tickSafely, 0, 1, TimeUnit.SECONDS);
    }

    /** 合并提交后唤醒提示；提示丢失时每秒数据库轮询仍会发现事件。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(ProjectEventCommitted committed) {
        ProjectChannel channel = channels.get(committed.projectId());
        if (channel == null || channel.subscribers.isEmpty()) return;
        channel.wakeRequested.set(true);
        scheduleWake(channel);
    }

    /** 每个项目最多排队一个唤醒任务，并限制单次连续轮询次数让出调度线程。 */
    private void scheduleWake(ProjectChannel channel) {
        if (!channel.wakeQueued.compareAndSet(false, true)) return;
        try {
            poller.execute(() -> {
                try {
                    // 单次最多连续读取四轮，让其他项目和周期兜底轮询有机会运行。
                    for (int attempt = 0; attempt < 4; attempt++) {
                        if (!channel.wakeRequested.getAndSet(false)) break;
                        try {
                            pollProject(channel);
                        } catch (RuntimeException failure) {
                            LOGGER.warn("Project event wake-up poll failed", failure);
                            break;
                        }
                    }
                } finally {
                    channel.wakeQueued.set(false);
                    if (channel.wakeRequested.get()) scheduleWake(channel);
                }
            });
        } catch (RejectedExecutionException stopping) {
            channel.wakeQueued.set(false);
            // 停机或调度饱和只会丢失唤醒提示，不会丢失数据库中的已提交事件。
        }
    }

    /** 验证补发游标未超前或过期后才注册异步 SSE 响应，并限制全局连接数。 */
    public SseEmitter subscribe(UUID ownerId, UUID projectId, long afterSequence) {
        events.requireReplayableCursor(ownerId, projectId, afterSequence);
        if (subscriberCount.incrementAndGet() > MAX_SUBSCRIBERS) {
            subscriberCount.decrementAndGet();
            throw new ApiProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "EVENT_CONNECTION_LIMIT",
                    ApiMessage.of("api.project-event-hub.event-connection-is-full"),
                    ApiMessage.of("api.project-event-hub.please-reconnect-to-the-project-event-stream-later"),
                    true);
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Subscriber subscriber =
                new Subscriber(projectId, afterSequence, emitter, heartbeatInterval);
        emitter.onCompletion(() -> close(subscriber, false));
        // 到达连接存活上限时正常完成 DeferredResult，阻止 MVC 把超时交给 JSON 错误处理器。
        // 发送失败和容器错误仍只清理订阅，不能再次完成已不可写的响应。
        emitter.onTimeout(() -> close(subscriber));
        emitter.onError(error -> close(subscriber, false));
        channels.compute(projectId, (ignored, current) -> {
            ProjectChannel channel = current == null ? new ProjectChannel(ownerId) : current;
            channel.subscribers.add(subscriber);
            return channel;
        });
        return emitter;
    }

    /** 周期轮询所有开放项目并执行到期清理；捕获单项目失败以继续处理其余项目。 */
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

    /** 对照持久化水位处理过期游标、批量补发事件和心跳。 */
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

    /** 到期后最多清理一批记录；批次达到上限时下轮立即继续。 */
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

    /** 同一订阅者只排一个 drain 任务；发送池饱和时关闭以维持内存上限。 */
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

    /** 按队列顺序写 SSE 事件 ID、类型或心跳；发送异常关闭连接以触发客户端重连补发。 */
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
            // Servlet 异步响应发送失败后可能已经终止，不能再调用 emitter.complete()。
            sendFailures.increment();
            close(subscriber, false);
        } finally {
            subscriber.draining.set(false);
            if (!subscriber.closed.get() && subscriber.hasPending()) {
                dispatch(subscriber);
            }
        }
    }

    /** 关闭订阅并完成响应；发送异常路径使用另一个重载避免重复触碰 AsyncContext。 */
    private void close(Subscriber subscriber) {
        close(subscriber, true);
    }

    /** 原子移除已结束订阅者并按需要完成响应，避免重复减少连接计数。 */
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

    /** Servlet 已报错时不再派发完成回调，并容忍完成与终态回调竞争。 */
    static void completeIfNeeded(SseEmitter emitter, boolean shouldComplete) {
        if (!shouldComplete) return;
        try {
            emitter.complete();
        } catch (IllegalStateException alreadyEnded) {
            LOGGER.debug("SSE response was already terminated", alreadyEnded);
        }
    }

    /** 优雅停机时停止调度和发送线程，并关闭所有开放中的 SSE 响应。 */
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

    /** 一个项目的订阅者和合并唤醒状态；事件正文始终从数据库按游标读取。 */
    private static final class ProjectChannel {
        /** 此通道的所有者，轮询数据库时用于再次鉴权。 */
        private final UUID ownerId;
        /** 线程安全的当前项目订阅者集合。 */
        private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        /** 是否有提交后提示等待被轮询消费。 */
        private final AtomicBoolean wakeRequested = new AtomicBoolean();
        /** 是否已经排队唤醒任务，避免同项目重复排队。 */
        private final AtomicBoolean wakeQueued = new AtomicBoolean();

        /** 新通道仅绑定创建时已鉴权的项目所有者。 */
        private ProjectChannel(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    /** 一个 SSE 客户端的有界帧队列和补发游标状态。 */
    static final class Subscriber {
        /** 当前订阅的项目。 */
        private final UUID projectId;
        /** Spring MVC 管理的异步 SSE 响应。 */
        private final SseEmitter emitter;
        /** 该连接的心跳节流间隔；跟随服务端配置，测试可缩短以加快回收验证。 */
        private final Duration heartbeatInterval;
        /** 等待写出的事件、心跳或游标过期控制帧。 */
        private final ArrayDeque<Frame> pending = new ArrayDeque<>();
        /** 防止并发线程为同一订阅者启动多个发送循环。 */
        private final AtomicBoolean draining = new AtomicBoolean();
        /** 连接关闭标记，同时保证资源计数只扣减一次。 */
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 队列中已连续覆盖到的最高项目序号。 */
        private long queuedThrough;
        /** 最近一次入队心跳时间。 */
        private Instant lastHeartbeat = Instant.EPOCH;
        /** 检测到序号缺口或历史过期后停止普通事件入队。 */
        private boolean cursorGap;

        /** 初始补发游标表示客户端已处理到 afterSequence。 */
        Subscriber(
                UUID projectId,
                long afterSequence,
                SseEmitter emitter,
                Duration heartbeatInterval) {
            this.projectId = projectId;
            this.queuedThrough = afterSequence;
            this.emitter = emitter;
            this.heartbeatInterval = heartbeatInterval;
        }

        /** 游标只推进到已连续入队的最后序号，不能越过尚未排入的事件。 */
        private synchronized long queuedThrough() {
            return queuedThrough;
        }

        /** 去重旧事件，检测序号缺口，并在队列有界时按序追加事件帧。 */
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

        /** 有容量时追加一次游标过期控制帧；队列已满则由调用方直接关闭。 */
        private synchronized boolean offerCursorExpired() {
            if (pending.size() < MAX_PENDING_PER_SUBSCRIBER) {
                cursorGap = true;
                pending.addLast(new CursorExpiredFrame());
                return true;
            }
            return false;
        }

        /** 仅无游标缺口且超过间隔时入队心跳，不挤占已满队列。 */
        private synchronized boolean offerHeartbeat(Instant now) {
            if (closed.get() || cursorGap || now.isBefore(lastHeartbeat.plus(heartbeatInterval))) {
                return false;
            }
            if (pending.size() >= MAX_PENDING_PER_SUBSCRIBER) {
                return false;
            }
            pending.addLast(new HeartbeatFrame());
            lastHeartbeat = now;
            return true;
        }

        /** 取出最早等待帧供唯一发送循环处理。 */
        private synchronized Frame take() {
            return pending.pollFirst();
        }

        /** 检查是否有 drain 循环尚未消费的帧。 */
        private synchronized boolean hasPending() {
            return !pending.isEmpty();
        }
    }

    /** 单条事件加入订阅队列的结果。 */
    enum OfferResult {
        /** 已入队，可调度发送。 */
        QUEUED,
        /** 事件已旧、订阅已关闭或已有游标缺口。 */
        IGNORED,
        /** 新事件序号不连续，必须重新获取快照。 */
        CURSOR_GAP,
        /** 待发送队列已满，调用方应关闭慢客户端。 */
        OVERFLOW
    }

    /** 发送线程消费的封闭帧类型。 */
    private sealed interface Frame permits EventFrame, HeartbeatFrame, CursorExpiredFrame {}

    /**
     * 待发送的不可变项目事件。
     *
     * @param value 数据库补发得到的项目序号事件
     */
    private record EventFrame(ProjectEvent value) implements Frame {}

    /** 仅用于保持代理连接的 SSE 注释帧。 */
    private record HeartbeatFrame() implements Frame {}

    /** 通知客户端游标过期并重新获取快照的控制帧。 */
    private record CursorExpiredFrame() implements Frame {}
}

package dev.agenvas.event.application;

import dev.agenvas.event.domain.ProjectEvent;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Records business changes and their replay event under one project counter lock and transaction. */
@Service
public class ProjectEventService {

    private static final int MAX_REPLAY_PAGE = 1_000;
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final Pattern EVENT_TYPE =
            Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+");

    private final ProjectEventRepository events;
    private final Clock clock;
    private final ApplicationEventPublisher publisher;

    public ProjectEventService(ProjectEventRepository events, Clock clock,
            ApplicationEventPublisher publisher) {
        this.events = events;
        this.clock = clock;
        this.publisher = publisher;
    }

    /**
     * Locks the project sequence before invoking the business mutation and writes its event before
     * commit. An unchanged replay advances neither the counter nor the event log.
     */
    @Transactional
    public <T> RecordedChange<T> recordChange(
            UUID ownerId, UUID projectId, Supplier<Change<T>> mutation) {
        long currentSequence = events.lockCurrentSequence(ownerId, projectId)
                .orElseThrow(this::notFound);
        Change<T> change = mutation.get();
        if (change.event() == null) {
            return new RecordedChange<>(change.value(), null);
        }
        EventDraft draft = validate(change.event());
        long nextSequence = Math.addExact(currentSequence, 1);
        if (!events.advanceSequence(ownerId, projectId, currentSequence, nextSequence)) {
            throw new IllegalStateException("Locked project event sequence changed unexpectedly");
        }
        ProjectEvent event = new ProjectEvent(
                projectId,
                nextSequence,
                UUID.randomUUID(),
                draft.type(),
                draft.schemaVersion(),
                draft.aggregateId(),
                draft.aggregateVersion(),
                draft.payload().deepCopy(),
                clock.instant());
        events.insert(event);
        publisher.publishEvent(new ProjectEventCommitted(projectId));
        return new RecordedChange<>(change.value(), event);
    }

    /** Appends one internal event without a separate business mutation, primarily for coordination. */
    @Transactional
    public ProjectEvent append(UUID ownerId, UUID projectId, EventDraft draft) {
        return recordChange(ownerId, projectId, () -> Change.changed(null, draft)).event();
    }

    /** Returns an owner-scoped ordered replay page for the later SSE delivery layer. */
    @Transactional(readOnly = true)
    public List<ProjectEvent> listAfter(
            UUID ownerId, UUID projectId, long afterSequence, int requestedLimit) {
        if (afterSequence < 0 || requestedLimit < 1 || requestedLimit > MAX_REPLAY_PAGE) {
            throw validation("事件游标必须非负，limit 必须在 1 到 1000 之间。");
        }
        return events.listAfter(ownerId, projectId, afterSequence, requestedLimit);
    }

    /** Rejects a future or pruned cursor before HTTP upgrades to a streaming response. */
    @Transactional(readOnly = true)
    public void requireReplayableCursor(UUID ownerId, UUID projectId, long afterSequence) {
        if (afterSequence < 0) {
            throw validation("事件游标必须非负。");
        }
        ReplayWindow window = replayWindow(ownerId, projectId);
        if (afterSequence > window.latestSequence()) {
            throw validation("事件游标超过项目当前水位。");
        }
        if (window.expired(afterSequence)) {
            throw cursorExpired();
        }
    }

    /** Reads the replay window once per open project for active-stream pruning checks. */
    @Transactional(readOnly = true)
    public ReplayWindow replayWindow(UUID ownerId, UUID projectId) {
        ProjectEventRepository.CursorBounds bounds = events.cursorBounds(ownerId, projectId);
        if (bounds == null) {
            throw notFound();
        }
        return new ReplayWindow(bounds.latestSequence(), bounds.oldestRetainedSequence());
    }

    /** Prunes a bounded batch of events older than the configured 30 day retention window. */
    @Transactional
    public int pruneExpired(int limit) {
        if (limit < 1 || limit > MAX_REPLAY_PAGE) {
            throw validation("清理批量必须在 1 到 1000 之间。");
        }
        return events.pruneOlderThan(clock.instant().minus(RETENTION), limit);
    }

    private EventDraft validate(EventDraft draft) {
        if (draft == null
                || draft.type() == null
                || !EVENT_TYPE.matcher(draft.type()).matches()
                || draft.type().length() > 120
                || draft.schemaVersion() < 1
                || draft.aggregateId() == null
                || draft.aggregateVersion() < 0
                || draft.payload() == null
                || !draft.payload().isObject()) {
            throw validation("项目事件类型、版本、聚合标识或对象 payload 无效。");
        }
        return draft;
    }

    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "项目不存在",
                "项目不存在或当前用户无权访问。",
                false);
    }

    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "项目事件无效",
                detail,
                false);
    }

    private ApiProblemException cursorExpired() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "EVENT_CURSOR_EXPIRED",
                "事件游标已过期",
                "请重新获取项目快照并从新水位订阅。",
                false);
    }

    /** Safe client-visible event fields chosen by the application service performing the mutation. */
    public record EventDraft(
            String type,
            int schemaVersion,
            UUID aggregateId,
            long aggregateVersion,
            JsonNode payload) {}

    /** A callback outcome that either emits one event or represents an exact unchanged replay. */
    public record Change<T>(T value, EventDraft event) {

        /** Creates an eventful business outcome. */
        public static <T> Change<T> changed(T value, EventDraft event) {
            return new Change<>(value, event);
        }

        /** Creates an unchanged, idempotent outcome without consuming a project sequence. */
        public static <T> Change<T> unchanged(T value) {
            return new Change<>(value, null);
        }
    }

    /** Returned value and the event committed with it, if the operation changed state. */
    public record RecordedChange<T>(T value, ProjectEvent event) {}

    /** Latest committed sequence and the first sequence still available for replay. */
    public record ReplayWindow(long latestSequence, Long oldestRetainedSequence) {

        /** Tells whether a cursor points before any retained replay interval. */
        public boolean expired(long afterSequence) {
            return (oldestRetainedSequence != null
                            && afterSequence < oldestRetainedSequence - 1)
                    || (oldestRetainedSequence == null && afterSequence < latestSequence);
        }
    }
}

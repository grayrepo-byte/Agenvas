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

/** 在同一项目计数行锁和事务内提交业务变更及可补发事件；内存通知仅唤醒订阅端。 */
@Service
public class ProjectEventService {

    /** 单次补发与清理允许的最大记录数。 */
    private static final int MAX_REPLAY_PAGE = 1_000;
    /** 项目事件的持久化保留期；过期游标需重新获取快照。 */
    private static final Duration RETENTION = Duration.ofDays(30);
    /** 事件类型限定为小写点分段，避免任意文本进入协议字段。 */
    private static final Pattern EVENT_TYPE =
            Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");

    /** 以项目计数行锁生成序号并保存不可变事件。 */
    private final ProjectEventRepository events;
    /** 生成事件时间并计算清理截止时间。 */
    private final Clock clock;
    /** 提交后唤醒内存订阅者；真正补发仍以数据库事件为准。 */
    private final ApplicationEventPublisher publisher;

    /** 注入持久事件仓储、事件时间源和仅用于提交后唤醒的本地发布器。 */
    public ProjectEventService(ProjectEventRepository events, Clock clock,
            ApplicationEventPublisher publisher) {
        this.events = events;
        this.clock = clock;
        this.publisher = publisher;
    }

    /**
     * 先锁项目事件计数行，再执行调用方业务变更；状态、递增序号和事件行在同一事务提交。
     * 调用方返回 unchanged 时不消耗序号，也不生成事件。内存发布仅用于唤醒，补发读取持久化记录。
     *
     * @param ownerId 经认证的项目所有者，用于锁定正确项目
     * @param projectId 待变更且需分配本地事件序号的项目
     * @param mutation 持有项目锁期间执行的业务变更，返回事件草稿或幂等不变结果
     * @return 业务返回值及本事务新增的事件；未变化时事件为空
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

    /** 在当前项目序号下追加一个内部事件，可用于已有状态变化后的协调通知。 */
    @Transactional
    public ProjectEvent append(UUID ownerId, UUID projectId, EventDraft draft) {
        return recordChange(ownerId, projectId, () -> Change.changed(null, draft)).event();
    }

    /** 按项目本地序号从独占游标之后读取有界事件页，供 SSE 断线补发。 */
    @Transactional(readOnly = true)
    public List<ProjectEvent> listAfter(
            UUID ownerId, UUID projectId, long afterSequence, int requestedLimit) {
        if (afterSequence < 0 || requestedLimit < 1 || requestedLimit > MAX_REPLAY_PAGE) {
            throw validation("事件游标必须非负，limit 必须在 1 到 1000 之间。");
        }
        return events.listAfter(ownerId, projectId, afterSequence, requestedLimit);
    }

    /** HTTP 升级为事件流前拒绝未来或已被清理的游标；过期时要求重新获取快照。 */
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

    /** 读取当前序号水位与保留日志下界，供活动 SSE 流判断游标是否仍可补发。 */
    @Transactional(readOnly = true)
    public ReplayWindow replayWindow(UUID ownerId, UUID projectId) {
        ProjectEventRepository.CursorBounds bounds = events.cursorBounds(ownerId, projectId);
        if (bounds == null) {
            throw notFound();
        }
        return new ReplayWindow(bounds.latestSequence(), bounds.oldestRetainedSequence());
    }

    /** 每次至多清理一批 30 天前事件；项目计数序号不随清理回退。 */
    @Transactional
    public int pruneExpired(int limit) {
        if (limit < 1 || limit > MAX_REPLAY_PAGE) {
            throw validation("清理批量必须在 1 到 1000 之间。");
        }
        return events.pruneOlderThan(clock.instant().minus(RETENTION), limit);
    }

    /** 校验事件类型、Schema、聚合版本及对象负载，再允许写入可供客户端重放的日志。 */
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

    /** 构造项目不存在或当前用户无权访问时的 404 响应。 */
    private ApiProblemException notFound() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "项目不存在",
                "项目不存在或当前用户无权访问。",
                false);
    }

    /** 构造事件类型、游标或批量参数不符合协议时的 400 响应。 */
    private ApiProblemException validation(String detail) {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "项目事件无效",
                detail,
                false);
    }

    /** 构造事件日志已无法连续补发时的 409 响应，要求客户端重取快照。 */
    private ApiProblemException cursorExpired() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "EVENT_CURSOR_EXPIRED",
                "事件游标已过期",
                "请重新获取项目快照并从新水位订阅。",
                false);
    }

    /** 由业务服务选择的客户端可见字段；写入前复制负载，调用方须排除密钥和私有模型内容。
     * @param type 小写点分段事件类型
     * @param schemaVersion 客户端解释负载所用的结构版本
     * @param aggregateId 发生变化的业务聚合 ID
     * @param aggregateVersion 聚合自身递增版本
     * @param payload 不含密钥和私有推理内容的对象负载
     */
    public record EventDraft(
            String type,
            int schemaVersion,
            UUID aggregateId,
            long aggregateVersion,
            JsonNode payload) {}

    /** 业务变更回调结果；有事件表示变化，空事件表示精确幂等重放。
     * @param value 调用方业务返回值
     * @param event 需要和本次状态变更一同持久化的事件；未变化时为空
     */
    public record Change<T>(T value, EventDraft event) {

        /** 返回需消费一个项目事件序号的业务变化。 */
        public static <T> Change<T> changed(T value, EventDraft event) {
            return new Change<>(value, event);
        }

        /** 返回不消耗项目序号的幂等不变结果。 */
        public static <T> Change<T> unchanged(T value) {
            return new Change<>(value, null);
        }
    }

    /** 同事务提交后的业务值及事件；不变操作的事件为空。
     * @param value 已提交业务操作的返回值
     * @param event 本事务新增的持久化事件；幂等不变时为空
     */
    public record RecordedChange<T>(T value, ProjectEvent event) {}

    /** 项目最新水位与当前仍可补发的最早事件序号。
     * @param latestSequence 项目已提交的最高事件序号
     * @param oldestRetainedSequence 最早保留事件序号；日志为空时为空
     */
    public record ReplayWindow(long latestSequence, Long oldestRetainedSequence) {

        /** 判断游标是否早于保留区间；过期时客户端必须重新获取快照。
         * @param afterSequence 客户端最后处理的事件序号
         * @return 游标与剩余日志不连续时为 true
         */
        public boolean expired(long afterSequence) {
            return (oldestRetainedSequence != null
                            && afterSequence < oldestRetainedSequence - 1)
                    || (oldestRetainedSequence == null && afterSequence < latestSequence);
        }
    }
}

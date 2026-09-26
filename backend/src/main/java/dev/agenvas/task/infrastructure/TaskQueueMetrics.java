package dev.agenvas.task.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只读发布持久任务状态数量和最老到期任务年龄，指标标签仅使用固定状态值。 */
@Component
public class TaskQueueMetrics {

    /** 记录数据库指标采集失败和恢复，不记录任务内容。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskQueueMetrics.class);
    /** 从数据库加载同一快照的任务数量和等待年龄。 */
    private final Supplier<Snapshot> snapshotLoader;
    /** 最近一次快照中的 READY 数量；-1 表示尚未采集或数据库不可用。 */
    private final AtomicLong ready = new AtomicLong(-1);
    /** 最近一次快照中的 UNKNOWN 数量；-1 表示该值当前不可用。 */
    private final AtomicLong unknown = new AtomicLong(-1);
    /** 最近一次快照中的 BLOCKED 数量；-1 表示该值当前不可用。 */
    private final AtomicLong blocked = new AtomicLong(-1);
    /** 最老到期 READY 任务距最近状态更新的秒数；不可用时为 -1。 */
    private final AtomicReference<Double> oldestReadyAgeSeconds = new AtomicReference<>(-1.0);
    /** 控制数据库故障日志仅在状态转换时输出一次。 */
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean();

    /** 注册持久层快照查询，并将可见数值映射到无项目标签的 Micrometer 指标。 */
    @Autowired
    public TaskQueueMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this(() -> jdbc.sql("""
                        select count(*) filter (where status = 'READY') as ready,
                               count(*) filter (where status = 'UNKNOWN') as unknown,
                               count(*) filter (where status = 'BLOCKED') as blocked,
                               coalesce(max(greatest(0, extract(epoch from
                                   (now() - updated_at)))) filter (where status = 'READY'
                                   and next_action_at <= now()), 0)::double precision
                                   as oldest_ready_age_seconds
                        from task
                        where status in ('READY', 'UNKNOWN', 'BLOCKED')
                        """)
                .query((row, ignored) -> new Snapshot(row.getLong("ready"),
                        row.getLong("unknown"), row.getLong("blocked"),
                        row.getDouble("oldest_ready_age_seconds"))).single(), meters);
    }

    /** 注入快照加载器，便于隔离数据库状态源并验证失败与恢复行为。 */
    TaskQueueMetrics(Supplier<Snapshot> snapshotLoader, MeterRegistry meters) {
        this.snapshotLoader = snapshotLoader;
        register(meters, "READY", ready);
        register(meters, "UNKNOWN", unknown);
        register(meters, "BLOCKED", blocked);
        Gauge.builder("agenvas.tasks.ready.oldest.age.seconds", oldestReadyAgeSeconds,
                        AtomicReference::get)
                .description("Oldest due READY task age since last state update; -1 when unavailable")
                .register(meters);
    }

    /** 注册固定状态标签的任务数量 Gauge，负值表示快照尚未可用。 */
    private void register(MeterRegistry meters, String status, AtomicLong value) {
        Gauge.builder("agenvas.tasks.current", value, AtomicLong::get)
                .description("Durable tasks in one allowlisted state; -1 when unavailable")
                .tag("status", status)
                .register(meters);
    }

    /** 用一次只读 SQL 刷新全部状态和队列年龄，不认领或改变任何任务。 */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            Snapshot snapshot = snapshotLoader.get();
            ready.set(snapshot.ready());
            unknown.set(snapshot.unknown());
            blocked.set(snapshot.blocked());
            oldestReadyAgeSeconds.set(snapshot.oldestReadyAgeSeconds());
            if (databaseUnavailable.getAndSet(false)) {
                LOGGER.info("Task queue metrics database snapshot recovered");
            }
        } catch (DataAccessException unavailable) {
            ready.set(-1);
            unknown.set(-1);
            blocked.set(-1);
            oldestReadyAgeSeconds.set(-1.0);
            if (databaseUnavailable.compareAndSet(false, true)) {
                LOGGER.warn("Task queue metrics database snapshot unavailable",
                        unavailable);
            }
        }
    }

    /** 来自同一 SQL 语句的已提交数据库快照。
     * @param ready 到期或未到期 READY 任务总数
     * @param unknown 需人工重试的结果未知任务数
     * @param blocked 等待恢复条件满足的任务数
     * @param oldestReadyAgeSeconds 最老到期 READY 任务等待秒数
     */
    record Snapshot(long ready, long unknown, long blocked, double oldestReadyAgeSeconds) {}
}

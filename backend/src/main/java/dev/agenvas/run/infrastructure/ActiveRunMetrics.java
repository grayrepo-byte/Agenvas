package dev.agenvas.run.infrastructure;

import static dev.agenvas.db.Tables.AGENT_RUN;

import dev.agenvas.run.domain.AgentRun;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 发布持久化非终态 Run 数量，不附加项目或用户标签。 */
@Component
public class ActiveRunMetrics {

    /** 记录数据库快照故障与恢复，不重复输出相同状态日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ActiveRunMetrics.class);
    /** 从数据库读取非终态 Run 数量的只读查询。 */
    private final LongSupplier countLoader;
    /** 最近采样的非终态 Run 数量；-1 表示暂不可用。 */
    private final AtomicLong active = new AtomicLong(-1);
    /** 控制数据库不可用日志仅在状态切换时输出。 */
    private final AtomicBoolean databaseUnavailable = new AtomicBoolean();

    /** 注册非终态 Run 计数查询和 Gauge。 */
    @Autowired
    public ActiveRunMetrics(DSLContext dsl, MeterRegistry meters) {
        this(() -> dsl.fetchCount(AGENT_RUN, AGENT_RUN.STATUS.notIn(
                AgentRun.Status.CANCELED.name(),
                AgentRun.Status.FAILED.name(),
                AgentRun.Status.SUCCEEDED.name())), meters);
    }

    /** 注入数量加载器以隔离数据库访问并验证不可用和恢复状态。 */
    ActiveRunMetrics(LongSupplier countLoader, MeterRegistry meters) {
        this.countLoader = countLoader;
        Gauge.builder("agenvas.runs.active", active, AtomicLong::get)
                .description("Non-terminal Agent Runs; -1 when database snapshot unavailable")
                .register(meters);
    }

    /** 通过单次只读查询刷新 Gauge，不调度或推进任何 Run。 */
    @Scheduled(fixedDelay = 30_000)
    public void refresh() {
        try {
            active.set(countLoader.getAsLong());
            if (databaseUnavailable.getAndSet(false)) {
                LOGGER.info("Active Run metrics database snapshot recovered");
            }
        } catch (DataAccessException unavailable) {
            active.set(-1);
            if (databaseUnavailable.compareAndSet(false, true)) {
                LOGGER.warn("Active Run metrics database snapshot unavailable", unavailable);
            }
        }
    }
}

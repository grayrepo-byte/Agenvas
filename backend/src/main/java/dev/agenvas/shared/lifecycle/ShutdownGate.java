package dev.agenvas.shared.lifecycle;

import dev.agenvas.shared.error.ApiProblemException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 关闭时拒绝新 Run 和新任务认领，让已开始的 fencing 工作正常完成或租约到期。 */
@Component
public class ShutdownGate {

    /** 记录关闭门闩状态转换，不重复输出关闭消息。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownGate.class);
    /** 读锁允许短认领或 Run 准入并发，写锁使关闭信号与其串行化。 */
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock(true);
    /** 上下文关闭事件生效后置为 true，调度器可安全读取。 */
    private volatile boolean closing;

    /** Spring 销毁 Worker 与数据库 Bean 前发布事件，阻止新工作进入。 */
    @EventListener
    public void onContextClosed(ContextClosedEvent ignored) {
        admission.writeLock().lock();
        try {
            if (!closing) {
                closing = true;
                LOGGER.info("Shutdown gate closed: new Runs and Task claims disabled");
            }
        } finally {
            admission.writeLock().unlock();
        }
    }

    /** 调度器关闭期间仍可能触发，但可据此跳过新认领。 */
    public boolean isClosing() {
        return closing;
    }

    /** 对会启动新编排的用户操作返回稳定且可重试的 503。 */
    public void requireAcceptingRuns() {
        if (closing) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "APPLICATION_STOPPING", "服务正在关闭",
                    "当前实例不再接收新的 Agent Run，请稍后重试。", true);
        }
    }

    /** 关闭前已开始的短认领允许完成，关闭后新调用直接返回调用方指定的空值。
     * @param claim 执行短时仓储认领的回调
     * @param empty 关闭状态下返回且不执行回调的值
     * @return 认领回调结果或关闭时的空值
     */
    public <T> T claimOrEmpty(Supplier<T> claim, T empty) {
        admission.readLock().lock();
        try {
            return closing ? empty : claim.get();
        } finally {
            admission.readLock().unlock();
        }
    }

    /** 使最后一次短 Run 创建操作与关闭信号串行化。
     * @param create 创建并持久化 Run 的回调
     * @return Run 创建回调返回的值
     */
    public <T> T admitRun(Supplier<T> create) {
        admission.readLock().lock();
        try {
            requireAcceptingRuns();
            return create.get();
        } finally {
            admission.readLock().unlock();
        }
    }
}

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

/** Stops new Run admission and worker claims while existing fenced work finishes or expires. */
@Component
public class ShutdownGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShutdownGate.class);
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock(true);
    private volatile boolean closing;

    /** Spring publishes this before destroying the worker and database beans. */
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

    /** A scheduler may still tick during teardown, but must not claim fresh work. */
    public boolean isClosing() {
        return closing;
    }

    /** User commands that would start new orchestration receive a stable retryable 503. */
    public void requireAcceptingRuns() {
        if (closing) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "APPLICATION_STOPPING", "服务正在关闭",
                    "当前实例不再接收新的 Agent Run，请稍后重试。", true);
        }
    }

    /** Short repository claims begun before close finish; no new claim begins afterward. */
    public <T> T claimOrEmpty(Supplier<T> claim, T empty) {
        admission.readLock().lock();
        try {
            return closing ? empty : claim.get();
        } finally {
            admission.readLock().unlock();
        }
    }

    /** Orders the last short Run-admission write against the close signal. */
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

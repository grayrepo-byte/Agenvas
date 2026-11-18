package dev.agenvas.shared.lifecycle;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/** Owns a local worker thread and admits at most one outstanding pass. */
public final class BoundedWorkerExecutor implements AutoCloseable {
    private static final int SHUTDOWN_SECONDS = 10;

    private final ShutdownGate shutdown;
    private final ExecutorService executor;
    private final Logger logger;
    private final String failureMessage;
    private final AtomicBoolean busy = new AtomicBoolean();

    public BoundedWorkerExecutor(ShutdownGate shutdown, String threadName, Logger logger, String failureMessage) {
        this(shutdown, Executors.newSingleThreadExecutor(Thread.ofPlatform().name(threadName).factory()),
                logger, failureMessage);
    }

    BoundedWorkerExecutor(ShutdownGate shutdown, ExecutorService executor, Logger logger, String failureMessage) {
        this.shutdown = shutdown;
        this.executor = executor;
        this.logger = logger;
        this.failureMessage = failureMessage;
    }

    /** Submits a pass without blocking the scheduler or queueing a follow-up tick. */
    public void execute(Runnable pass) {
        if (shutdown.isClosing() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    // Shutdown may start after admission but before the worker thread runs.
                    if (!shutdown.isClosing()) {
                        pass.run();
                    }
                } catch (RuntimeException failure) {
                    logger.warn(failureMessage, failure.getClass().getSimpleName());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException stopped) {
            busy.set(false);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

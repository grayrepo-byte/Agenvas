package dev.agenvas.library.application;

import dev.agenvas.shared.lifecycle.BoundedWorkerExecutor;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A single local copy executor, with no queued jobs or blocked shared scheduler threads. */
@Component
@ConditionalOnExpression("${agenvas.library.worker-enabled:true} && !${agenvas.recovery-mode:false}")
public class LibraryWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryWorker.class);
    private final LibraryService library;
    private final BoundedWorkerExecutor executor;

    public LibraryWorker(LibraryService library, ShutdownGate shutdown) {
        this.library = library;
        this.executor = new BoundedWorkerExecutor(shutdown, "library-copy", LOGGER,
                "Library worker pass failed: {}");
    }

    @Scheduled(fixedDelayString = "${agenvas.library.poll-interval-ms:500}")
    public void tick() {
        executor.execute(() -> {
            library.cleanupNext();
            library.processNext();
        });
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}

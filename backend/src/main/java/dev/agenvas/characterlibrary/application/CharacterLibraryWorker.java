package dev.agenvas.characterlibrary.application;

import dev.agenvas.shared.lifecycle.BoundedWorkerExecutor;
import dev.agenvas.shared.lifecycle.ShutdownGate;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One bounded local-copy worker; recovery mode never claims or cleans operations. */
@Component
@ConditionalOnExpression("${agenvas.character-library.worker-enabled:true} && !${agenvas.recovery-mode:false}")
public class CharacterLibraryWorker {
    private final CharacterLibraryService service;
    private final BoundedWorkerExecutor executor;

    public CharacterLibraryWorker(CharacterLibraryService service, ShutdownGate shutdown) {
        this.service = service;
        this.executor = new BoundedWorkerExecutor(shutdown, "character-library-copy",
                LoggerFactory.getLogger(getClass()),
                "Character library worker pass failed: {}");
    }

    @Scheduled(fixedDelayString = "${agenvas.character-library.poll-interval-ms:500}")
    public void tick() {
        executor.execute(() -> {
            service.cleanupNext();
            service.processNext();
        });
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}

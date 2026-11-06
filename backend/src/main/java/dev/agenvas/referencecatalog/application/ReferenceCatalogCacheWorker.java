package dev.agenvas.referencecatalog.application;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated bounded executor; scheduled callbacks never perform network work on the shared scheduler. */
@Component
@ConditionalOnExpression("!${agenvas.recovery-mode:false} && ${agenvas.reference-catalog.cache.worker-enabled:true}")
public class ReferenceCatalogCacheWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ReferenceCatalogCacheWorker.class);
    private final ReferenceCatalogCacheService jobs;private final AtomicBoolean running=new AtomicBoolean();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"reference-cache");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public ReferenceCatalogCacheWorker(ReferenceCatalogCacheService jobs){this.jobs=jobs;}
    @Scheduled(fixedDelayString="${agenvas.reference-catalog.cache.scan-delay-ms:1000}",initialDelayString="${agenvas.reference-catalog.cache.scan-delay-ms:1000}")
    public void scan(){if(!running.compareAndSet(false,true))return;try{worker.execute(()->{try{jobs.processNext();}catch(RuntimeException failure){LOG.warn("Reference cache batch failed ({})",failure.getClass().getSimpleName());}finally{running.set(false);}});}catch(RejectedExecutionException rejected){running.set(false);}}
    @PreDestroy public void close(){worker.shutdownNow();}
}

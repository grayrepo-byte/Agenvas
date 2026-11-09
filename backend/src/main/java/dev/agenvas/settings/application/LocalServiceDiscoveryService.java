package dev.agenvas.settings.application;

import dev.agenvas.settings.infrastructure.LocalServiceProbe;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** Explicit, read-only discovery. It never saves configuration or grants endpoint authority. */
@org.springframework.stereotype.Service
public class LocalServiceDiscoveryService implements AutoCloseable {
    public enum Service { OLLAMA, COMFYUI }
    public enum Runtime { CONTAINER, NATIVE, UNKNOWN }
    public enum Status { FOUND, NOT_FOUND, UNRESOLVED, TIMEOUT,
        UNEXPECTED_RESPONSE, AUTH_REQUIRED, UNSUPPORTED_RUNTIME }
    public record Result(Service service, Runtime runtime, Status status, String endpoint) {}

    private final LocalServiceDiscoveryProperties properties;
    private final LocalServiceProbe probe;
    private final Clock clock;
    private final Duration deadline;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("local-service-discovery").factory());
    private final Semaphore capacity = new Semaphore(1);
    private final Object acceptanceLock = new Object();
    private Long lastAccepted;

    @Autowired
    public LocalServiceDiscoveryService(LocalServiceDiscoveryProperties properties, LocalServiceProbe probe,
            Clock clock) {
        this(properties, probe, clock, Duration.ofSeconds(3));
    }

    LocalServiceDiscoveryService(LocalServiceDiscoveryProperties properties, LocalServiceProbe probe,
            Clock clock, Duration deadline) {
        this.properties = properties; this.probe = probe; this.clock = clock; this.deadline = deadline;
    }

    public Result discover(Service service) {
        Objects.requireNonNull(service);
        if (!properties.enabled()) throw problem(HttpStatus.SERVICE_UNAVAILABLE,
                "LOCAL_SERVICE_DISCOVERY_DISABLED", "disabled", false);
        Runtime runtime = probe.runtime();
        var completed = new CompletableFuture<LocalServiceProbe.Detected>();
        var activeCall = new AtomicReference<Call>();
        var budget = new LocalServiceProbe.Budget(deadline);
        synchronized (acceptanceLock) {
            long now = clock.millis();
            if (lastAccepted != null && now - lastAccepted < 1000 || !capacity.tryAcquire()) throw busy();
            try {
                // Cancellation must not release this permit: an OS DNS lookup may ignore interruption.
                worker.execute(() -> {
                    LocalServiceProbe.Detected detected = null;
                    RuntimeException failure = null;
                    try { detected = probe.discover(service, runtime, activeCall, budget); }
                    catch (RuntimeException unavailable) { failure = unavailable; }
                    finally { capacity.release(); }
                    // Publish only after the actual probe relinquishes capacity. The bounded
                    // queue bridges executor bookkeeping until this worker takes its next job;
                    // the permit still rejects requests while DNS or HTTP is running.
                    if (failure == null) completed.complete(detected);
                    else completed.completeExceptionally(failure);
                });
            } catch (RejectedExecutionException unavailable) {
                capacity.release();
                throw busy();
            }
            lastAccepted = now;
        }
        try {
            LocalServiceProbe.Detected found = completed.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            return new Result(service, runtime, found.status(), found.endpoint());
        } catch (TimeoutException timeout) {
            budget.cancel(); cancel(activeCall, completed);
            return new Result(service, runtime, Status.TIMEOUT, null);
        } catch (InterruptedException interrupted) {
            budget.cancel(); cancel(activeCall, completed);
            Thread.currentThread().interrupt();
            return new Result(service, runtime, Status.TIMEOUT, null);
        } catch (ExecutionException failure) {
            // Never expose or log DNS, transport or upstream exception text.
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_SERVICE_DISCOVERY_FAILED", "failed", true);
        }
    }

    private static void cancel(AtomicReference<Call> call, CompletableFuture<?> completed) {
        completed.cancel(true);
        Call active = call.get();
        if (active != null) active.cancel();
    }

    private static ApiProblemException busy() {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "LOCAL_SERVICE_DISCOVERY_BUSY", "busy", true);
    }

    private static ApiProblemException problem(HttpStatus status, String code, String key, boolean retryable) {
        return new ApiProblemException(status, code, ApiMessage.of("api.local-service-discovery.title"),
                ApiMessage.of("api.local-service-discovery." + key), retryable);
    }

    @Override @PreDestroy public void close() { worker.shutdownNow(); }
}

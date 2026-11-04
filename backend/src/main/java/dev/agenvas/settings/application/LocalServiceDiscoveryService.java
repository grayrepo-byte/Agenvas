package dev.agenvas.settings.application;

import dev.agenvas.provider.application.ComfyUiEndpointPolicy;
import dev.agenvas.settings.infrastructure.LocalServiceProbe;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
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
    public enum Status { FOUND, NOT_FOUND, UNRESOLVED, BLOCKED_ADDRESS, TIMEOUT,
        UNEXPECTED_RESPONSE, AUTH_REQUIRED, UNSUPPORTED_RUNTIME }
    public record Result(Service service, Runtime runtime, Status status, String endpoint,
            boolean policyAllowed) {}

    private final LocalServiceDiscoveryProperties properties;
    private final LocalServiceProbe probe;
    private final LlmEndpointPolicy llmPolicy;
    private final ComfyUiEndpointPolicy comfyPolicy;
    private final Clock clock;
    private final Duration deadline;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("local-service-discovery").factory());
    private final Semaphore capacity = new Semaphore(1);
    private final Object acceptanceLock = new Object();
    private Long lastAccepted;

    @Autowired
    public LocalServiceDiscoveryService(LocalServiceDiscoveryProperties properties, LocalServiceProbe probe,
            LlmEndpointPolicy llmPolicy, ComfyUiEndpointPolicy comfyPolicy, Clock clock) {
        this(properties, probe, llmPolicy, comfyPolicy, clock, Duration.ofSeconds(3));
    }

    LocalServiceDiscoveryService(LocalServiceDiscoveryProperties properties, LocalServiceProbe probe,
            LlmEndpointPolicy llmPolicy, ComfyUiEndpointPolicy comfyPolicy, Clock clock, Duration deadline) {
        this.properties = properties; this.probe = probe; this.llmPolicy = llmPolicy;
        this.comfyPolicy = comfyPolicy; this.clock = clock; this.deadline = deadline;
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
                    try { completed.complete(probe.discover(service, runtime, activeCall, budget)); }
                    catch (RuntimeException failure) { completed.completeExceptionally(failure); }
                    finally { capacity.release(); }
                });
            } catch (RejectedExecutionException unavailable) {
                capacity.release();
                throw busy();
            }
            lastAccepted = now;
        }
        try {
            LocalServiceProbe.Detected found = completed.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            boolean allowed = found.status() == Status.FOUND && policyAllows(service, found);
            return new Result(service, runtime, found.status(), found.endpoint(), allowed);
        } catch (TimeoutException timeout) {
            budget.cancel(); cancel(activeCall, completed);
            return new Result(service, runtime, Status.TIMEOUT, null, false);
        } catch (InterruptedException interrupted) {
            budget.cancel(); cancel(activeCall, completed);
            Thread.currentThread().interrupt();
            return new Result(service, runtime, Status.TIMEOUT, null, false);
        } catch (ExecutionException failure) {
            // Never expose or log DNS, transport or upstream exception text.
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "LOCAL_SERVICE_DISCOVERY_FAILED", "failed", true);
        }
    }

    private boolean policyAllows(Service service, LocalServiceProbe.Detected found) {
        if (service == Service.COMFYUI) return comfyPolicy.allows(found.endpoint());
        URI endpoint = URI.create(found.endpoint());
        try {
            llmPolicy.requireAllowedOrigin(endpoint);
            llmPolicy.requireAllowedAddress(endpoint.getHost(), found.address());
            return true;
        } catch (ApiProblemException denied) {
            return false;
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

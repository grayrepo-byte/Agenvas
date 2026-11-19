package dev.agenvas.settings.infrastructure;

import dev.agenvas.settings.application.LlmEndpointPolicy;
import dev.agenvas.settings.application.ModelCatalog;
import dev.agenvas.shared.http.PinnedHttpClients;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import dev.agenvas.shared.error.ApiProblemException;

/** Explicit metadata operations have a separate transport from generation. */
@Component
public class ModelMetadataClient implements AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final LlmEndpointPolicy policy;
    private final Dns dns;
    private final Duration deadline;
    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(262144).maxNumberLength(32).build())
            .build()).build();
    // The permit bounds live operations, including resolvers that ignore cancellation.
    // One handoff slot lets a completed call admit its successor before the worker is idle.
    private final Semaphore capacity = new Semaphore(1);
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                var thread = new Thread(task, "model-metadata");
                thread.setDaemon(true);
                return thread;
            });

    @Autowired
    public ModelMetadataClient(LlmEndpointPolicy policy) {
        this(policy, Dns.SYSTEM, Duration.ofSeconds(10));
    }
    ModelMetadataClient(LlmEndpointPolicy policy, Dns dns, Duration deadline) {
        this.policy = policy;
        this.dns = dns;
        this.deadline = deadline;
    }
    public ModelCatalog fetch(ModelCatalog.Provider provider, String endpoint, String key, String modelId) {
        if (provider == null || key == null || key.length() < 8 || key.length() > 4096 || !key.matches("[!-~]+")
                || (modelId != null && !modelId.isEmpty() && !ModelCatalog.validModelId(modelId)))
            throw failure("INVALID");
        if (!capacity.tryAcquire())
            throw failure("BUSY");
        var active = new AtomicReference<Call>();
        var pending = new MetadataTask(() -> retrieve(provider, endpoint, key, modelId, active));
        try {
            worker.execute(pending);
        } catch (RejectedExecutionException busy) {
            pending.releaseCapacity();
            throw failure("BUSY");
        }
        try {
            return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            cancel(active);
            throw failure("CANCELED");
        } catch (TimeoutException expired) {
            pending.cancel(true);
            cancel(active);
            throw failure("TIMEOUT");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof MetadataFailure known)
                throw known;
            throw failure("UNAVAILABLE");
        }
    }

    private final class MetadataTask extends FutureTask<ModelCatalog> {
        private final AtomicBoolean released = new AtomicBoolean();

        MetadataTask(Callable<ModelCatalog> action) {
            super(action);
        }

        private void releaseCapacity() {
            if (released.compareAndSet(false, true))
                capacity.release();
        }

        // Publish completion only after retrieve has finished and its slot is reusable.
        @Override
        protected void set(ModelCatalog result) {
            releaseCapacity();
            super.set(result);
        }
        @Override
        protected void setException(Throwable failure) {
            releaseCapacity();
            super.setException(failure);
        }

        @Override
        public void run() {
            try {
                super.run();
            }
            // A task canceled before starting never invokes the callable or either setter.
            // Cancellation while running keeps capacity until the actual resolver returns.
            finally {
                releaseCapacity();
            }
        }
    }

    private ModelCatalog retrieve(ModelCatalog.Provider provider, String endpoint, String key, String modelId,
            AtomicReference<Call> active) {
        try {
            if (endpoint == null || endpoint.length() > 500)
                throw failure("INVALID");
            URI uri = URI.create(endpoint);
            policy.requireAllowedOrigin(uri);
            HttpUrl base = HttpUrl.get(endpoint);
            String suppliedHost = uri.getHost().replaceAll("^\\[|\\]$", "");
            String suppliedPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            // Avoid alternate numeric IP syntax and encoded dot-segments changing the admitted scope.
            if ((!suppliedHost.contains(":") && !base.host().equalsIgnoreCase(suppliedHost))
                    || !base.encodedPath().equals(suppliedPath))
                throw failure("INVALID");
            var client = PinnedHttpClients.pinnedUncaptured(host -> {
                if (!host.equalsIgnoreCase(base.host()))
                    throw new UnknownHostException("Blocked metadata target");
                List<InetAddress> answers = dns.lookup(host);
                if (answers.isEmpty())
                    throw new UnknownHostException("No metadata address");
                return answers;
            }, Duration.ofSeconds(2), Duration.ofSeconds(3), deadline);
            String prefix = base.encodedPath().replaceAll("/+$", "");
            var protocol = ModelMetadataProtocol.forProvider(provider);
            var catalog = protocol.fetch(prefix, modelId, key,
                    (path, cursor, body) -> request(client, base, path, protocol, key, body, active, cursor));
            var safe = new java.util.ArrayList<ModelCatalog.Model>();
            boolean partial = catalog.partial();
            // Even an authenticated service's strings are untrusted: do not turn a
            // credential echo into a read-back API for a saved server-side secret.
            for (var model : catalog.models()) {
                if (model.id().contains(key)) {
                    partial = true;
                    continue;
                }
                String digest = model.digest();
                if (digest != null && digest.contains(key)) {
                    digest = null;
                    partial = true;
                }
                safe.add(new ModelCatalog.Model(model.id(), model.contextWindowTokens(), model.inputLimitTokens(),
                        model.outputLimitTokens(), model.runtimeContextTokens(), digest,
                        model.deploymentContextTokens()));
            }
            return new ModelCatalog(List.copyOf(safe), partial);
        } catch (MetadataFailure known) {
            throw known;
        } catch (ApiProblemException blocked) {
            throw failure("INVALID");
        } catch (RuntimeException | java.io.IOException unavailable) {
            throw failure("UNAVAILABLE");
        }
    }

    private JsonNode request(okhttp3.OkHttpClient client, HttpUrl base, String path, ModelMetadataProtocol protocol,
            String key, JsonNode body, AtomicReference<Call> active, String cursor) throws java.io.IOException {
        if (Thread.currentThread().isInterrupted())
            throw failure("CANCELED");
        var urlBuilder = base.newBuilder().encodedPath(path).query(null).fragment(null);
        protocol.query(urlBuilder, cursor);
        var request = new Request.Builder().url(urlBuilder.build()).header("Accept", "application/json");
        protocol.authenticate(request, key);
        if (body != null)
            request.post(RequestBody.create(json.writeValueAsBytes(body), MediaType.get("application/json")));
        Call call = client.newCall(request.build());
        active.set(call);
        if (Thread.currentThread().isInterrupted()) {
            call.cancel();
            throw failure("CANCELED");
        }
        try (var response = call.execute()) {
            if (response.code() == 429 || response.code() >= 500)
                throw failure("UNAVAILABLE");
            if (!response.isSuccessful() || response.body() == null)
                throw failure("HTTP_ERROR");
            if (response.body().contentLength() > MAX_BYTES)
                throw failure("TOO_LARGE");
            byte[] bytes;
            try (var input = response.body().byteStream()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES)
                throw failure("TOO_LARGE");
            try {
                return json.readTree(bytes);
            } catch (RuntimeException invalid) {
                throw failure("INVALID");
            }
        } finally {
            active.compareAndSet(call, null);
        }
    }
    private static void cancel(AtomicReference<Call> active) {
        var call = active.get();
        if (call != null)
            call.cancel();
    }
    static MetadataFailure failure(String reason) {
        return new MetadataFailure("MODEL_METADATA_" + reason);
    }
    public static final class MetadataFailure extends RuntimeException {
        private MetadataFailure(String code) {
            super(code);
        }
    }
    @Override
    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}

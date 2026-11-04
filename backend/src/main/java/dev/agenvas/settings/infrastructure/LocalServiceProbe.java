package dev.agenvas.settings.infrastructure;

import dev.agenvas.settings.application.LocalServiceDiscoveryProperties;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Runtime;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Service;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Status;
import dev.agenvas.shared.http.PinnedHttpClients;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One fixed credential-free status request; no arbitrary URL, port or subnet input exists. */
@Component
public class LocalServiceProbe {
    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final String HOST_ALIAS = "host.docker.internal";
    private final LocalServiceDiscoveryProperties properties;
    private final Dns resolver;
    private final BooleanSupplier containerMarker;
    private final Supplier<String> osName;
    private final Function<Dns, Call.Factory> clients;
    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(MAX_BODY_BYTES).maxNumberLength(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** Internal result retains the pin only for existing-policy evaluation; never serialized to API. */
    public record Detected(Status status, String endpoint, InetAddress address) {}

    /** Absolute monotonic budget survives resolvers that swallow thread interruption. */
    public static final class Budget {
        private final long expiresAt;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public Budget(Duration duration) { expiresAt = System.nanoTime() + duration.toNanos(); }
        public long remainingNanos() { return Math.max(0, expiresAt - System.nanoTime()); }
        public boolean expired() { return cancelled.get() || remainingNanos() == 0; }
        public void cancel() { cancelled.set(true); }
    }

    @Autowired public LocalServiceProbe(LocalServiceDiscoveryProperties properties) {
        this(properties, Dns.SYSTEM,
                () -> Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv")),
                () -> System.getProperty("os.name", ""),
                dns -> PinnedHttpClients.pinnedUncaptured(dns, Duration.ofSeconds(1),
                        Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }

    /** Package seam for deterministic DNS, runtime and local synthetic HTTP tests only. */
    LocalServiceProbe(LocalServiceDiscoveryProperties properties, Dns resolver,
            BooleanSupplier containerMarker, Supplier<String> osName, Function<Dns, Call.Factory> clients) {
        this.properties = properties; this.resolver = resolver; this.containerMarker = containerMarker;
        this.osName = osName; this.clients = clients;
    }

    public Runtime runtime() {
        if (properties.runtime() == LocalServiceDiscoveryProperties.Mode.CONTAINER) return Runtime.CONTAINER;
        if (containerMarker.getAsBoolean()) return Runtime.CONTAINER;
        if (properties.runtime() == LocalServiceDiscoveryProperties.Mode.NATIVE) return Runtime.NATIVE;
        String os = osName.get().toLowerCase(Locale.ROOT);
        return os.startsWith("mac") || os.startsWith("windows") ? Runtime.NATIVE : Runtime.UNKNOWN;
    }

    public Detected discover(Service service, Runtime runtime, AtomicReference<Call> activeCall, Budget budget) {
        if (runtime == Runtime.UNKNOWN) return absent(Status.UNSUPPORTED_RUNTIME);
        if (budget.expired()) return absent(Status.TIMEOUT);
        String host = runtime == Runtime.CONTAINER ? HOST_ALIAS : "127.0.0.1";
        InetAddress address;
        try {
            if (runtime == Runtime.CONTAINER) {
                List<InetAddress> answers = resolver.lookup(HOST_ALIAS);
                if (answers.isEmpty()) return absent(Status.UNRESOLVED);
                for (InetAddress answer : answers) if (!privateIpv4(answer)) return absent(Status.BLOCKED_ADDRESS);
                address = answers.getFirst();
            } else address = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (UnknownHostException unresolved) { return absent(Status.UNRESOLVED); }
        if (budget.expired() || Thread.currentThread().isInterrupted()) return absent(Status.TIMEOUT);
        InetAddress pinned = address;
        Dns dns = requested -> {
            if (!host.equals(requested)) throw new UnknownHostException("Discovery target unavailable");
            return List.of(pinned);
        };
        int port = service == Service.OLLAMA ? 11434 : 8188;
        String path = service == Service.OLLAMA ? "/api/version" : "/system_stats";
        var request = new Request.Builder().url("http://" + host + ":" + port + path)
                .get().header("Accept", "application/json").header("Connection", "close").build();
        Call call = clients.apply(dns).newCall(request);
        activeCall.set(call);
        if (budget.expired() || Thread.currentThread().isInterrupted()) { call.cancel(); return absent(Status.TIMEOUT); }
        long remaining = budget.remainingNanos();
        if (remaining == 0) { call.cancel(); return absent(Status.TIMEOUT); }
        call.timeout().timeout(Math.min(TimeUnit.SECONDS.toNanos(2), remaining), TimeUnit.NANOSECONDS);
        try (Response response = call.execute()) {
            if (response.code() == 401 || response.code() == 403) return absent(Status.AUTH_REQUIRED);
            if (response.code() == 404) return absent(Status.NOT_FOUND);
            if (response.code() != 200 || response.body() == null) return absent(Status.UNEXPECTED_RESPONSE);
            // OkHttp transparently decompresses; the budget applies to decoded bytes, including chunked bodies.
            byte[] bytes = response.body().byteStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) return absent(Status.UNEXPECTED_RESPONSE);
            JsonNode body = json.readTree(bytes);
            if (!matches(service, body)) return absent(Status.UNEXPECTED_RESPONSE);
            String candidateHost = service == Service.COMFYUI ? address.getHostAddress() : host;
            if (budget.expired()) return absent(Status.TIMEOUT);
            return new Detected(Status.FOUND, "http://" + candidateHost + ":" + port
                    + (service == Service.OLLAMA ? "/v1" : ""), address);
        } catch (InterruptedIOException timeout) { return absent(Status.TIMEOUT); }
        catch (IOException unavailable) { return absent(Status.NOT_FOUND); }
        catch (tools.jackson.core.JacksonException malformed) { return absent(Status.UNEXPECTED_RESPONSE); }
        finally { activeCall.compareAndSet(call, null); }
    }

    private static boolean matches(Service service, JsonNode body) {
        if (body == null || !body.isObject()) return false;
        if (service == Service.OLLAMA) return text(body.path("version"));
        JsonNode system = body.path("system");
        return system.isObject() && text(system.path("os")) && text(system.path("python_version"))
                && body.path("devices").isArray();
    }

    private static boolean text(JsonNode value) { return value.isString() && !value.asString().isBlank(); }

    private static Detected absent(Status status) { return new Detected(status, null, null); }

    private static boolean privateIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
    }
}

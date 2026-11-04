package dev.agenvas.settings.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.agenvas.settings.application.LocalServiceDiscoveryProperties;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Runtime;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Service;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Status;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

class LocalServiceProbeTest {
    private final LocalServiceDiscoveryProperties properties = new LocalServiceDiscoveryProperties(true,
            LocalServiceDiscoveryProperties.Mode.AUTO);

    @Test void validatesAllDnsAnswersBeforeAnyConnection() throws Exception {
        for (String forbidden : List.of("8.8.8.8", "127.0.0.1", "169.254.169.254", "198.18.0.1", "::1", "fc00::1")) {
            AtomicInteger connections = new AtomicInteger();
            var probe = new LocalServiceProbe(properties, host -> List.of(ip("192.168.65.254"), ip(forbidden)),
                    () -> true, () -> "Linux", dns -> request -> { connections.incrementAndGet(); throw new AssertionError(); });
            assertThat(probe.discover(Service.OLLAMA, Runtime.CONTAINER, new AtomicReference<>(), budget()).status())
                    .isEqualTo(Status.BLOCKED_ADDRESS);
            assertThat(connections).hasValue(0);
        }
    }

    @Test void pinsOnlyOneCheckedAnswerAndUsesFixedCredentialFreeRequest() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        var probe = new LocalServiceProbe(properties, host -> {
            assertThat(host).isEqualTo("host.docker.internal"); lookups.incrementAndGet();
            return List.of(ip("192.168.65.254"), ip("10.1.2.3"));
        }, () -> true, () -> "Linux", dns -> request -> {
            requests.incrementAndGet();
            assertThat(request.url().toString()).isEqualTo("http://host.docker.internal:11434/api/version");
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.header("Authorization")).isNull(); assertThat(request.header("Cookie")).isNull();
            try { assertThat(dns.lookup("host.docker.internal")).containsExactly(ip("192.168.65.254")); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return response(request, 200, "{\"version\":\"custom-dev\",\"secret\":\"never-return\"}");
        });
        var found = probe.discover(Service.OLLAMA, Runtime.CONTAINER, new AtomicReference<>(), budget());
        assertThat(found.status()).isEqualTo(Status.FOUND);
        assertThat(found.endpoint()).isEqualTo("http://host.docker.internal:11434/v1");
        assertThat(lookups).hasValue(1); assertThat(requests).hasValue(1);
    }

    @Test void nativeDoesNotResolveDnsAndComfyUsesLiteralCandidate() throws Exception {
        var probe = new LocalServiceProbe(properties, host -> { throw new AssertionError("No native DNS"); },
                () -> false, () -> "Mac OS X", dns -> request -> {
                    assertThat(request.url().toString()).isEqualTo("http://127.0.0.1:8188/system_stats");
                    return response(request, 200, "{\"system\":{\"os\":\"posix\",\"python_version\":\"3.10\",\"argv\":[\"/private/path\"]},\"devices\":[]}");
                });
        assertThat(probe.runtime()).isEqualTo(Runtime.NATIVE);
        var found = probe.discover(Service.COMFYUI, Runtime.NATIVE, new AtomicReference<>(), budget());
        assertThat(found.status()).isEqualTo(Status.FOUND);
        assertThat(found.endpoint()).isEqualTo("http://127.0.0.1:8188");
    }

    @Test void containerComfyReturnsExactlyThePinnedIpv4Candidate() {
        var probe = new LocalServiceProbe(properties, host -> List.of(ip("172.18.0.1")),
                () -> true, () -> "Linux", dns -> request -> {
                    assertThat(request.url().toString()).isEqualTo("http://host.docker.internal:8188/system_stats");
                    return response(request, 200, "{\"system\":{\"os\":\"posix\",\"python_version\":\"3.10\"},\"devices\":[]}");
                });
        assertThat(probe.discover(Service.COMFYUI, Runtime.CONTAINER, new AtomicReference<>(), budget()).endpoint())
                .isEqualTo("http://172.18.0.1:8188");
    }

    @Test void rejectsUnexpectedAndOversizedRepliesAndReportsAuthentication() {
        for (String body : List.of("<html>hello</html>", "{}", "{\"version\":\"\"}", "{\"version\":\"" + "x".repeat(32768) + "\"}", "{\"version\":\"a\"} {}")) {
            var probe = fixture(200, body);
            assertThat(probe.discover(Service.OLLAMA, Runtime.NATIVE, new AtomicReference<>(), budget()).status())
                    .isEqualTo(Status.UNEXPECTED_RESPONSE);
        }
        assertThat(fixture(401, "secret").discover(Service.OLLAMA, Runtime.NATIVE, new AtomicReference<>(), budget()).status())
                .isEqualTo(Status.AUTH_REQUIRED);
        assertThat(fixture(302, "redirect").discover(Service.OLLAMA, Runtime.NATIVE, new AtomicReference<>(), budget()).status())
                .isEqualTo(Status.UNEXPECTED_RESPONSE);
    }

    @Test void unknownRuntimeNeverConnects() {
        var probe = new LocalServiceProbe(properties, Dns.SYSTEM, () -> false, () -> "Linux", dns -> request -> { throw new AssertionError(); });
        assertThat(probe.runtime()).isEqualTo(Runtime.UNKNOWN);
        assertThat(probe.discover(Service.OLLAMA, Runtime.UNKNOWN, new AtomicReference<>(), budget()).status()).isEqualTo(Status.UNSUPPORTED_RUNTIME);
    }

    @Test void cancellationAndAbsoluteDeadlinePreventLateHttpAfterDnsClearsInterrupt() {
        for (boolean cancel : List.of(true, false)) {
            var budget = new LocalServiceProbe.Budget(java.time.Duration.ofMillis(10));
            var probe = new LocalServiceProbe(properties, host -> {
                if (cancel) budget.cancel();
                else {
                    long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20);
                    while (System.nanoTime() < until) Thread.onSpinWait();
                }
                Thread.interrupted(); // Simulates a native resolver swallowing interruption.
                return List.of(ip("192.168.65.254"));
            }, () -> true, () -> "Linux", dns -> request -> { throw new AssertionError("Late HTTP forbidden"); });
            assertThat(probe.discover(Service.OLLAMA, Runtime.CONTAINER, new AtomicReference<>(), budget).status())
                    .isEqualTo(Status.TIMEOUT);
        }
    }

    @Test void knownContainerMarkerOverridesNativeAndUnresolvedDnsIsSafe() {
        var nativeProperties = new LocalServiceDiscoveryProperties(true, LocalServiceDiscoveryProperties.Mode.NATIVE);
        var probe = new LocalServiceProbe(nativeProperties, host -> { throw new java.net.UnknownHostException("private resolver text"); },
                () -> true, () -> "Mac OS X", dns -> request -> { throw new AssertionError(); });
        assertThat(probe.runtime()).isEqualTo(Runtime.CONTAINER);
        assertThat(probe.discover(Service.COMFYUI, Runtime.CONTAINER, new AtomicReference<>(), budget()).status())
                .isEqualTo(Status.UNRESOLVED);
    }

    private LocalServiceProbe fixture(int status, String body) {
        return new LocalServiceProbe(properties, Dns.SYSTEM, () -> false, () -> "Mac OS X",
                dns -> request -> response(request, status, body));
    }

    private static Call response(okhttp3.Request request, int status, String body) {
        Call call = mock(Call.class);
        when(call.timeout()).thenReturn(new okio.Timeout());
        try { when(call.execute()).thenReturn(new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(ResponseBody.create(body, MediaType.get("application/json"))).build()); }
        catch (Exception failure) { throw new AssertionError(failure); }
        return call;
    }
    private static LocalServiceProbe.Budget budget() { return new LocalServiceProbe.Budget(java.time.Duration.ofSeconds(3)); }
    private static InetAddress ip(String value) throws java.net.UnknownHostException { return InetAddress.getByName(value); }
}

package dev.agenvas.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.agenvas.provider.application.ComfyUiEndpointPolicy;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Runtime;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Service;
import dev.agenvas.settings.application.LocalServiceDiscoveryService.Status;
import dev.agenvas.settings.infrastructure.LocalServiceProbe;
import dev.agenvas.shared.error.ApiProblemException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LocalServiceDiscoveryServiceTest {
    private final LocalServiceProbe probe = mock(LocalServiceProbe.class);
    private final MutableClock clock = new MutableClock();

    @Test void foundDoesNotGrantLlmHttpPermissionAndComfyKeepsExistingPolicy() throws Exception {
        when(probe.runtime()).thenReturn(Runtime.NATIVE);
        when(probe.discover(eq(Service.OLLAMA), eq(Runtime.NATIVE), any(), any()))
                .thenReturn(found("http://127.0.0.1:11434/v1", "127.0.0.1"));
        when(probe.discover(eq(Service.COMFYUI), eq(Runtime.NATIVE), any(), any()))
                .thenReturn(found("http://127.0.0.1:8188", "127.0.0.1"));
        try (var service = service(new LlmEndpointProperties(false), Duration.ofSeconds(1))) {
            var ollama = service.discover(Service.OLLAMA);
            assertThat(ollama.status()).isEqualTo(Status.FOUND);
            assertThat(ollama.policyAllowed()).isFalse();
            clock.advance();
            assertThat(service.discover(Service.COMFYUI).policyAllowed()).isTrue();
        }
        try (var service = service(new LlmEndpointProperties(true), Duration.ofSeconds(1))) {
            assertThat(service.discover(Service.OLLAMA).policyAllowed()).isTrue();
        }
    }

    @Test void defaultPrivateHttpAllowsThePinnedContainerCandidate() throws Exception {
        when(probe.runtime()).thenReturn(Runtime.CONTAINER);
        when(probe.discover(eq(Service.OLLAMA), eq(Runtime.CONTAINER), any(), any()))
                .thenReturn(found("http://host.docker.internal:11434/v1", "192.168.65.254"));
        try (var service = service(new LlmEndpointProperties(false), Duration.ofSeconds(1))) {
            assertThat(service.discover(Service.OLLAMA).policyAllowed()).isTrue();
        }
    }

    @Test void exactDeploymentOriginAllowsThePinnedContainerCandidate() throws Exception {
        when(probe.runtime()).thenReturn(Runtime.CONTAINER);
        when(probe.discover(eq(Service.OLLAMA), eq(Runtime.CONTAINER), any(), any()))
                .thenReturn(found("http://host.docker.internal:11434/v1", "192.168.65.254"));
        try (var service = service(new LlmEndpointProperties(false,
                List.of("http://host.docker.internal:11434")), Duration.ofSeconds(1))) {
            assertThat(service.discover(Service.OLLAMA).policyAllowed()).isTrue();
        }
    }

    @Test void timedOutUninterruptibleResolverRetainsActualWorkerCapacity() throws Exception {
        when(probe.runtime()).thenReturn(Runtime.CONTAINER);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        when(probe.discover(any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                boolean done = false;
                while (!done) try { done = release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { workerInterrupted.set(true); }
                return new LocalServiceProbe.Detected(Status.UNRESOLVED, null, null);
            } finally { exited.countDown(); }
        });
        try (var service = service(new LlmEndpointProperties(false), Duration.ofMillis(100))) {
            assertThat(service.discover(Service.OLLAMA).status()).isEqualTo(Status.TIMEOUT);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            clock.advance();
            assertBusy(() -> service.discover(Service.COMFYUI));
            verify(probe, times(1)).discover(any(), any(), any(), any());
            release.countDown();
            assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(workerInterrupted).isFalse();
        } finally { release.countDown(); }
    }

    @Test void enforcesRequestIntervalAndDeploymentDisableWithoutNetwork() {
        when(probe.runtime()).thenReturn(Runtime.NATIVE);
        when(probe.discover(any(), any(), any(), any())).thenReturn(new LocalServiceProbe.Detected(Status.NOT_FOUND, null, null));
        try (var service = service(new LlmEndpointProperties(false), Duration.ofSeconds(1))) {
            assertThat(service.discover(Service.OLLAMA).status()).isEqualTo(Status.NOT_FOUND);
            assertBusy(() -> service.discover(Service.OLLAMA));
        }
        reset(probe);
        try (var disabled = new LocalServiceDiscoveryService(new LocalServiceDiscoveryProperties(false,
                LocalServiceDiscoveryProperties.Mode.AUTO), probe, new LlmEndpointPolicy(new LlmEndpointProperties(false)),
                new ComfyUiEndpointPolicy(), clock)) {
            assertThatThrownBy(() -> disabled.discover(Service.OLLAMA)).isInstanceOfSatisfying(ApiProblemException.class,
                    failure -> assertThat(failure.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            verifyNoInteractions(probe);
        }
    }

    private LocalServiceDiscoveryService service(LlmEndpointProperties llm, Duration deadline) {
        return new LocalServiceDiscoveryService(new LocalServiceDiscoveryProperties(true,
                LocalServiceDiscoveryProperties.Mode.AUTO), probe, new LlmEndpointPolicy(llm), new ComfyUiEndpointPolicy(), clock, deadline);
    }
    private static LocalServiceProbe.Detected found(String endpoint, String address) throws Exception {
        return new LocalServiceProbe.Detected(Status.FOUND, endpoint, InetAddress.getByName(address));
    }
    private static void assertBusy(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(ApiProblemException.class,
                failure -> assertThat(failure.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(10000);
        void advance() { millis.addAndGet(2000); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }
}
